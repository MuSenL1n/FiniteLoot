package vip.naya.finiteloot.command;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.data.ContainerInspection;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.gui.RewardSessions;
import vip.naya.finiteloot.loot.ItemStacks;

/**
 * The bodies of the {@code /fl} subcommands.
 *
 * <p>Port of Paper's {@code AdminActions}. The structure survives; four things had to change:
 *
 * <table>
 *   <caption>Porting notes</caption>
 *   <tr><th>Paper</th><th>Here</th><th>Why</th></tr>
 *   <tr><td>{@code Bukkit.getScheduler().runTask}</td><td>{@link #onServerThread}</td>
 *       <td>Database callbacks land on a pool thread; menus and messages must not.</td></tr>
 *   <tr><td>{@code Block} click</td><td>{@link #handleClick} with a position</td>
 *       <td>The interaction layer (P7) resolves the block; the action only needs where it was.</td></tr>
 *   <tr><td>{@code world.getUID()}</td><td>{@code dimension().location()}</td>
 *       <td>R19: the stored uid is a hash of the dimension name, so the name is read from the level.</td></tr>
 *   <tr><td>permission strings</td><td>vanilla OP level 2</td>
 *       <td>D6: no permission plugin assumed. See {@link AdminGate}.</td></tr>
 * </table>
 *
 * <p><b>Why {@code adopt} exists:</b> releasing a container leaves an "ignored" flag so it can never be
 * managed again by accident -- that flag is the anti-duplication guarantee. Without an undo, a single
 * mistaken {@code /fl remove} would be permanent short of digging out the block. {@code adopt} is that undo,
 * and it is only reachable from here.
 */
public final class AdminActions {

    private final MinecraftServer server;
    private final Database database;
    private final ContainerManager containers;
    private final ExhaustedContainers exhausted;
    private final RewardSessions sessions;
    private final Supplier<PluginSettings> settings;
    private final Supplier<Messages> messages;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, PendingAction> pending = new ConcurrentHashMap<>();

