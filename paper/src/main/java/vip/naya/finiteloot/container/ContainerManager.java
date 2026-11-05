package vip.naya.finiteloot.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ContainerManager {
    private static final Comparator<TileState> TILE_ORDER = Comparator
            .comparingInt((TileState state) -> state.getX())
            .thenComparingInt(TileState::getY)
            .thenComparingInt(TileState::getZ);
    private final NamespacedKey containerIdKey;
    private final NamespacedKey ignoredKey;
    private final NamespacedKey lootTableKey;
    private final Logger logger;

    public ContainerManager(JavaPlugin plugin) {
        containerIdKey = new NamespacedKey(plugin, "container_id");
        ignoredKey = new NamespacedKey(plugin, "ignored");
        lootTableKey = new NamespacedKey(plugin, "loot_table");
        logger = plugin.getLogger();
    }

    public Optional<ContainerTarget> resolve(Block block, boolean allowManual) {
        return resolveState(block.getState(), allowManual, true);
    }

    public boolean isManagedOrEligible(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tile) || !(state instanceof Lootable lootable)
                || !(state instanceof InventoryHolder)) {
            return false;
        }
        if (isIgnored(tile)) {
            return false;
        }
        // The live loot table is not a durable answer: any read of the container can settle it, and after
        // that it is gone. So the question is "does it have an id, or a table we recorded, or a table we
        // can still see". This stays read-only; recording happens where the tile is live.
        return readId(tile) != null || readLootTableKey(tile) != null || lootable.getLootTable() != null;
    }

    /**
     * A hopper pulls from a container by reading it, and reading is what settles the loot. By the time
     * InventoryMoveItemEvent reaches a listener the table is already spent, so a listener cannot be the
     * first place that learns about it. Chunk load is the last moment the table is guaranteed to still
     * be there, and it is also the moment the container becomes reachable by automation.
     *
     * @return the number of containers recorded
     */
    public int rememberLootTables(Chunk chunk) {
        int remembered = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof TileState tile) || !(state instanceof Lootable lootable) || isIgnored(tile)) {
                continue;
            }
            if (readId(tile) != null || readLootTableKey(tile) != null) {
                continue;
            }
            LootTable table = lootable.getLootTable();
            if (table == null) {
                continue;
            }
            tile.getPersistentDataContainer().set(lootTableKey, PersistentDataType.STRING, table.getKey().toString());
            tile.update(true, false);
            remembered++;
        }
        return remembered;
    }

    public Optional<UUID> existingId(Block block) {
        return block.getState() instanceof TileState tile
                ? Optional.ofNullable(readId(tile))
                : Optional.empty();
    }

    public void removeIdentity(ContainerTarget target) {
        for (TileState tile : target.tiles()) {
            tile.getPersistentDataContainer().remove(containerIdKey);
            // Forget the recorded table too. Left behind, it would keep the container "eligible" after an
            // admin removed it, and it could resurrect a table vanilla has already spent.
            tile.getPersistentDataContainer().remove(lootTableKey);
            tile.getPersistentDataContainer().set(ignoredKey, PersistentDataType.BYTE, (byte) 1);
            tile.update(true, false);
        }
    }

    public void clearSource(ContainerTarget target) {
        target.sourceInventory().clear();
        for (TileState tile : target.tiles()) {
            if (tile instanceof Lootable lootable) {
                lootable.setLootTable(null);
                tile.update(true, false);
            }
        }
    }

    public void prepareNormalInventory(ContainerTarget target) {
        boolean hasOriginalLootTable = target.tiles().stream()
                .filter(Lootable.class::isInstance)
                .map(Lootable.class::cast)
                .anyMatch(lootable -> lootable.getLootTable() != null);
        if (hasOriginalLootTable) {
            clearSource(target);
        }
    }

    public void releaseToNormal(ContainerTarget target) {
        prepareNormalInventory(target);
        removeIdentity(target);
    }

    public void restoreVanillaInventory(ContainerTarget target, ItemStack[] contents) {
        target.sourceInventory().clear();
        ItemStack[] fitted = new ItemStack[target.sourceInventory().getSize()];
        System.arraycopy(contents, 0, fitted, 0, Math.min(contents.length, fitted.length));
        target.sourceInventory().setContents(fitted);
        for (TileState tile : target.tiles()) {
            if (tile instanceof Lootable lootable) {
                lootable.setLootTable(null);
                tile.update(true, false);
            }
        }
        removeIdentity(target);
    }

    public NamespacedKey idKey() {
        return containerIdKey;
    }

    private Optional<ContainerTarget> resolveState(BlockState state, boolean allowManual, boolean writeIdentity) {
        if (!(state instanceof TileState tile) || !(state instanceof Lootable lootable)
                || !(state instanceof InventoryHolder holder)) {
            return Optional.empty();
        }
        List<TileState> tiles = pairedTiles(tile, holder.getInventory());
        if (!allowManual && tiles.stream().anyMatch(this::isIgnored)) {
            return Optional.empty();
        }
        LootTable table = lootable.getLootTable();
        UUID id = null;
        List<UUID> existingIds = new ArrayList<>();
        for (TileState candidate : tiles) {
            UUID candidateId = readId(candidate);
            if (candidateId != null) {
                existingIds.add(candidateId);
                if (id != null && !id.equals(candidateId)) {
                    logger.warning("Merging mismatched FiniteLoot IDs on a double container: " + id + " / " + candidateId);
                } else if (id == null) {
                    id = candidateId;
                }
            }
            if (table == null && candidate instanceof Lootable candidateLootable) {
                table = candidateLootable.getLootTable();
            }
        }
        String rememberedKey = rememberedTableKey(tiles);
        if (table == null && rememberedKey != null) {
            table = resolveTable(rememberedKey);
        }
        // A remembered table keeps the container manageable even when the live table is already spent,
        // otherwise a container can be protected yet unreachable for /fl remove.
        if (id == null && table == null && rememberedKey == null && !allowManual) {
            return Optional.empty();
        }
        boolean newlyIdentified = id == null;
        id = ContainerIdentity.normalize(existingIds, UUID::randomUUID);
        if (writeIdentity) {
            writeId(tiles, id, table);
        }
        return Optional.of(new ContainerTarget(id, List.copyOf(tiles), holder.getInventory(), table, newlyIdentified));
    }

    private List<TileState> pairedTiles(TileState tile, Inventory inventory) {
        List<TileState> result = new ArrayList<>();
        if (tile instanceof Chest && inventory instanceof DoubleChestInventory doubleInventory) {
            addHolder(result, doubleInventory.getLeftSide().getHolder());
            addHolder(result, doubleInventory.getRightSide().getHolder());
        }
        if (result.isEmpty()) {
            result.add(tile);
        }
        result.sort(TILE_ORDER);
        return result;
    }

    private void addHolder(List<TileState> result, InventoryHolder holder) {
        if (holder instanceof TileState tile && !result.contains(tile)) {
            result.add(tile);
        }
    }

    private UUID readId(TileState tile) {
        String value = tile.getPersistentDataContainer().get(containerIdKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            logger.warning("Ignoring invalid FiniteLoot container ID at " + tile.getLocation() + ": " + value);
            return null;
        }
    }

    private void writeId(List<TileState> tiles, UUID id, LootTable table) {
        String tableId = table == null ? null : table.getKey().toString();
        for (TileState tile : tiles) {
            tile.getPersistentDataContainer().remove(ignoredKey);
            tile.getPersistentDataContainer().set(containerIdKey, PersistentDataType.STRING, id.toString());
            if (tableId != null) {
                tile.getPersistentDataContainer().set(lootTableKey, PersistentDataType.STRING, tableId);
            }
            tile.update(true, false);
        }
    }

    private String rememberedTableKey(List<TileState> tiles) {
        for (TileState candidate : tiles) {
            String key = readLootTableKey(candidate);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private String readLootTableKey(TileState tile) {
        return tile.getPersistentDataContainer().get(lootTableKey, PersistentDataType.STRING);
    }

    private LootTable resolveTable(String key) {
        NamespacedKey tableKey = NamespacedKey.fromString(key);
        if (tableKey == null) {
            logger.warning("Ignoring invalid FiniteLoot loot table key: " + key);
            return null;
        }
        return Bukkit.getLootTable(tableKey);
    }

    private boolean isIgnored(TileState tile) {
        return tile.getPersistentDataContainer().has(ignoredKey, PersistentDataType.BYTE);
    }
}
