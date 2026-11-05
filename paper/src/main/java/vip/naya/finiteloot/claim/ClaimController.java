package vip.naya.finiteloot.claim;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vip.naya.finiteloot.config.FinalClaimAction;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.compat.PiglinAngerService;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.data.ClaimAllocation;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.gui.RewardSessionManager;
import vip.naya.finiteloot.loot.ItemStacks;
import vip.naya.finiteloot.loot.LootAdvancementService;
import vip.naya.finiteloot.loot.LootGenerator;

public final class ClaimController {
    private final JavaPlugin plugin;
    private final Database database;
    private final ContainerManager containers;
    private final ExhaustedContainers exhausted;
    private final RewardSessionManager sessions;
    private final LootGenerator generator;
    private final LootAdvancementService lootAdvancements;
    private final PiglinAngerService piglinAnger;
    private final java.util.function.Supplier<PluginSettings> settings;
    private final java.util.function.Supplier<Messages> messages;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<java.util.UUID> finalizingContainers = ConcurrentHashMap.newKeySet();

    public ClaimController(
            JavaPlugin plugin,
            Database database,
            ContainerManager containers,
            ExhaustedContainers exhausted,
            RewardSessionManager sessions,
            LootGenerator generator,
            LootAdvancementService lootAdvancements,
            PiglinAngerService piglinAnger,
            java.util.function.Supplier<PluginSettings> settings,
            java.util.function.Supplier<Messages> messages) {
        this.plugin = plugin;
        this.database = database;
        this.containers = containers;
        this.exhausted = exhausted;
        this.sessions = sessions;
        this.generator = generator;
        this.lootAdvancements = lootAdvancements;
        this.piglinAnger = piglinAnger;
        this.settings = settings;
        this.messages = messages;
    }

    public void open(Player player, ContainerTarget target) {
        if (finalizingContainers.contains(target.id())) {
            return;
        }
        String operationKey = target.id() + ":" + player.getUniqueId();
        if (!inFlight.add(operationKey)) {
            return;
        }
        boolean counted = shouldCount(player);
        database.findContainer(target.id())
                .thenCompose(existing -> existing == null
                        ? database.upsertContainer(createRecord(target))
                        : java.util.concurrent.CompletableFuture.completedFuture(existing))
                .thenCompose(record -> database.allocateClaim(
                                record.id(), player.getUniqueId(), player.getName(), counted,
                                shouldReopenCompleted())
                        .thenCompose(allocation -> database.findContainer(record.id())
                                .thenApply(latest -> new AllocationWithRecord(
                                        latest == null ? record : latest, allocation, counted))))
                .whenComplete((result, throwable) -> runMain(() -> {
                    if (throwable != null) {
                        fail(player, operationKey, throwable);
                        return;
                    }
                    handleAllocation(player, target, result.record(), result.allocation(),
                            result.counted(), operationKey);
                }));
    }

    private void handleAllocation(
            Player player,
            ContainerTarget target,
            ContainerRecord record,
            ClaimAllocation allocation,
            boolean counted,
            String operationKey) {
        if (record.claimCount() >= record.maxClaims()) {
            exhausted.markExhausted(record.id());
        }
        switch (allocation.kind()) {
            case EXISTING -> {
                if (allocation.contents() == null) {
                    inFlight.remove(operationKey);
                    messages.get().send(player, "database-error");
                    return;
                }
                lootAdvancements.award(player, record.lootTable());
                ItemStack[] contents = ItemStacks.deserialize(allocation.contents());
                if (isFinalVanillaClaim(record, counted)) {
                    recoverFinalVanilla(player, target, record, contents, operationKey);
                } else if (isPersonalCompletionEnabled() && ItemStacks.isEmpty(contents)) {
                    completeAndOpenNormal(player, target, record, allocation.contents(), operationKey);
                } else {
                    inFlight.remove(operationKey);
                    openInventory(player, target, record, contents);
                }
            }
            case COMPLETED -> {
                lootAdvancements.award(player, record.lootTable());
                if (isFinalVanillaClaim(record, counted)) {
                    recoverFinalVanilla(player, target, record,
                            new ItemStack[target.sourceInventory().getSize()], operationKey);
                } else if (isPersonalCompletionEnabled()) {
                    inFlight.remove(operationKey);
                    openNormalInventory(player, target);
                } else {
                    inFlight.remove(operationKey);
                    messages.get().send(player, "completed");
                }
            }
            case EXHAUSTED -> {
                inFlight.remove(operationKey);
                if (settings.get().allowBreakingExhaustedContainers()
                        && !finalizingContainers.contains(record.id())) {
                    release(target, record.id());
                    if (player.isOnline()) {
                        player.openInventory(target.sourceInventory());
                        piglinAnger.reactToOpen(player, target);
                        messages.get().send(player, "exhausted-released");
                    }
                } else {
                    messages.get().send(player, "exhausted");
                }
            }
            case PENDING -> {
                inFlight.remove(operationKey);
                messages.get().send(player, "loading");
            }
            case NOT_FOUND -> {
                inFlight.remove(operationKey);
                messages.get().send(player, "database-error");
            }
            case NEW -> {
                if (isFinalVanillaClaim(record, counted)) {
                    persistFinalVanillaInventory(player, target, record, operationKey);
                } else {
                    persistNewInventory(player, target, record, operationKey);
                }
            }
            default -> throw new IllegalStateException("Unhandled allocation " + allocation.kind());
        }
    }

