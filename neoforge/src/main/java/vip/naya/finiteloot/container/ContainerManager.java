package vip.naya.finiteloot.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Identifies loot containers, tags them, and lets them go again.
 *
 * <p>Ported from the Paper manager. The logic is unchanged on purpose; what changed is how each Bukkit
 * concept is expressed in NeoForge 1.21.1. Three mappings are worth knowing before reading the code:
 *
 * <ol>
 *   <li><b>Managed marker.</b> Paper used {@code PersistentDataContainer}; here it is a block-entity
 *       data attachment. Same lifetime -- it lives in the block entity's NBT and dies with the block.
 *   <li><b>Double chest pairing.</b> Paper asked Bukkit for a {@code DoubleChestInventory} and read
 *       {@code getLeftSide()}/{@code getRightSide()}. Vanilla does not hand you that from a block, so the
 *       partner is found the way vanilla itself finds it:
 *       {@code ChestBlock.getConnectedDirection(state)} returns the direction <em>towards</em> the other
 *       half (left half looks clockwise, right half counter-clockwise), and
 *       {@code ChestBlock.getContainer(...)} returns the combined {@code CompoundContainer}.
 *   <li><b>Loot table.</b> Paper's {@code Lootable#getLootTable()} returned a {@code LootTable} object;
 *       the mod equivalent returns a {@code ResourceKey<LootTable>}. The key is what we actually want,
 *       because claim limits are keyed by the table's name.
 *   </ol>
 *
 * <p>The "is this a FiniteLoot container" test is deliberately <em>not</em> a list of known blocks. Any
 * block entity that is both randomisable and currently carrying a loot table qualifies, which is what
 * makes this work for containers added by other mods without naming them.
 */
public final class ContainerManager {

    /**
     * Deterministic ordering matters: a double chest must resolve to the same primary tile every time,
     * otherwise the id normalised from the pair could disagree between the two halves on a later load.
     */
    private static final Comparator<BlockEntity> TILE_ORDER = Comparator
            .comparingInt((BlockEntity tile) -> tile.getBlockPos().getX())
            .thenComparingInt(tile -> tile.getBlockPos().getY())
            .thenComparingInt(tile -> tile.getBlockPos().getZ());

    private final Logger logger;
    private final Predicate<String> excludedDimension;

    /**
     * @param excludedDimension decides whether a dimension name is excluded from automatic handling;
     *     receives both the full key ({@code minecraft:overworld}) and the bare path ({@code overworld})
     */
    public ContainerManager(Logger logger, Predicate<String> excludedDimension) {
        this.logger = logger;
        this.excludedDimension = excludedDimension;
    }

    /** Resolve as if a player interacted with the block: an ignored container resolves to nothing. */
    public Optional<ContainerTarget> resolve(Level level, BlockPos pos) {
        return resolve(level, pos, false);
    }

    public Optional<ContainerTarget> resolve(Level level, BlockPos pos, boolean allowManual) {
        return resolveState(level, pos, allowManual, true);
    }

    public boolean isManagedOrEligible(Level level, BlockPos pos) {
        BlockEntity tile = level.getBlockEntity(pos);
        if (!(tile instanceof RandomizableContainerBlockEntity lootable) || !(tile instanceof Container)) {
            return false;
        }
        if (ContainerMarker.isIgnored(tile)) {
            return false;
        }
        // Remember a loot table while it is still there. Reading the container -- a player opening it, or a
        // hopper's handler walking its slots -- rolls the table and clears it, so "has a table right now" is
        // a property that can vanish mid-tick. Without this a hopper could read a fresh dungeon chest, roll
        // its table, watch the chest stop qualifying, and drain it on the very next tick.
        if (lootable.getLootTable() != null) {
            ContainerMarker.rememberLootTable(tile, lootable.getLootTable());
        }
        return ContainerMarker.readId(tile, logger) != null || ContainerMarker.rememberedLootTable(tile) != null;
    }

    public Optional<UUID> existingId(Level level, BlockPos pos) {
        BlockEntity tile = level.getBlockEntity(pos);
        return tile == null ? Optional.empty() : Optional.ofNullable(ContainerMarker.readId(tile, logger));
    }

    public void removeIdentity(ContainerTarget target) {
        ContainerMarker.markIgnored(target.tiles());
    }

    /**
     * Removes the ignored flag so this container can be taken over again.
     *
     * <p>The inverse of {@link #removeIdentity}. Only reachable from {@code /fl adopt}: the flag exists to
     * make sure a container that was handed back to vanilla is never silently managed again, so undoing it
     * has to be a deliberate administrative act, never a side effect.
     */
    public void clearIgnored(ContainerTarget target) {
        ContainerMarker.clearIgnored(target.tiles());
        setChanged(target);
    }

    public void clearSource(ContainerTarget target) {
        target.sourceInventory().clearContent();
        for (BlockEntity tile : target.tiles()) {
            if (tile instanceof RandomizableContainerBlockEntity lootable) {
                lootable.setLootTable(null);
            }
        }
        setChanged(target);
    }

    public void prepareNormalInventory(ContainerTarget target) {
        boolean hasOriginalLootTable = target.tiles().stream()
                .filter(RandomizableContainerBlockEntity.class::isInstance)
                .map(RandomizableContainerBlockEntity.class::cast)
                .anyMatch(lootable -> lootable.getLootTable() != null);
        if (hasOriginalLootTable) {
            clearSource(target);
        }
    }

    public void releaseToNormal(ContainerTarget target) {
        prepareNormalInventory(target);
        removeIdentity(target);
    }

