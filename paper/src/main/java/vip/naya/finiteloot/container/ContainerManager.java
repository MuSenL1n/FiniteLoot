package vip.naya.finiteloot.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
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
    private final Logger logger;

    public ContainerManager(JavaPlugin plugin) {
        containerIdKey = new NamespacedKey(plugin, "container_id");
        ignoredKey = new NamespacedKey(plugin, "ignored");
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
        return !isIgnored(tile) && (readId(tile) != null || lootable.getLootTable() != null);
    }

    public Optional<UUID> existingId(Block block) {
        return block.getState() instanceof TileState tile
                ? Optional.ofNullable(readId(tile))
                : Optional.empty();
    }

    public void removeIdentity(ContainerTarget target) {
        for (TileState tile : target.tiles()) {
            tile.getPersistentDataContainer().remove(containerIdKey);
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
        if (id == null && table == null && !allowManual) {
            return Optional.empty();
        }
        boolean newlyIdentified = id == null;
        id = ContainerIdentity.normalize(existingIds, UUID::randomUUID);
        if (writeIdentity) {
            writeId(tiles, id);
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

    private void writeId(List<TileState> tiles, UUID id) {
        for (TileState tile : tiles) {
            tile.getPersistentDataContainer().remove(ignoredKey);
            tile.getPersistentDataContainer().set(containerIdKey, PersistentDataType.STRING, id.toString());
            tile.update(true, false);
        }
    }

    private boolean isIgnored(TileState tile) {
        return tile.getPersistentDataContainer().has(ignoredKey, PersistentDataType.BYTE);
    }
}
