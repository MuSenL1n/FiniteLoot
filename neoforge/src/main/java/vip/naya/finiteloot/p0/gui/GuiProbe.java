package vip.naya.finiteloot.p0.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P0-2: open a titled "chest" screen backed by a self-implemented {@link ProbeContainer}, using only
 * a <em>vanilla</em> {@link MenuType}.
 *
 * <p>This is the load-bearing assumption of the whole port. The Paper plugin calls
 * {@code Bukkit.createInventory(holder, size, title)} + {@code player.openInventory(...)}. If we can
 * reproduce that with a vanilla menu type, then a client with no FiniteLoot mod still sees a normal,
 * correctly titled chest -- which is what makes the "server-side only" requirement achievable.
 *
 * <p>The counterfactual is registering a custom {@code MenuType}: that ships a menu id the vanilla
 * client cannot resolve, so it would disconnect or render garbage. P0-2 therefore deliberately uses
 * only {@code MenuType.GENERIC_9xN}.
 */
public final class GuiProbe {

    private GuiProbe() {
    }

    /**
     * Opens a probe container for {@code player} and logs everything needed to judge the approach.
     *
     * @param rows 1..6, matching the vanilla {@code GENERIC_9xN} family
     */
    public static void open(ServerPlayer player, int rows) {
        int clamped = Math.max(1, Math.min(6, rows));
        ProbeContainer container = new ProbeContainer(clamped * 9);

        // Put a marker in the first slot: a vanilla client must actually SEE this, which proves the
        // server container is what is rendered (not a stale or client-predicted view).
        container.setItem(0, new ItemStack(Items.DIAMOND, 3));
        container.setItem(clamped * 9 - 1, new ItemStack(Items.EMERALD, 7));

        MenuType<ChestMenu> type = menuTypeFor(clamped);

        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, opener) ->
                        new ProbeChestMenu(type, containerId, playerInventory, container, clamped),
                Component.literal("FiniteLoot - Personal Loot")));

        ProbeMod.LOG.info(
                "[P0-2] opened probe container: rows={} size={} menuType={} title=\"FiniteLoot - Personal Loot\"",
                clamped, clamped * 9, type);
    }

    /** Maps a row count to the vanilla menu type the client already knows how to render. */
    private static MenuType<ChestMenu> menuTypeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /**
     * A {@link ChestMenu} that reports its own lifecycle.
     *
     * <p>{@link #removed(Player)} is the exact hook the real port needs to flush a player's personal
     * loot to the database, so P0-2 verifies it fires and that mutations were observed by the
     * <em>server</em> container before it does.
     */
    static final class ProbeChestMenu extends ChestMenu {
        private final ProbeContainer probeContainer;

        ProbeChestMenu(MenuType<?> type, int containerId, Inventory playerInventory,
                       ProbeContainer container, int rows) {
            super(type, containerId, playerInventory, container, rows);
            this.probeContainer = container;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            ProbeMod.LOG.info(
                    "[P0-2] menu removed for {} -> server container observed {} mutation(s). "
                            + "Server-authoritative container confirmed if this is > 2.",
                    player.getName().getString(), probeContainer.changeCount());
        }
    }
}
