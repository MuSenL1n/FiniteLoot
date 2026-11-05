package vip.naya.finiteloot.admin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.data.ContainerInspection;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.loot.ItemStacks;
import vip.naya.finiteloot.gui.RewardSessionManager;

public final class AdminActions {
    private final JavaPlugin plugin;
    private final Database database;
    private final ContainerManager containers;
    private final ExhaustedContainers exhausted;
    private final java.util.function.Supplier<Messages> messages;
    private final RewardSessionManager sessions;
    private final Map<UUID, PendingAction> pending = new ConcurrentHashMap<>();

    public AdminActions(
            JavaPlugin plugin,
            Database database,
            ContainerManager containers,
            ExhaustedContainers exhausted,
            RewardSessionManager sessions,
            java.util.function.Supplier<Messages> messages) {
        this.plugin = plugin;
        this.database = database;
        this.containers = containers;
        this.exhausted = exhausted;
        this.sessions = sessions;
        this.messages = messages;
    }

    public void arm(Player player, PendingAction action) {
        pending.put(player.getUniqueId(), action);
        messages.get().send(player, "action-armed");
    }

    public boolean handleClick(Player player, org.bukkit.block.Block block) {
        PendingAction action = pending.remove(player.getUniqueId());
        if (action == null) {
            return false;
        }
        boolean manual = action.type() == PendingAction.Type.SET;
        Optional<ContainerTarget> resolved = containers.resolve(block, manual);
        if (resolved.isEmpty()) {
            messages.get().send(player, "not-container");
            return true;
        }
        ContainerTarget target = resolved.get();
        switch (action.type()) {
            case INSPECT -> inspect(player, target);
            case SET -> set(player, target, action.maxClaims());
            case RESET_CONTAINER -> {
                sessions.closeContainer(target.id());
                exhausted.clear(target.id());
                complete(player, database.resetContainer(target.id()), "reset-ok");
            }
            case RESET_PLAYER -> {
                sessions.closePlayerContainer(action.targetPlayer(), target.id());
                exhausted.clear(target.id());
                complete(player, database.resetPlayer(target.id(), action.targetPlayer()), "reset-ok");
            }
            case REMOVE -> remove(player, target);
            default -> throw new IllegalStateException("Unhandled admin action " + action.type());
        }
        return true;
    }

    public void migrate(org.bukkit.command.CommandSender sender) {
        Path backups = plugin.getDataFolder().toPath().resolve("backups");
        database.backupMigrateAndClean(backups).whenComplete((path, throwable) -> runMain(() -> {
            if (throwable != null) {
                fail(sender, throwable);
            } else {
                messages.get().send(sender, "migrate-ok", Map.of("backup", path.toString()));
            }
        }));
    }

    private void inspect(Player player, ContainerTarget target) {
        database.inspect(target.id()).whenComplete((inspection, throwable) -> runMain(() -> {
            if (throwable != null) {
                fail(player, throwable);
                return;
            }
            if (inspection == null) {
                messages.get().send(player, "not-lootable");
                return;
            }
            sendInspection(player, inspection);
        }));
    }

    private void set(Player player, ContainerTarget target, int maxClaims) {
        ItemStack[] snapshot = target.sourceInventory().getContents();
        boolean hasTemplateItems = java.util.Arrays.stream(snapshot)
                .anyMatch(item -> item != null && item.getType() != Material.AIR);
        database.findContainer(target.id()).thenCompose(existing -> {
            ContainerRecord record;
            if (existing != null) {
                record = new ContainerRecord(
                        existing.id(), existing.worldId(), existing.x(), existing.y(), existing.z(),
                        existing.partnerX(), existing.partnerY(), existing.partnerZ(), existing.lootTable(),
                        existing.templateContents(), maxClaims, existing.claimCount(), true);
            } else {
                String table = target.lootTable() == null ? null : target.lootTable().getKey().toString();
                if (table == null && !hasTemplateItems) {
                    return CompletableFuture.failedFuture(
                            new IllegalArgumentException("Container has no loot table or template items"));
                }
                TileState primary = target.primary();
                Integer partnerX = target.tiles().size() > 1 ? target.tiles().get(1).getX() : null;
                Integer partnerY = target.tiles().size() > 1 ? target.tiles().get(1).getY() : null;
                Integer partnerZ = target.tiles().size() > 1 ? target.tiles().get(1).getZ() : null;
                byte[] template = table == null ? ItemStacks.serialize(snapshot) : null;
                record = new ContainerRecord(
                        target.id(), primary.getWorld().getUID(), primary.getX(), primary.getY(), primary.getZ(),
                        partnerX, partnerY, partnerZ, table, template, maxClaims, 0, true);
            }
            return database.upsertContainer(record);
        }).whenComplete((record, throwable) -> runMain(() -> {
            if (throwable != null) {
                if (throwable.getCause() instanceof IllegalArgumentException) {
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
            if (target.lootTable() == null && hasTemplateItems) {
                containers.clearSource(target);
            }
            messages.get().send(player, "set-ok", Map.of(
                    "id", record.id().toString(), "max", Integer.toString(record.maxClaims())));
        }));
    }

    private void remove(Player player, ContainerTarget target) {
        sessions.closeContainer(target.id());
        exhausted.clear(target.id());
        database.removeContainer(target.id()).whenComplete((ignored, throwable) -> runMain(() -> {
            if (throwable != null) {
                fail(player, throwable);
                return;
            }
            containers.removeIdentity(target);
            messages.get().send(player, "remove-ok");
        }));
    }

    private void complete(Player player, CompletableFuture<Void> future, String message) {
        future.whenComplete((ignored, throwable) -> runMain(() -> {
            if (throwable != null) {
                fail(player, throwable);
            } else {
                messages.get().send(player, message);
            }
        }));
    }

    private void sendInspection(Player player, ContainerInspection inspection) {
        ContainerRecord record = inspection.container();
        String claims = inspection.claims().isEmpty() ? "-" : String.join(", ", inspection.claims());
        messages.get().send(player, "inspect", Map.of(
                "id", record.id().toString(),
                "table", record.lootTable() == null ? "manual-template" : record.lootTable(),
                "max", Integer.toString(record.maxClaims()),
                "count", Integer.toString(record.claimCount()),
                "claims", claims));
    }

    private void fail(org.bukkit.command.CommandSender sender, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, "Administrative database operation failed", throwable);
        messages.get().send(sender, "database-error");
    }

    private void runMain(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