    private void persistNewInventory(
            Player player, ContainerTarget target, ContainerRecord record, String operationKey) {
        final ItemStack[] generated;
        try {
            generated = generator.generate(record, target, player);
        } catch (RuntimeException exception) {
            database.releasePendingClaim(record.id(), player.getUniqueId());
            fail(player, operationKey, exception);
            return;
        }
        byte[] bytes = ItemStacks.serialize(generated);
        boolean empty = ItemStacks.isEmpty(generated);
        PluginSettings current = settings.get();
        boolean personalCompletion = current.finalClaimAction() == FinalClaimAction.PERSONAL
                && current.completedContainerBecomesNormal();
        boolean completed = empty
                && (current.preventItemInsertion() || personalCompletion);
        database.finalizeClaim(record.id(), player.getUniqueId(), bytes, completed).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                database.releasePendingClaim(record.id(), player.getUniqueId());
            }
            runMain(() -> {
                inFlight.remove(operationKey);
                if (throwable != null || !player.isOnline()) {
                    if (throwable != null) {
                        fail(player, operationKey, throwable);
                    }
                    return;
                }
                lootAdvancements.award(player, record.lootTable());
                if (completed) {
                    if (isPersonalCompletionEnabled()) {
                        openNormalInventory(player, target);
                    } else {
                        messages.get().send(player, "completed");
                    }
                } else {
                    openInventory(player, target, record, generated);
                }
            });
        });
    }

    private ContainerRecord createRecord(ContainerTarget target) {
        String lootTable = target.lootTable() == null ? null : target.lootTable().getKey().toString();
        var primary = target.primary();
        Integer partnerX = target.tiles().size() > 1 ? target.tiles().get(1).getX() : null;
        Integer partnerY = target.tiles().size() > 1 ? target.tiles().get(1).getY() : null;
        Integer partnerZ = target.tiles().size() > 1 ? target.tiles().get(1).getZ() : null;
        return new ContainerRecord(
                target.id(), primary.getWorld().getUID(), primary.getX(), primary.getY(), primary.getZ(),
                partnerX, partnerY, partnerZ, lootTable, null,
                settings.get().maxClaimsFor(lootTable), 0, false);
    }

    private boolean shouldCount(Player player) {
        PluginSettings current = settings.get();
        if (!current.countCreativePlayers() && player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        return !(current.adminBypassCounts() && player.hasPermission("finiteloot.bypass"));
    }

    private boolean shouldReopenCompleted() {
        PluginSettings current = settings.get();
        return !current.preventItemInsertion()
                && (current.finalClaimAction() == FinalClaimAction.VANILLA_CONTAINER
                        || !current.completedContainerBecomesNormal());
    }

    private boolean isPersonalCompletionEnabled() {
        PluginSettings current = settings.get();
        return current.finalClaimAction() == FinalClaimAction.PERSONAL
                && current.completedContainerBecomesNormal();
    }

    private boolean isFinalVanillaClaim(ContainerRecord record, boolean counted) {
        return counted
                && settings.get().finalClaimAction() == FinalClaimAction.VANILLA_CONTAINER
                && record.claimCount() >= record.maxClaims();
    }

    private void openNormalInventory(Player player, ContainerTarget target) {
        containers.prepareNormalInventory(target);
        player.openInventory(target.sourceInventory());
        piglinAnger.reactToOpen(player, target);
    }

    public boolean releaseExhaustedContainer(org.bukkit.block.Block block) {
        if (!settings.get().allowBreakingExhaustedContainers()) {
            return false;
        }
        java.util.UUID id = containers.existingId(block).orElse(null);
        if (id == null || !exhausted.isExhausted(id) || finalizingContainers.contains(id)) {
            return false;
        }
        java.util.Optional<ContainerTarget> resolved = containers.resolve(block, false);
        if (resolved.isEmpty()) {
            return false;
        }
        release(resolved.get(), id);
        return true;
    }

    private void release(ContainerTarget target, java.util.UUID containerId) {
        sessions.closeContainer(containerId);
        containers.releaseToNormal(target);
        exhausted.clear(containerId);
        database.removeContainer(containerId).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to remove exhausted container data " + containerId, throwable);
            }
        });
    }

    private void completeAndOpenNormal(
            Player player,
            ContainerTarget target,
            ContainerRecord record,
            byte[] contents,
            String operationKey) {
        database.saveInventory(record.id(), player.getUniqueId(), contents, true)
                .whenComplete((ignored, throwable) -> runMain(() -> {
                    inFlight.remove(operationKey);
                    if (throwable != null) {
                        fail(player, operationKey, throwable);
                    } else if (player.isOnline()) {
                        openNormalInventory(player, target);
                    }
                }));
    }

    private void persistFinalVanillaInventory(
            Player player, ContainerTarget target, ContainerRecord record, String operationKey) {
        final ItemStack[] generated;
        try {
            generated = generator.generate(record, target, player);
        } catch (RuntimeException exception) {
            database.releasePendingClaim(record.id(), player.getUniqueId());
            fail(player, operationKey, exception);
            return;
        }
        finalizingContainers.add(record.id());
        byte[] bytes = ItemStacks.serialize(generated);
        database.finalizeClaim(record.id(), player.getUniqueId(), bytes, false)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        database.releasePendingClaim(record.id(), player.getUniqueId());
                        finalizingContainers.remove(record.id());
                        runMain(() -> fail(player, operationKey, throwable));
                        return;
                    }
                    runMain(() -> {
                        if (player.isOnline()) {
                            lootAdvancements.award(player, record.lootTable());
                        }
                        transitionToVanilla(player, target, record, generated, operationKey);
                    });
                });
    }

    private void recoverFinalVanilla(
            Player player,
            ContainerTarget target,
            ContainerRecord record,
            ItemStack[] contents,
            String operationKey) {
        database.isLastCountedClaimant(record.id(), player.getUniqueId())
                .whenComplete((lastClaimant, throwable) -> runMain(() -> {
                    if (throwable != null) {
                        fail(player, operationKey, throwable);
                    } else if (lastClaimant) {
                        finalizingContainers.add(record.id());
                        transitionToVanilla(player, target, record, contents, operationKey);
                    } else {
                        inFlight.remove(operationKey);
                        openInventory(player, target, record, contents);
                    }
                }));
    }

    private void transitionToVanilla(
            Player player,
            ContainerTarget target,
            ContainerRecord record,
            ItemStack[] contents,
            String operationKey) {
        sessions.closeContainerAndSave(record.id()).whenComplete((ignored, throwable) -> runMain(() -> {
            if (throwable != null) {
                finalizingContainers.remove(record.id());
                fail(player, operationKey, throwable);
                return;
            }
            containers.restoreVanillaInventory(target, contents);
            inFlight.remove(operationKey);
            finalizingContainers.remove(record.id());
            exhausted.clear(record.id());
            if (player.isOnline()) {
                player.openInventory(target.sourceInventory());
                piglinAnger.reactToOpen(player, target);
                if (settings.get().showFinalClaimMessage()) {
                    messages.get().send(player, "final-claim");
                }
            }
            if (settings.get().clearPersonalInventoriesOnFinalClaim()) {
                database.removeContainer(record.id()).whenComplete((removed, cleanupFailure) -> {
                    if (cleanupFailure != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to clear finalized container data " + record.id(), cleanupFailure);
                    }
                });
            }
        }));
    }

    private void openInventory(
            Player player, ContainerTarget target, ContainerRecord record, ItemStack[] contents) {
        sessions.open(player, record.id(), contents, title(), target);
        piglinAnger.reactToOpen(player, target);
        PluginSettings current = settings.get();
        if (current.showRemainingClaims()) {
            int remaining = Math.max(0, record.maxClaims() - record.claimCount());
            messages.get().send(player, "remaining-claims", java.util.Map.of(
                    "remaining", Integer.toString(remaining),
                    "max", Integer.toString(record.maxClaims())));
        }
    }

    private net.kyori.adventure.text.Component title() {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize("<gold>FiniteLoot</gold> <gray>- Personal Loot</gray>");
    }

    private void fail(Player player, String operationKey, Throwable throwable) {
        inFlight.remove(operationKey);
        plugin.getLogger().log(Level.SEVERE, "Claim operation failed for " + player.getUniqueId(), throwable);
        if (player.isOnline()) {
            messages.get().send(player, "database-error");
        }
    }

    private void runMain(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private record AllocationWithRecord(ContainerRecord record, ClaimAllocation allocation, boolean counted) {
    }
}
