package vip.naya.finiteloot.p0.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * P0-2: a purely in-memory {@link Container} standing in for a player's personal loot.
 *
 * <p>This is the modded equivalent of the Paper plugin's {@code RewardInventoryHolder}. The crucial
 * question P0-2 answers is whether a vanilla {@code MenuType} plus a hand-written {@code Container}
 * is enough to give a <em>vanilla</em> client a titled chest screen, with no client mod at all.
 *
 * <p>Design notes that matter for the real port:
 * <ul>
 *   <li>{@link #stillValid} must return {@code true}. A virtual container is not backed by a world
 *       block, so the usual distance check would close the screen immediately.
 *   <li>{@link #setChanged} is the save hook -- the real port debounces a database write here.
 *   <li>{@link #getMaxStackSize()} defers to the stack itself, matching {@code Container}'s contract.
 *   <li>Items are returned as-is rather than copied; the menu layer is responsible for copying,
 *       which is why the real port will use {@code StackCopySlot} (see MIGRATION-PLAN R8).
 * </ul>
 */
public class ProbeContainer implements Container {
    private final ItemStack[] items;
    private int changeCount;

    public ProbeContainer(int size) {
        this.items = new ItemStack[size];
        java.util.Arrays.fill(this.items, ItemStack.EMPTY);
    }

    @Override
    public int getContainerSize() {
        return items.length;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        return items[slot];
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        ItemStack existing = items[slot];
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack split = existing.split(amount);
        if (existing.isEmpty()) {
            items[slot] = ItemStack.EMPTY;
        }
        setChanged();
        return split;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        ItemStack existing = items[slot];
        items[slot] = ItemStack.EMPTY;
        return existing;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items[slot] = stack;
        setChanged();
    }

    @Override
    public void setChanged() {
        changeCount++;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        // Virtual container: never invalidate based on position.
        return true;
    }

    @Override
    public void clearContent() {
        java.util.Arrays.fill(items, ItemStack.EMPTY);
        setChanged();
    }

    /** Number of mutations observed; P0-2 uses this to prove the server container is authoritative. */
    public int changeCount() {
        return changeCount;
    }
}
