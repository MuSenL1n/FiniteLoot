package vip.naya.finiteloot.gui;

import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The personal-loot inventory: a plain in-memory {@link Container}, exactly what Paper's
 * {@code RewardInventoryHolder} wrapped.
 *
 * <p>Keeping it a real {@code Container} rather than a menu-embedded list means the vanilla menu code can
 * treat it like any chest. That is the whole reason a client with no mod can render it: from the network's
 * point of view this is a chest, because it is one.
 *
 * <p><b>{@link #stillValid} always returns true</b>, and that is deliberate. The backing container is a
 * database row, not a block, so there is no block to walk away from. Paper's version had the opposite
 * problem -- the inventory outlived the block -- so the rule here is explicit: the menu stays valid until
 * it is closed, and closure is what forces the save.
 *
 * <p>{@link #setChanged} is the single place that knows a mutation happened. It does not write to the
 * database: the session layer decides when to persist, because a save per slot change would be one query
 * per item moved. Paper deferred it by one tick; here the menu close and the periodic flush own it.
 */
public final class RewardInventory implements Container {

    private final UUID containerId;
    private final UUID playerId;
    private final NonNullList<ItemStack> items;
    private final Runnable onChanged;

    public RewardInventory(UUID containerId, UUID playerId, int size, Runnable onChanged) {
        this.containerId = containerId;
        this.playerId = playerId;
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
        this.onChanged = onChanged;
    }

    public UUID containerId() {
        return containerId;
    }

    public UUID playerId() {
        return playerId;
    }

    /**
     * Copies contents in, padding or truncating to this inventory's size.
     *
     * <p>The size is chosen by {@link RewardSessions#normalizedSize(int)} from the stored contents, so a
     * mismatch only happens if the stored array came from a different container size. Truncating is the
     * safe direction: a lost item is a bug report, a duplicated one is an exploit.
     */
    public void fill(ItemStack[] contents) {
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = slot < contents.length ? contents[slot] : ItemStack.EMPTY;
            items.set(slot, stack == null ? ItemStack.EMPTY : stack);
        }
    }

    /** Snapshot for serialisation; the caller must not rely on it staying in sync. */
    public ItemStack[] copyContents() {
        ItemStack[] contents = new ItemStack[items.size()];
        for (int slot = 0; slot < items.size(); slot++) {
            contents[slot] = items.get(slot);
        }
        return contents;
    }

    @Override
    public int getContainerSize() {
        return items.size();
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
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = net.minecraft.world.ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return net.minecraft.world.ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) {
            return;
        }
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        onChanged.run();
    }

    @Override
    public boolean stillValid(Player player) {
        // See the class comment: validity is bounded by the session, not by a block position.
        return true;
    }

    @Override
    public void clearContent() {
        // Must not shrink the list: getContainerSize() is derived from it, and a cleared-but-still-open
        // inventory has to keep the same slot count or the menu and the client disagree about its shape.
        items.replaceAll(ignored -> ItemStack.EMPTY);
        setChanged();
    }
}
