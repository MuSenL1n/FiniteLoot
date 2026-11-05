package vip.naya.finiteloot.listener;

import java.util.Iterator;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import vip.naya.finiteloot.admin.AdminActions;
import vip.naya.finiteloot.claim.ClaimController;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.container.ContainerManager;

public final class WorldListener implements Listener {
    private final ContainerManager containers;
    private final ClaimController claims;
    private final AdminActions adminActions;
    private final java.util.function.Supplier<PluginSettings> settings;

    public WorldListener(
            ContainerManager containers,
            ClaimController claims,
            AdminActions adminActions,
            java.util.function.Supplier<PluginSettings> settings) {
        this.containers = containers;
        this.claims = claims;
        this.adminActions = adminActions;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || !event.getAction().isRightClick()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (adminActions.handleClick(event.getPlayer(), block)) {
            event.setCancelled(true);
            return;
        }
        if (settings.get().isWorldExcluded(block.getWorld().getName())) {
            return;
        }
        containers.resolve(block, false).ifPresent(target -> {
            event.setCancelled(true);
            claims.open(event.getPlayer(), target);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!settings.get().preventBreaking() || !containers.isManagedOrEligible(event.getBlock())) {
            return;
        }
        if (claims.releaseExhaustedContainer(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (settings.get().preventHopperExtraction() && protectedHolder(event.getSource().getHolder())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (settings.get().preventExplosions()) {
            removeProtected(event.blockList().iterator());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (settings.get().preventExplosions()) {
            removeProtected(event.blockList().iterator());
        }
    }

    private void removeProtected(Iterator<Block> iterator) {
        while (iterator.hasNext()) {
            if (containers.isManagedOrEligible(iterator.next())) {
                iterator.remove();
            }
        }
    }

    private boolean protectedHolder(InventoryHolder holder) {
        if (holder instanceof TileState tile) {
            return containers.isManagedOrEligible(tile.getBlock());
        }
        if (holder instanceof DoubleChest doubleChest) {
            return protectedHolder(doubleChest.getLeftSide()) || protectedHolder(doubleChest.getRightSide());
        }
        return false;
    }
}

