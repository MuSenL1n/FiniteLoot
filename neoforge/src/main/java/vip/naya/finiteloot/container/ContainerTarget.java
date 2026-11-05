package vip.naya.finiteloot.container;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * One loot container under FiniteLoot management.
 *
 * <p>Mapped from the Paper record with the types swapped one for one:
 *
 * <table>
 *   <caption>Type mapping</caption>
 *   <tr><th>Paper</th><th>NeoForge 1.21.1</th></tr>
 *   <tr><td>{@code List<TileState> tiles}</td><td>{@code List<BlockEntity>} -- two entries for a double chest</td></tr>
 *   <tr><td>{@code Inventory sourceInventory}</td><td>{@code Container} -- a {@code CompoundContainer} for a double chest</td></tr>
 *   <tr><td>{@code LootTable lootTable}</td><td>{@code ResourceKey<LootTable>} -- the key, not a resolved table</td></tr>
 * </table>
 *
 * <p>{@code lootTable} being a key rather than a table is not a detail: {@code maxClaimsFor(...)} in
 * {@code PluginSettings} keys off exactly this string, so the type was chosen to make that call direct.
 */
public record ContainerTarget(
        UUID id,
        List<BlockEntity> tiles,
        Container sourceInventory,
        ResourceKey<LootTable> lootTable,
        boolean newlyIdentified) {

    public BlockEntity primary() {
        return tiles.getFirst();
    }

    /** Contents as a fixed-size list, i.e. what Bukkit's {@code Inventory#getContents()} returned. */
    public NonNullList<ItemStack> contents() {
        NonNullList<ItemStack> items = NonNullList.withSize(sourceInventory.getContainerSize(), ItemStack.EMPTY);
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, sourceInventory.getItem(slot));
        }
        return items;
    }
}
