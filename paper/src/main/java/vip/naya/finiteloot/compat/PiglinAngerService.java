package vip.naya.finiteloot.compat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Tag;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.container.ContainerTarget;

public final class PiglinAngerService implements Listener {
    private static final double REACTION_RADIUS = 16.0;
    private static final int MIN_ANGER_TICKS = 20 * 20;
    private static final int MAX_ANGER_TICKS = 39 * 20;
    private final JavaPlugin plugin;
    private final java.util.function.Supplier<PluginSettings> settings;
    private final Map<UUID, AngerLease> leases = new HashMap<>();

    public PiglinAngerService(
            JavaPlugin plugin, java.util.function.Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void reactToOpen(Player opener, ContainerTarget target) {
        if (!settings.get().triggerPiglinAnger()
                || !isTargetable(opener)
                || !Tag.GUARDED_BY_PIGLINS.isTagged(target.primary().getType())) {
            return;
        }
        for (Piglin piglin : opener.getWorld().getNearbyEntitiesByType(
                Piglin.class, opener.getLocation(), REACTION_RADIUS)) {
            if (!canReact(piglin, opener)) {
                continue;
            }
            Player angerTarget = selectAngerTarget(piglin, opener);
            if (angerTarget != null) {
                establishAnger(piglin, angerTarget);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Piglin piglin) {
                clearManagedAnger(piglin);
            }
        }
    }

    public void clearAll() {
        for (UUID piglinId : java.util.List.copyOf(leases.keySet())) {
            Entity entity = Bukkit.getEntity(piglinId);
            if (entity instanceof Piglin piglin) {
                clearManagedAnger(piglin);
            } else {
                AngerLease lease = leases.remove(piglinId);
                if (lease != null) {
                    lease.expiryTask().cancel();
                }
            }
        }
    }

    private boolean canReact(Piglin piglin, Player opener) {
        return piglin.isAdult()
                && piglin.hasAI()
                && piglin.isAware()
                && piglin.getTarget() == null
                && piglin.getMemory(MemoryKey.ANGRY_AT) == null
                && !Boolean.TRUE.equals(piglin.getMemory(MemoryKey.ADMIRING_ITEM))
                && !Boolean.TRUE.equals(piglin.getMemory(MemoryKey.IS_PANICKING))
                && !leases.containsKey(piglin.getUniqueId())
                && piglin.hasLineOfSight(opener);
    }

    private Player selectAngerTarget(Piglin piglin, Player opener) {
        Boolean universalAnger = opener.getWorld().getGameRuleValue(GameRules.UNIVERSAL_ANGER);
        if (!Boolean.TRUE.equals(universalAnger)) {
            return opener;
        }
        return opener.getWorld().getNearbyPlayers(
                        piglin.getLocation(), REACTION_RADIUS,
                        player -> isTargetable(player) && piglin.hasLineOfSight(player))
                .stream()
                .min(Comparator.comparingDouble(player ->
                        player.getLocation().distanceSquared(piglin.getLocation())))
                .orElse(null);
    }

    private void establishAnger(Piglin piglin, Player target) {
        piglin.setTarget(target);
        LivingEntity selected = piglin.getTarget();
        if (selected == null || !selected.getUniqueId().equals(target.getUniqueId())) {
            return;
        }
        UUID piglinId = piglin.getUniqueId();
        UUID targetId = target.getUniqueId();
        piglin.setMemory(MemoryKey.ANGRY_AT, targetId);
        long duration = ThreadLocalRandom.current().nextLong(MIN_ANGER_TICKS, MAX_ANGER_TICKS + 1L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin, () -> expire(piglinId, targetId), duration);
        leases.put(piglinId, new AngerLease(targetId, task));
    }

    private void expire(UUID piglinId, UUID targetId) {
        AngerLease lease = leases.get(piglinId);
        if (lease == null || !lease.targetId().equals(targetId)) {
            return;
        }
        Entity entity = Bukkit.getEntity(piglinId);
        if (entity instanceof Piglin piglin) {
            clearManagedAnger(piglin);
        } else {
            leases.remove(piglinId);
        }
    }

    private void clearManagedAnger(Piglin piglin) {
        AngerLease lease = leases.remove(piglin.getUniqueId());
        if (lease == null) {
            return;
        }
        lease.expiryTask().cancel();
        UUID remembered = piglin.getMemory(MemoryKey.ANGRY_AT);
        if (!lease.targetId().equals(remembered)) {
            return;
        }
        piglin.setMemory(MemoryKey.ANGRY_AT, null);
        LivingEntity currentTarget = piglin.getTarget();
        if (currentTarget != null && lease.targetId().equals(currentTarget.getUniqueId())) {
            piglin.setTarget(null);
        }
    }

    private boolean isTargetable(Player player) {
        return player.isOnline()
                && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL
                        || player.getGameMode() == GameMode.ADVENTURE);
    }

    private record AngerLease(UUID targetId, BukkitTask expiryTask) {
    }
}