    public void restoreVanillaInventory(ContainerTarget target, List<ItemStack> contents) {
        target.sourceInventory().clearContent();
        int size = target.sourceInventory().getContainerSize();
        for (int slot = 0; slot < Math.min(contents.size(), size); slot++) {
            ItemStack stack = contents.get(slot);
            target.sourceInventory().setItem(slot, stack == null ? ItemStack.EMPTY : stack);
        }
        for (BlockEntity tile : target.tiles()) {
            if (tile instanceof RandomizableContainerBlockEntity lootable) {
                lootable.setLootTable(null);
            }
        }
        setChanged(target);
        removeIdentity(target);
    }

    private Optional<ContainerTarget> resolveState(Level level, BlockPos pos, boolean allowManual,
            boolean writeIdentity) {
        // Excluded worlds are never managed automatically. This is checked here rather than at the
        // interaction handler so that every route in -- a player opening the chest, a hopper pulling from
        // it, a block-break check -- sees the same answer. An explicit admin action (allowManual) still
        // gets through, so an operator can inspect or release a container in an excluded world.
        if (!allowManual && isExcluded(level)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity tile = level.getBlockEntity(pos);
        if (!(tile instanceof RandomizableContainerBlockEntity lootable) || !(tile instanceof Container)) {
            return Optional.empty();
        }

        Container inventory = inventoryOf(level, pos, state, (Container) tile);
        List<BlockEntity> tiles = pairedTiles(level, pos, state, tile);
        if (!allowManual && tiles.stream().anyMatch(ContainerMarker::isIgnored)) {
            return Optional.empty();
        }

        ResourceKey<LootTable> table = lootable.getLootTable();
        UUID id = null;
        List<UUID> existingIds = new ArrayList<>();
        for (BlockEntity candidate : tiles) {
            UUID candidateId = ContainerMarker.readId(candidate, logger);
            if (candidateId != null) {
                existingIds.add(candidateId);
                if (id != null && !id.equals(candidateId)) {
                    logger.warn("Merging mismatched FiniteLoot IDs on a double container: {} / {}",
                            id, candidateId);
                } else if (id == null) {
                    id = candidateId;
                }
            }
            // A double chest only reports a loot table from one half; take it from whichever half has it.
            if (table == null && candidate instanceof RandomizableContainerBlockEntity candidateLootable) {
                table = candidateLootable.getLootTable();
            }
        }

        // A container whose table was consumed before anything resolved it would otherwise be protected
        // against hoppers by a rule that cannot name it -- and /fl remove would report nothing to remove.
        // Recovering the remembered id also means a per-player roll can still be generated from the same
        // table, so the player sees loot instead of a claim that fails with "no loot table recorded".
        if (table == null) {
            for (BlockEntity candidate : tiles) {
                ResourceKey<LootTable> remembered = ContainerMarker.rememberedLootTable(candidate);
                if (remembered != null) {
                    table = remembered;
                    break;
                }
            }
        }

        if (id == null && table == null && !allowManual) {
            return Optional.empty();
        }

        boolean newlyIdentified = id == null;
        id = ContainerIdentity.normalize(existingIds, UUID::randomUUID);
        if (writeIdentity) {
            ContainerMarker.writeId(tiles, id, logger);
        }
        return Optional.of(new ContainerTarget(id, List.copyOf(tiles), inventory, table, newlyIdentified));
    }

    /**
     * Accepts either spelling of a dimension in {@code excluded-worlds}.
     *
     * <p>Paper configured this with {@code World#getName()}, which is a plain folder name like
     * {@code world_nether}. A mod's natural name is the registry key, {@code minecraft:the_nether}. Neither
     * spelling is obviously right, so both match: the operator does not have to know which one the
     * implementation happens to prefer.
     */
    private boolean isExcluded(Level level) {
        ResourceLocation key = level.dimension().location();
        return excludedDimension.test(key.toString()) || excludedDimension.test(key.getPath());
    }

    /**
     * The combined inventory for a double chest, or the block entity itself otherwise.
     *
     * <p>Resolving this through {@code ChestBlock.getContainer} rather than by hand is what keeps
     * double chests from being treated as two independent containers -- the bug that would otherwise
     * let the same physical chest be claimed twice.
     */
    private Container inventoryOf(Level level, BlockPos pos, BlockState state, Container tile) {
        if (state.getBlock() instanceof ChestBlock chest && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Container combined = ChestBlock.getContainer(chest, state, level, pos, false);
            if (combined != null) {
                return combined;
            }
        }
        return tile;
    }

    private List<BlockEntity> pairedTiles(Level level, BlockPos pos, BlockState state, BlockEntity tile) {
        List<BlockEntity> result = new ArrayList<>();
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction partnerDirection = ChestBlock.getConnectedDirection(state);
            BlockPos partnerPos = pos.relative(partnerDirection);
            BlockState partnerState = level.getBlockState(partnerPos);
            if (partnerState.getBlock() == state.getBlock()
                    && partnerState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockEntity partner = level.getBlockEntity(partnerPos);
                if (partner != null) {
                    result.add(partner);
                }
            }
        }
        result.add(tile);
        result.sort(TILE_ORDER);
        return result;
    }

    private void setChanged(ContainerTarget target) {
        for (BlockEntity tile : target.tiles()) {
            tile.setChanged();
        }
        // A CompoundContainer wraps two block entities and does not forward setChanged to them.
        if (target.sourceInventory() instanceof CompoundContainer) {
            target.sourceInventory().setChanged();
        }
    }
}
