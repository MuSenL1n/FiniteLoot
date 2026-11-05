package vip.naya.finiteloot.container;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Reads and writes the FiniteLoot marker on a container block entity.
 *
 * <p>This is the mapped equivalent of Paper's {@code PersistentDataContainer} usage: the same two
 * pieces of state (container id, ignored flag), the same tag names, on the block entity rather than on
 * a {@code TileState} snapshot.
 *
 * <p>One deliberate difference: Paper had to call {@code TileState.update(true, false)} after every
 * write because it was mutating a <em>copy</em> of the block snapshot and pushing it back. An attachment
 * is set on the live block entity, so there is no snapshot to push; the block entity is simply marked
 * dirty by {@code setData}. Forgetting the old {@code update()} call is therefore not a bug here.
 */
public final class ContainerMarker {

    private ContainerMarker() {
    }

    /** The stored id, or {@code null} when this block entity is not managed by FiniteLoot. */
    public static UUID readId(BlockEntity blockEntity, Logger logger) {
        String value = blockEntity.getData(AttachmentTypes.CONTAINER_ID.get());
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            // A hand-edited or corrupted world must not take the whole container down with it: drop
            // the marker and let the container be identified again from its loot table.
            logger.warn("Ignoring invalid FiniteLoot container ID at {}: {}", blockEntity.getBlockPos(), value);
            blockEntity.removeData(AttachmentTypes.CONTAINER_ID.get());
            return null;
        }
    }

    public static void writeId(List<? extends BlockEntity> tiles, UUID id, Logger logger) {
        for (BlockEntity tile : tiles) {
            tile.removeData(AttachmentTypes.IGNORED.get());
            tile.setData(AttachmentTypes.CONTAINER_ID.get(), id.toString());
        }
    }

    public static boolean isIgnored(BlockEntity blockEntity) {
        return Boolean.TRUE.equals(blockEntity.getData(AttachmentTypes.IGNORED.get()));
    }

    public static void markIgnored(List<? extends BlockEntity> tiles) {
        for (BlockEntity tile : tiles) {
            tile.removeData(AttachmentTypes.CONTAINER_ID.get());
            tile.setData(AttachmentTypes.IGNORED.get(), Boolean.TRUE);
        }
    }

    /**
     * Removes the ignored flag so the container becomes eligible for automatic takeover again.
     *
     * <p>Only {@code /fl adopt} needs this. Nothing else in the mod clears the flag, because the flag is
     * what stops a released container from being re-managed and re-filled -- the anti-duplication
     * guarantee depends on it staying put. Making it reachable only from an administrative command keeps
     * that guarantee intact while still giving an operator a way out of a mistake.
     */
    public static void clearIgnored(List<? extends BlockEntity> tiles) {
        for (BlockEntity tile : tiles) {
            tile.removeData(AttachmentTypes.IGNORED.get());
        }
    }

    /** Remembers the loot table this container carries, so it survives the table being consumed. */
    public static void rememberLootTable(BlockEntity blockEntity, ResourceKey<LootTable> table) {
        blockEntity.setData(AttachmentTypes.LOOT_TABLE_ID.get(), table.location().toString());
    }

    /** The loot table this container was seen with, or {@code null} when none was ever recorded. */
    public static ResourceKey<LootTable> rememberedLootTable(BlockEntity blockEntity) {
        String value = blockEntity.getData(AttachmentTypes.LOOT_TABLE_ID.get());
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(value));
        } catch (RuntimeException exception) {
            // A hand-edited world must not take the container down: forget the value and let the
            // container be identified again from whatever it carries now.
            blockEntity.removeData(AttachmentTypes.LOOT_TABLE_ID.get());
            return null;
        }
    }

    /** True when this block entity carries FiniteLoot state at all -- used by the probe and by /fl inspect. */
    public static boolean hasAnyMarker(BlockEntity blockEntity) {
        String id = blockEntity.getData(AttachmentTypes.CONTAINER_ID.get());
        return (id != null && !id.isEmpty()) || isIgnored(blockEntity)
                || rememberedLootTable(blockEntity) != null;
    }
}