    public AdminActions(MinecraftServer server, Database database, ContainerManager containers,
            ExhaustedContainers exhausted, RewardSessions sessions, Supplier<PluginSettings> settings,
            Supplier<Messages> messages, Path dataDirectory, Logger logger) {
        this.server = server;
        this.database = database;
        this.containers = containers;
        this.exhausted = exhausted;
        this.sessions = sessions;
        this.settings = settings;
        this.messages = messages;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    /** Commands that need a target container; the click consumes the action. */
    public void arm(ServerPlayer player, PendingAction action) {
        pending.put(player.getUUID(), action);
        messages.get().send(player, "action-armed");
    }

    public boolean hasPending(ServerPlayer player) {
        return pending.containsKey(player.getUUID());
    }

    public void clearPending(ServerPlayer player) {
        pending.remove(player.getUUID());
    }

    /**
     * Executes the armed action against the container the admin clicked.
     *
     * @return true when an action was consumed, so the caller knows not to also open the container
     */
    public boolean handleClick(ServerPlayer player, ServerLevel level, BlockPos pos) {
        PendingAction action = pending.remove(player.getUUID());
        if (action == null) {
            return false;
        }
        // SET and ADOPT are the two that may touch a container a player could not interact with: setting a
        // limit on a plain chest, and adopting one that was released.
        boolean allowManual = action.type() == PendingAction.Type.SET
                || action.type() == PendingAction.Type.ADOPT;
        Optional<ContainerTarget> resolved = containers.resolve(level, pos, allowManual);
        if (resolved.isEmpty()) {
            messages.get().send(player, "not-container");
            return true;
        }
        ContainerTarget target = resolved.get();
        switch (action.type()) {
            case INSPECT -> inspect(player, level, target);
            case SET -> set(player, target, action.maxClaims());
            case RESET_CONTAINER -> {
                sessions.closeContainer(target.id());
                exhausted.clear(target.id());
                complete(player, database.resetContainer(target.id()), "reset-ok");
            }
            case RESET_PLAYER -> {
                sessions.closeContainer(target.id());
                exhausted.clear(target.id());
                complete(player, database.resetPlayer(target.id(), action.targetPlayer()), "reset-ok");
            }
            case REMOVE -> remove(player, target);
            case ADOPT -> adopt(player, level, pos, target);
        }
        return true;
    }

    /**
     * Hands a released container back to FiniteLoot.
     *
     * <p>Two things have to happen, and the second one is the whole reason this is not a one-liner:
     *
     * <ol>
     *   <li>The ignored flag is cleared, or the container can never be managed again.
     *   <li>If a row for this position still exists, its id is written back onto the block. Without this the
     *       next interaction would mint a fresh id and a second row for the same chest -- two rows, two claim
     *       counters, and the exact duplicate-counting hazard the row-retention question was about. Reusing the
     *       row also means an adopted container that was already at its limit goes straight to the exhausted
     *       path instead of silently granting a fresh set of claims.
     * </ol>
     *
     * <p>When no row exists the id is left empty on purpose: the ordinary takeover path will create one, with
     * the loot table read from the block, which is the correct treatment for a container that was never stored.
     */
    private void adopt(ServerPlayer player, ServerLevel level, BlockPos pos, ContainerTarget target) {
        boolean wasIgnored = target.tiles().stream()
                .anyMatch(vip.naya.finiteloot.container.ContainerMarker::isIgnored);
        BlockPos primary = target.tiles().getFirst().getBlockPos();
        UUID worldId = vip.naya.finiteloot.container.WorldIds.of(level);
        database.findContainerAt(worldId, primary.getX(), primary.getY(), primary.getZ())
                .whenComplete((record, throwable) -> onServerThread(() -> {
                    if (throwable != null) {
                        fail(player, throwable);
                        return;
                    }
                    containers.clearIgnored(target);
                    if (record != null) {
                        // Same id, same counter: the container resumes where it left off.
                        target.tiles().getFirst().setData(
                                vip.naya.finiteloot.container.AttachmentTypes.CONTAINER_ID.get(),
                                record.id().toString());
                        for (BlockEntity tile : target.tiles()) {
                            tile.setChanged();
                        }
                        if (record.claimCount() >= record.maxClaims()) {
                            exhausted.markExhausted(record.id());
                        } else {
                            exhausted.clear(record.id());
                        }
                    }
                    messages.get().send(player, "adopt-ok", Map.of(
                            "id", record == null ? "-" : record.id().toString(),
                            "state", record == null
                                    ? "new"
                                    : (wasIgnored ? "released" : "managed")));
                    logger.info("Adopted container at {} {} {}: {} (row {})",
                            level.dimension().location(), primary, wasIgnored ? "released" : "managed",
                            record == null ? "none, will be identified on first use" : record.id());
                }));
    }

    public void migrate(CommandSourceStack source) {
        Path backups = dataDirectory.resolve("backups");
        database.backupMigrateAndClean(backups).whenComplete((path, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                fail(source, throwable);
            } else {
                send(source, "migrate-ok", Map.of("backup", String.valueOf(path)));
            }
        }));
    }

    private void inspect(ServerPlayer player, ServerLevel level, ContainerTarget target) {
        database.inspect(target.id()).whenComplete((inspection, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                fail(player, throwable);
                return;
            }
            if (inspection == null) {
                messages.get().send(player, "not-lootable");
                return;
            }
            sendInspection(player, level, inspection);
        }));
    }

    private void set(ServerPlayer player, ContainerTarget target, int maxClaims) {
        NonNullList<ItemStack> snapshot = NonNullList.withSize(
                target.sourceInventory().getContainerSize(), ItemStack.EMPTY);
        for (int slot = 0; slot < snapshot.size(); slot++) {
            snapshot.set(slot, target.sourceInventory().getItem(slot));
        }
        boolean hasTemplateItems = snapshot.stream().anyMatch(stack -> !stack.isEmpty());

        database.findContainer(target.id()).thenCompose(existing -> {
            ContainerRecord record;
            if (existing != null) {
                record = new ContainerRecord(
                        existing.id(), existing.worldId(), existing.x(), existing.y(), existing.z(),
                        existing.partnerX(), existing.partnerY(), existing.partnerZ(), existing.lootTable(),
                        existing.templateContents(), maxClaims, existing.claimCount(), true);
            } else {
                String table = lootTableOf(target);
                if (table == null && !hasTemplateItems) {
                    return CompletableFuture.<ContainerRecord>failedFuture(
                            new IllegalArgumentException("Container has no loot table or template items"));
                }
                List<BlockEntity> tiles = target.tiles();
                Integer partnerX = tiles.size() > 1 ? tiles.get(1).getBlockPos().getX() : null;
                Integer partnerY = tiles.size() > 1 ? tiles.get(1).getBlockPos().getY() : null;
                Integer partnerZ = tiles.size() > 1 ? tiles.get(1).getBlockPos().getZ() : null;
                byte[] template = null;
                if (table == null) {
                    try {
                        template = ItemStacks.serialize(
                                snapshot.toArray(new ItemStack[0]), server.registryAccess());
                    } catch (java.io.IOException failure) {
                        // Serialising a template is a prerequisite for a manual container; without it there is
                        // nothing to store, so fail the operation rather than register a container that can
                        // never generate loot.
                        return CompletableFuture.<ContainerRecord>failedFuture(failure);
                    }
                }
                BlockPos primary = target.tiles().getFirst().getBlockPos();
                // R19: the world uid is a hash of the dimension name, so it has to come from the level here.
                record = new ContainerRecord(
                        target.id(), vip.naya.finiteloot.container.WorldIds.of(levelOf(target)),
                        primary.getX(), primary.getY(), primary.getZ(),
                        partnerX, partnerY, partnerZ, table, template, maxClaims, 0, true);
            }
            return database.upsertContainer(record);
        }).whenComplete((record, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                if (cause instanceof IllegalArgumentException) {
                    messages.get().send(player, "not-lootable");
                } else {
                    fail(player, throwable);
                }
                return;
            }
            if (record.claimCount() >= record.maxClaims()) {
                exhausted.markExhausted(record.id());
            } else {
                exhausted.clear(record.id());
            }
            if (lootTableOf(target) == null && hasTemplateItems) {
                containers.clearSource(target);
            }
            messages.get().send(player, "set-ok", Map.of(
                    "id", record.id().toString(), "max", Integer.toString(record.maxClaims())));
        }));
    }

    private void remove(ServerPlayer player, ContainerTarget target) {
        sessions.closeContainer(target.id());
        exhausted.clear(target.id());
        database.removeContainer(target.id()).whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                fail(player, throwable);
                return;
            }
            containers.removeIdentity(target);
            logger.info("Released container {}: ignored flag written to {} tile(s)",
                    target.id(), target.tiles().size());
            messages.get().send(player, "remove-ok");
        }));
    }

    private void complete(ServerPlayer player, CompletableFuture<Void> future, String message) {
        future.whenComplete((ignored, throwable) -> onServerThread(() -> {
            if (throwable != null) {
                fail(player, throwable);
            } else {
                messages.get().send(player, message);
            }
        }));
    }

    /**
     * Formats {@code /fl inspect}. Differs from Paper in one field: the dimension is shown by name (R19),
     * resolved from the level the admin clicked in rather than from the stored {@code world_uid} hash, which
     * cannot be reversed.
     */
    private void sendInspection(ServerPlayer player, ServerLevel level, ContainerInspection inspection) {
        ContainerRecord record = inspection.container();
        String claims = inspection.claims().isEmpty() ? "-" : String.join(", ", inspection.claims());
        messages.get().send(player, "inspect", Map.of(
                "id", record.id().toString(),
                "table", record.lootTable() == null ? "manual-template" : record.lootTable(),
                "max", Integer.toString(record.maxClaims()),
                "count", Integer.toString(record.claimCount()),
                "claims", claims,
                "dimension", level.dimension().location().toString(),
                "pos", record.x() + " " + record.y() + " " + record.z()));
    }

    private ServerLevel levelOf(ContainerTarget target) {
        return (ServerLevel) target.tiles().getFirst().getLevel();
    }

    private static String lootTableOf(ContainerTarget target) {
        // The target already carries the table the takeover resolved, including the double-chest case where only
        // one half reports it. Preferring it over a fresh read of the tiles means /fl set stores exactly what the
        // claim path would have used.
        if (target.lootTable() != null) {
            return target.lootTable().location().toString();
        }
        for (BlockEntity tile : target.tiles()) {
            if (tile instanceof RandomizableContainerBlockEntity lootable && lootable.getLootTable() != null) {
                return lootable.getLootTable().location().toString();
            }
        }
        return null;
    }

    private void fail(net.minecraft.commands.CommandSourceStack source, Throwable throwable) {
        logger.error("Administrative database operation failed", throwable);
        send(source, "database-error", Map.of());
    }

    private void fail(ServerPlayer player, Throwable throwable) {
        logger.error("Administrative database operation failed", throwable);
        messages.get().send(player, "database-error");
    }

    private void send(CommandSourceStack source, String key, Map<String, String> replacements) {
        Component message = messages.get().prefixed(key, replacements);
        source.sendSuccess(() -> message, false);
    }

    private void onServerThread(Runnable runnable) {
        if (server.isSameThread()) {
            runnable.run();
        } else {
            server.execute(runnable);
        }
    }
}
