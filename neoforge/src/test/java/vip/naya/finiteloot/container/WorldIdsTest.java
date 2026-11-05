package vip.naya.finiteloot.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

/**
 * Locks the dimension UUIDs to fixed values.
 *
 * <p>{@link WorldIds} hashes a dimension's name into a UUID so the untouched data layer keeps its
 * {@code world_uid} column. That hash is therefore <em>on disk</em>: every container row is keyed by it.
 * Changing the namespace constant, the encoding, or the hash function would silently orphan every
 * existing container -- the mod would see a world full of unclaimed chests that already have rows, and
 * the operator would find out by watching claim limits reset.
 *
 * <p>So the expected values below are literals on purpose. They are not "whatever the method returns";
 * they are the numbers that are already in players' databases. Recomputing them in the test would make
 * the test agree with any change, which is exactly the failure it exists to prevent.
 */
class WorldIdsTest {

    @Test
    void overworldKeepsItsHistoricId() {
        assertEquals(UUID.fromString("0977ef35-f820-398b-9e7b-817dc90c73e2"),
                WorldIds.of(ResourceLocation.parse("minecraft:overworld")));
    }

    @Test
    void netherAndEndKeepTheirHistoricIds() {
        assertEquals(UUID.fromString("896885c5-f2a5-3ae9-9853-0fbe0a207b48"),
                WorldIds.of(ResourceLocation.parse("minecraft:the_nether")));
        assertEquals(UUID.fromString("17e4d1c5-c252-30b7-a847-f0da29016a02"),
                WorldIds.of(ResourceLocation.parse("minecraft:the_end")));
    }

    @Test
    void differentDimensionsNeverCollide() {
        UUID overworld = WorldIds.of(ResourceLocation.parse("minecraft:overworld"));
        UUID nether = WorldIds.of(ResourceLocation.parse("minecraft:the_nether"));
        UUID custom = WorldIds.of(ResourceLocation.parse("somepack:mining_dimension"));

        assertNotEquals(overworld, nether);
        assertNotEquals(overworld, custom);
        assertNotEquals(nether, custom);
    }

    @Test
    void sameDimensionAlwaysHashesTheSame() {
        // Restart stability is the whole point: the value must not depend on anything but the name.
        ResourceLocation overworld = ResourceLocation.parse("minecraft:overworld");
        assertEquals(WorldIds.of(overworld), WorldIds.of(overworld));
        assertEquals(WorldIds.of(overworld),
                WorldIds.of(ResourceLocation.parse("minecraft:overworld")));
    }
}
