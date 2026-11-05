package vip.naya.finiteloot.container;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Gives each dimension a stable UUID, because the database stores one.
 *
 * <p>Paper's {@code World#getUID()} returns a UUID that is persisted inside the world folder. Minecraft
 * has no such thing -- a dimension is identified by its {@code ResourceKey}, e.g. {@code minecraft:overworld}.
 *
 * <p>Rather than change the data layer (which is byte-identical to the original and verified), the
 * dimension's name is hashed into a name-based UUID. That keeps {@code containers.world_uid} a UUID column,
 * keeps {@code ContainerRecord} unchanged, and is stable across restarts and across servers -- which is
 * what matters, since two different servers never share a database.
 *
 * <p>The prefix keeps these hashes from ever colliding with a UUID derived from something else in a
 * future format change.
 */
public final class WorldIds {

    private static final String NAMESPACE = "finiteloot:dimension:";

    private WorldIds() {
    }

    public static UUID of(ServerLevel level) {
        return of(level.dimension().location());
    }

    public static UUID of(ResourceLocation dimension) {
        return UUID.nameUUIDFromBytes(
                (NAMESPACE + dimension.toString()).getBytes(StandardCharsets.UTF_8));
    }
}
