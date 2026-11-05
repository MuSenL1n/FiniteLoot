package vip.naya.finiteloot.claim;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import vip.naya.finiteloot.claim.ClaimStateMachine.ClaimContext;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.container.WorldIds;
import vip.naya.finiteloot.data.ClaimAllocation;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.loot.ItemStacks;
import vip.naya.finiteloot.loot.LootAdvancementService;
import vip.naya.finiteloot.loot.LootGenerator;

/**
 * Drives a claim from "player opened a container" to "what the player sees".
 *
 * <p>This is the adapter: it owns the database round trips and the main-thread hops, and it delegates
 * every decision to {@link ClaimStateMachine}. The split is deliberate -- the original interleaved the two,
 * which is why none of its branch logic was testable.
 *
 * <p>Three pieces of state are kept in memory, and each one guards a specific hazard:
 *
 * <ul>
 *   <li>{@code inFlight} -- one operation per (container, player). Double-clicking a chest sends two
 *       interact packets; without this they would race and could both allocate a slot.
 *   <li>{@code finalizing} -- containers currently being handed back to vanilla. While set, claims for
 *       that container are dropped, because its inventory is being replaced.
 *   <li>{@code exhausted} -- the per-container "no slots left" flag, used by the hopper and break
 *       protections in P7.
 * </ul>
 *
 * <p><b>Threading contract:</b> {@link #open} may be called from the server thread; every callback that
 * touches a player is bounced back onto the server thread, because the database completes on its own
 * executor and players must not be touched off-thread.
 */
public final class ClaimController {

    private final MinecraftServer server;
    private final Database database;
    private final ContainerManager containers;
    private final ExhaustedContainers exhausted;
    private final RewardPresenter presenter;
    private final LootGenerator generator;
    private final LootAdvancementService lootAdvancements;
    private final Supplier<PluginSettings> settings;
    private final Supplier<Messages> messages;
    private final Logger logger;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> finalizing = ConcurrentHashMap.newKeySet();

    public ClaimController(
            MinecraftServer server,
            Database database,
            ContainerManager containers,
            ExhaustedContainers exhausted,
            RewardPresenter presenter,
            LootGenerator generator,
            LootAdvancementService lootAdvancements,
            Supplier<PluginSettings> settings,
            Supplier<Messages> messages,
            Logger logger) {
        this.server = server;
        this.database = database;
        this.containers = containers;
        this.exhausted = exhausted;
        this.presenter = presenter;
        this.generator = generator;
        this.lootAdvancements = lootAdvancements;
        this.settings = settings;
        this.messages = messages;
        this.logger = logger;
    }

    /**
     * Handle a player interacting with a managed container.
     *
     * <p>Mirrors the original's chain: find or create the container row, allocate this player's claim, then
     * re-read the row so the decision sees the post-allocation counts.
     */
    public void open(ServerPlayer player, ContainerTarget target) {
        if (finalizing.contains(target.id())) {
            return;
        }
        String operationKey = target.id() + ":" + player.getUUID();
        if (!inFlight.add(operationKey)) {
            return;
        }

        boolean counted = shouldCount(player);
        database.findContainer(target.id())
                .thenCompose(existing -> existing == null
                        ? database.upsertContainer(createRecord(target))
                        : CompletableFuture.completedFuture(existing))
                .thenCompose(record -> database
                        .allocateClaim(record.id(), player.getUUID(), player.getGameProfile().getName(),
                                counted, shouldReopenCompleted())
                        .thenCompose(allocation -> database.findContainer(record.id())
                                .thenApply(latest -> new AllocationWithRecord(
                                        latest == null ? record : latest, allocation))))
                .whenComplete((result, throwable) -> onServerThread(() -> {
                    if (throwable != null) {
                        fail(player, operationKey, throwable);
                        return;
                    }
                    handleAllocation(player, target, result.record(), result.allocation(), counted,
                            operationKey);
                }));
    }

