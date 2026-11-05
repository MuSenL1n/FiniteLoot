package vip.naya.finiteloot.container;

import java.util.List;
import java.util.UUID;
import org.bukkit.block.TileState;
import org.bukkit.inventory.Inventory;
import org.bukkit.loot.LootTable;

public record ContainerTarget(
        UUID id,
        List<TileState> tiles,
        Inventory sourceInventory,
        LootTable lootTable,
        boolean newlyIdentified) {

    public TileState primary() {
        return tiles.getFirst();
    }
}

