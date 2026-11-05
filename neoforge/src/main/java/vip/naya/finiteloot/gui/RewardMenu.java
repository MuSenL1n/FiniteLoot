package vip.naya.finiteloot.gui;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The personal-loot menu: a chest menu whose top slots can be made extraction-only.
 *
 * <p>Paper enforced this with a cancelled {@code InventoryClickEvent}. That is not available here, and
 * reimplementing {@code AbstractContainerMenu#clicked} to reject insertions would mean reproducing
 * vanilla's pickup/place/swap/drag matrix and getting it subtly wrong. Instead the restriction is pushed
 * into the two places vanilla itself consults:
 *
 * <ul>
 *   <li>{@link Slot#mayPlace} -- covers placing from the cursor, swapping, and dragging. Vanilla checks it
 *       on every path that puts something into a slot.
 *   <li>{@code moveItemStackTo}'s merge loop does <b>not</b> check {@code mayPlace}, so a shift-click can
 *       still merge into an existing stack of the same item. That hole is closed by
 *       {@link #getMaxStackSize} returning zero, which makes the merge arithmetic fail, and by
 *       {@link #quickMoveStack} refusing to route anything from the player inventory into the top.
 * </ul>
 *
 * <p>The second point is the one worth remembering: {@code mayPlace} looks like the whole answer and is
 * not. A chest holding diamonds plus a player holding diamonds would leak items into the reward inventory.
 */
public final class RewardMenu extends ChestMenu {

    private final RewardInventory rewards;
    private final int topSize;
    private final boolean insertionBlocked;
    private final Runnable onClosed;

    public RewardMenu(int containerId, Inventory playerInventory, RewardInventory rewards, int rows,
            boolean insertionBlocked, Runnable onClosed) {
        super(menuTypeFor(rows), containerId, playerInventory, rewards, rows);
        this.rewards = rewards;
        this.topSize = rows * 9;
        this.insertionBlocked = insertionBlocked;
        this.onClosed = onClosed;
        if (insertionBlocked) {
            // Replace the slots the superclass just added. ChestMenu builds plain Slots in its constructor,
            // so there is no hook to supply our own; the slot list is a mutable NonNullList, and every
            // other field of the original Slot (container, index, coordinates) is carried over unchanged.
            for (int index = 0; index < topSize; index++) {
                Slot original = this.slots.get(index);
                this.slots.set(index, new ExtractionOnlySlot(
                        rewards, original.getContainerSlot(), original.x, original.y));
            }
        }
    }

    public RewardInventory rewards() {
        return rewards;
    }

    @Override
    public boolean stillValid(Player player) {
        // The session, not a block position, decides validity (see RewardInventory#stillValid).
        return true;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (insertionBlocked && clickType == ClickType.SWAP && slotId >= 0 && slotId < topSize) {
            // Vanilla's number-key swap does not consult mayPlace: when the cursor is empty it takes the
            // hotbar stack and writes it straight into the slot. Measured, not assumed -- the first version
            // of RewardMenu relied on mayPlace alone and this path replaced a reward stack with a hotbar
            // item, with the slot count unchanged so counting could not even detect it.
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (insertionBlocked) {
            Slot slot = this.slots.get(index);
            boolean fromTop = index < topSize;
            if (!fromTop) {
                // Shift-clicking from the player inventory would move items into the reward inventory.
                // Returning EMPTY is what vanilla does when a move is not possible, so the client simply
                // sees the item stay put.
                return ItemStack.EMPTY;
            }
            return super.quickMoveStack(player, index);
        }
        return super.quickMoveStack(player, index);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Fires exactly once per menu, including on disconnect, which is why the save hook lives here
        // rather than in an event listener that could fire twice.
        onClosed.run();
    }

    private static MenuType<?> menuTypeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Unsupported reward inventory rows: " + rows);
        };
    }

    /** A top slot that can be emptied but not filled. */
    private static final class ExtractionOnlySlot extends Slot {

        ExtractionOnlySlot(RewardInventory rewards, int index, int x, int y) {
            super(rewards, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public int getMaxStackSize() {
            return 0;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            // Zero is what stops moveItemStackTo from merging into an existing stack: it computes
            // `count + incoming <= max` and that can never hold.
            return 0;
        }
    }
}