    private void handleAllocation(ServerPlayer player, ContainerTarget target, ContainerRecord record,
            ClaimAllocation allocation, boolean counted, String operationKey) {
        if (record.claimCount() >= record.maxClaims()) {
            exhausted.markExhausted(record.id());
        }

        ClaimContext context = contextFor(record, allocation, counted, false);
        ClaimAction action = ClaimStateMachine.decide(context);

        switch (action) {
            case OPEN_PERSONAL -> {
                ItemStack[] contents = readContents(allocation, player, operationKey);
                if (contents == null) {
                    return;
                }
                inFlight.remove(operationKey);
                lootAdvancements.award(player, record.lootTable());
                openPersonal(player, target, record, contents);
            }
            case OPEN_NORMAL -> {
                inFlight.remove(operationKey);
                lootAdvancements.award(player, record.lootTable());
                openNormal(player, target);
            }
            case COMPLETE_AND_OPEN_NORMAL -> {
                ItemStack[] contents = readContents(allocation, player, operationKey);
                if (contents == null) {
                    return;
                }
                // Stamp the stored inventory as completed before handing the container over, so a reopen
                // cannot resurrect it as an active personal inventory.
                persistCompletion(player, target, record, allocation.contents(), operationKey);
            }
            case RECOVER_FINAL_VANILLA -> {
                ItemStack[] contents = allocation.kind() == ClaimAllocation.Kind.COMPLETED
                        ? new ItemStack[target.sourceInventory().getContainerSize()]
                        : readContents(allocation, player, operationKey);
                if (contents == null) {
                    return;
                }
                recoverFinalVanilla(player, target, record, contents, operationKey);
            }
            case RELEASE_AND_OPEN_NORMAL -> {
                inFlight.remove(operationKey);
                release(target, record.id());
                if (isOnline(player)) {
                    presenter.openNormal(player, target);
                    messages.get().send(player, "exhausted-released");
                }
            }
            case SHOW_EXHAUSTED -> {
                inFlight.remove(operationKey);
                messages.get().send(player, "exhausted");
            }
            case SHOW_COMPLETED -> {
                inFlight.remove(operationKey);
                messages.get().send(player, "completed");
            }
            case SHOW_LOADING -> {
                inFlight.remove(operationKey);
                messages.get().send(player, "loading");
            }
            case SHOW_DATABASE_ERROR -> {
                inFlight.remove(operationKey);
                messages.get().send(player, "database-error");
            }
            case PERSIST_NEW -> persistNew(player, target, record, operationKey, counted, false);
            case PERSIST_NEW_FINAL_VANILLA -> persistNew(player, target, record, operationKey, counted, true);
        }
    }

