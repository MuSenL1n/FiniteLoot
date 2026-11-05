package vip.naya.finiteloot.loot;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.data.ContainerRecord;

/**
 * Rolls a container's loot table the same way vanilla would, then spreads the result over the container's
 * slots.
 *
 * <p>Mapping notes, because none of this is a straight rename:
 *
 * <ul>
 *   <li>Paper's {@code LootContext} has become {@code LootParams} in 1.21.1, built against a
 *       {@code LootContextParamSet}. {@code LootContextParamSets.CHEST} requires {@code ORIGIN} and allows
 *       {@code THIS_ENTITY}, which is exactly what a chest roll needs -- so a chest context with the
 *       looting player attached is expressible without inventing a custom param set.
 *   <li>The loot table is looked up through {@code ReloadableServerRegistries}, not the level's registry
 *       access: in 1.21.1 loot tables are datapack data and are reloaded, so they do not live in the
 *       static registry view. Asking the level for them throws "Missing registry".
 *   <li>Luck is read from the player's {@code Attributes.LUCK} rather than Paper's {@code Attribute.LUCK};
 *       same attribute, so luck-based loot tables behave the same.
 * </ul>
 *
 * <p>The result is placed into a freshly allocated array the size of the container and then shuffled.
 * Vanilla fills slots left to right, which would make the loot order predictable; shuffling keeps the
 * layout roughly as random as a hand-opened chest.
 */
public final class LootGenerator {

    /**
     * @param record the container row, which may already carry a captured template
     * @param target the live container, used for its loot table and slot count
     * @param player the claimant, for luck and for loot conditions that reference the looting entity
     */
    public ItemStack[] generate(ContainerRecord record, ContainerTarget target, ServerPlayer player)
            throws LootResolutionException {
        if (record.templateContents() != null) {
            // A template means the loot was pinned when the container was created (used by admin flows).
            // Replaying it verbatim is the point: every claimant must see the same set of items.
            try {
                return ItemStacks.deserialize(record.templateContents(), player.server.registryAccess());
            } catch (IOException exception) {
                throw new LootResolutionException("could not read the stored template for "
                        + record.id() + ": " + exception.getMessage());
            }
        }

        LootTable table = resolveTable(record, target);
        int size = target.sourceInventory().getContainerSize();
        if (size <= 0) {
            return new ItemStack[0];
        }

        ServerLevel level = (ServerLevel) target.primary().getLevel();
        float luck = (float) player.getAttributeValue(Attributes.LUCK);
        Vec3 origin = Vec3.atCenterOf(target.primary().getBlockPos());

        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, origin)
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(luck)
                .create(LootContextParamSets.CHEST);

        List<ItemStack> generated = table.getRandomItems(params);
        ItemStack[] contents = new ItemStack[size];
        int index = 0;
        for (ItemStack stack : generated) {
            if (index >= size) {
                // Vanilla drops the overflow on the floor; a personal inventory has nowhere to put it.
                // Truncating is the only option that does not duplicate items.
                break;
            }
            contents[index++] = stack;
        }
        shuffle(contents);
        return contents;
    }

    /**
     * Finds the table to roll: the live one if the container still has it, otherwise the recorded key.
     *
     * <p>The fallback matters. A container can lose its loot table (that is how FiniteLoot retires one),
     * and a claim may still arrive for a player who was allocated before that happened; without the
     * fallback those players would be told the container is broken.
     */
    private LootTable resolveTable(ContainerRecord record, ContainerTarget target)
            throws LootResolutionException {
        if (target.lootTable() != null) {
            LootTable resolved = lookup(target, target.lootTable());
            if (resolved != null) {
                return resolved;
            }
        }
        String recorded = record.lootTable();
        if (recorded == null) {
            throw new LootResolutionException("no loot table recorded for container " + record.id());
        }
        ResourceLocation location = ResourceLocation.tryParse(recorded);
        if (location == null) {
            throw new LootResolutionException("unparseable loot table key '" + recorded + "' for " + record.id());
        }
        LootTable resolved = lookup(target, ResourceKey.create(Registries.LOOT_TABLE, location));
        if (resolved == null) {
            throw new LootResolutionException(
                    "loot table '" + recorded + "' is no longer loaded for " + record.id());
        }
        return resolved;
    }

    private LootTable lookup(ContainerTarget target, ResourceKey<LootTable> key) {
        var server = ((ServerLevel) target.primary().getLevel()).getServer();
        LootTable table = server.reloadableRegistries().getLootTable(key);
        return table == LootTable.EMPTY ? null : table;
    }

    private void shuffle(ItemStack[] contents) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = contents.length - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            ItemStack temporary = contents[index];
            contents[index] = contents[swap];
            contents[swap] = temporary;
        }
    }

    /** Raised when the loot table a container was created with can no longer be rolled. */
    public static final class LootResolutionException extends Exception {
        public LootResolutionException(String message) {
            super(message);
        }
    }
}