    private void persistNew(ServerPlayer player, ContainerTarget target, ContainerRecord record,
            String operationKey, boolean counted, boolean finalVanillaClaim) {
        ItemStack[] generated;
        try {
            generated = generator.generate(record, target, player);
        } catch (LootGenerator.LootResolutionException | RuntimeException exception) {
            // The claim row is already allocated at this point, so it must be released or the player
            // permanently occupies a slot without ever receiving loot.
            database.releasePendingClaim(record.id(), player.getUUID());
            fail(player, operationKey, exception);
            return;
        }

        byte[] bytes;
        try {
            bytes = ItemStacks.serialize(generated, server.registryAccess());
        } catch (IOException exception) {
            database.releasePendingClaim(record.id(), player.getUUID());
            fail(player, operationKey, exception);
            return;
        }

        boolean generatedEmpty = ItemStacks.isEmpty(generated);
        GeneratedClaimAction after = ClaimStateMachine.afterGeneration(
                contextFor(record, null, counted, generatedEmpty), generatedEmpty, finalVanillaClaim);

        // Paper marks the container as finalizing only on the vanilla path, before the write, so that no
        // other claim can slip in between the write and the handover.
        if (finalVanillaClaim) {
            finalizing.add(record.id());
        }
        boolean completed = after == GeneratedClaimAction.OPEN_COMPLETED_NORMAL
                || after == GeneratedClaimAction.SHOW_COMPLETED;

        database.finalizeClaim(record.id(), player.getUUID(), bytes, completed)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        database.releasePendingClaim(record.id(), player.getUUID());
                        finalizing.remove(record.id());
                    }
                    onServerThread(() -> {
                        if (throwable != null) {
                            inFlight.remove(operationKey);
                            fail(player, operationKey, throwable);
                            return;
                        }
                        if (!isOnline(player)) {
                            // The player left mid-claim. The loot is already stored, so they will get it on
                            // their next visit; do not touch the container.
                            inFlight.remove(operationKey);
                            finalizing.remove(record.id());
                            return;
                        }
                        lootAdvancements.award(player, record.lootTable());
                        switch (after) {
                            case OPEN_PERSONAL -> {
                                inFlight.remove(operationKey);
                                openPersonal(player, target, record, generated);
                            }
                            case OPEN_COMPLETED_NORMAL -> {
                                inFlight.remove(operationKey);
                                finalizing.remove(record.id());
                                openNormal(player, target);
                            }
                            case SHOW_COMPLETED -> {
                                inFlight.remove(operationKey);
                                finalizing.remove(record.id());
                                messages.get().send(player, "completed");
                            }
                            case TRANSITION_TO_VANILLA -> transitionToVanilla(
                                    player, target, record, generated, operationKey);
                        }
                    });
                });
    }

    private void recoverFinalVanilla(ServerPlayer player, ContainerTarget target, ContainerRecord record,
            ItemStack[] contents, String operationKey) {
        database.isLastCountedClaimant(record.id(), player.getUUID())
                .whenComplete((lastClaimant, throwable) -> onServerThread(() -> {
                    if (throwable != null) {
                        fail(player, operationKey, throwable);
                        return;
                    }
                    if (Boolean.TRUE.equals(lastClaimant)) {
                        finalizing.add(record.id());
                        transitionToVanilla(player, target, record, contents, operationKey);
                    } else {
                        // Someone counted is still behind us, so the container must stay managed.
                        inFlight.remove(operationKey);
                        openPersonal(player, target, record, contents);
                    }
                }));
    }

    private void transitionToVanilla(ServerPlayer player, ContainerTarget target, ContainerRecord record,
            ItemStack[] contents, String operationKey) {
        presenter.closeContainerAndSave(record.id()).whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                finalizing.remove(record.id());
                fail(player, operationKey, throwable);
                return;
            }
            try {
                containers.restoreVanillaInventory(target, java.util.Arrays.asList(contents));
                inFlight.remove(operationKey);
                finalizing.remove(record.id());
                exhausted.clear(record.id());
                if (isOnline(player)) {
                    presenter.openNormal(player, target);
                    if (settings.get().showFinalClaimMessage()) {
                        messages.get().send(player, "final-claim");
                    }
                }
            } catch (RuntimeException exception) {
                // Without this the failure is invisible: the exception lands in the future returned by
                // whenComplete, which nobody inspects, so the handover silently does nothing and the
                // container stays managed. Observed as "claim row written, loot table still set, no
                // window, no log line".
                finalizing.remove(record.id());
                inFlight.remove(operationKey);
                logger.error("Final-claim handover failed for container {}", record.id(), exception);
                fail(player, operationKey, exception);
            }
            if (settings.get().clearPersonalInventoriesOnFinalClaim()) {
                database.removeContainer(record.id()).whenComplete((removed, cleanupFailure) -> {
                    if (cleanupFailure != null) {
                        logger.error("Failed to clear finalized container data {}", record.id(), cleanupFailure);
                    }
                });
            }
        }));
    }

    private void persistCompletion(ServerPlayer player, ContainerTarget target, ContainerRecord record,
            byte[] contents, String operationKey) {
        database.saveInventory(record.id(), player.getUUID(), contents, true)
                .whenComplete((ignored, throwable) -> onServerThread(() -> {
                    inFlight.remove(operationKey);
                    if (throwable != null) {
                        fail(player, operationKey, throwable);
                    } else if (isOnline(player)) {
                        openNormal(player, target);
                    }
                }));
    }

    private void openPersonal(ServerPlayer player, ContainerTarget target, ContainerRecord record,
            ItemStack[] contents) {
        presenter.openPersonal(player, record.id(), contents, target);
        if (settings.get().showRemainingClaims()) {
            messages.get().send(player, "remaining-claims", Map.of(
                    "remaining", Integer.toString(Math.max(0, record.maxClaims() - record.claimCount())),
                    "max", Integer.toString(record.maxClaims())));
        }
    }

    private void openNormal(ServerPlayer player, ContainerTarget target) {
        containers.prepareNormalInventory(target);
        presenter.openNormal(player, target);
    }

    /**
     * Lets an exhausted container be broken, by releasing it just before the block drops.
     *
     * <p>Port of Paper's method of the same name, with the block turned into a level and a position. The order
     * matters: the container is released (identity cleared, row deleted) so the drop is a plain chest whose loot
     * is already empty, rather than a managed one that would be re-registered by the next player who places it.
     *
     * <p>Returns false for everything it is not responsible for -- wrong config, no id, not exhausted, currently
     * being handed back -- and the caller then cancels the break. Any of those returning true without actually
     * releasing would break the block open on a container that is still managed.
     */
    public boolean releaseExhaustedContainer(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos) {
        if (!settings.get().allowBreakingExhaustedContainers()) {
            return false;
        }
        UUID id = containers.existingId(level, pos).orElse(null);
        if (id == null || !exhausted.isExhausted(id) || finalizing.contains(id)) {
            return false;
        }
        var resolved = containers.resolve(level, pos);
        if (resolved.isEmpty()) {
            return false;
        }
        release(resolved.get(), id);
        return true;
    }

    /** Drop a container out of management, e.g. when an exhausted one is broken open. */
    public void release(ContainerTarget target, UUID containerId) {
        presenter.closeContainer(containerId);
        containers.releaseToNormal(target);
        exhausted.clear(containerId);
        database.removeContainer(containerId).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                logger.error("Failed to remove exhausted container data {}", containerId, throwable);
            }
        });
    }

    private ItemStack[] readContents(ClaimAllocation allocation, ServerPlayer player, String operationKey) {
        try {
            return ItemStacks.deserialize(allocation.contents(), server.registryAccess());
        } catch (IOException exception) {
            // A corrupt blob is reported rather than shown as an empty chest: an empty chest would look
            // like a successful claim and the loot would be lost without a trace.
            inFlight.remove(operationKey);
            fail(player, operationKey, exception);
            return null;
        }
    }

    private ClaimContext contextFor(ContainerRecord record, ClaimAllocation allocation, boolean counted,
            boolean contentsEmpty) {
        PluginSettings current = settings.get();
        boolean blobPresent = allocation == null || allocation.contents() != null;
        return new ClaimContext(
                allocation == null ? ClaimAllocation.Kind.NEW : allocation.kind(),
                counted,
                record.claimCount(),
                record.maxClaims(),
                blobPresent,
                contentsEmpty,
                current.finalClaimAction(),
                current.completedContainerBecomesNormal(),
                current.preventItemInsertion(),
                current.allowBreakingExhaustedContainers());
    }

    private ContainerRecord createRecord(ContainerTarget target) {
        String lootTable = target.lootTable() == null ? null : target.lootTable().location().toString();
        var primary = target.primary().getBlockPos();
        ServerLevel level = (ServerLevel) target.primary().getLevel();
        Integer partnerX = null;
        Integer partnerY = null;
        Integer partnerZ = null;
        if (target.tiles().size() > 1) {
            var partner = target.tiles().get(1).getBlockPos();
            partnerX = partner.getX();
            partnerY = partner.getY();
            partnerZ = partner.getZ();
        }
        return new ContainerRecord(
                target.id(), WorldIds.of(level), primary.getX(), primary.getY(), primary.getZ(),
                partnerX, partnerY, partnerZ, lootTable, null,
                settings.get().maxClaimsFor(lootTable), 0, false);
    }

    private boolean shouldCount(ServerPlayer player) {
        PluginSettings current = settings.get();
        if (!current.countCreativePlayers() && player.isCreative()) {
            return false;
        }
        // Permission (D6): vanilla OP level 2 stands in for the old "finiteloot.bypass" node. The
        // admin-uuids list is layered on top of this in P6.
        return !(current.adminBypassCounts() && player.hasPermissions(2));
    }

    private boolean shouldReopenCompleted() {
        PluginSettings current = settings.get();
        return ClaimStateMachine.shouldReopenCompleted(
                current.finalClaimAction(),
                current.completedContainerBecomesNormal(),
                current.preventItemInsertion());
    }

    private static boolean isOnline(ServerPlayer player) {
        return player != null && !player.hasDisconnected();
    }

    private void fail(ServerPlayer player, String operationKey, Throwable throwable) {
        inFlight.remove(operationKey);
        logger.error("Claim operation failed for {}", player.getUUID(), throwable);
        if (isOnline(player)) {
            messages.get().send(player, "database-error");
        }
    }

    private void onServerThread(Runnable runnable) {
        if (server.isSameThread()) {
            runnable.run();
        } else {
            server.execute(runnable);
        }
    }

    private record AllocationWithRecord(ContainerRecord record, ClaimAllocation allocation) {
    }
}
