package vip.naya.finiteloot.p0.containers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.IItemHandler;
import vip.naya.finiteloot.p0.ProbeMod;

import java.util.ArrayList;
import java.util.List;

/**
 * P0-5: how does a modded container get recognised generically?
 *
 * <p>You chose "every container in the pack that carries loot should be taken over". That rules out a
 * hard-coded block list, so the port needs a rule that works for containers it has never seen. This
 * probe evaluates the two candidate rules and reports which one actually fires:
 *
 * <ol>
 *   <li><b>Loot-table rule</b> -- {@code BlockEntity instanceof RandomizableContainerBlockEntity} and
 *       {@code getLootTable() != null}. This mirrors the Paper plugin's {@code Lootable} check and is
 *       the semantically correct one: a container only has "finite loot" if it has a loot table.
 *   <li><b>Capability rule</b> -- the block exposes {@code Capabilities.ItemHandler.BLOCK}. Broader
 *       (it catches modded crates and machines too) but pulls in containers that have nothing to do
 *       with loot, which would fight every storage mod on the server.
 * </ol>
 *
 * <p>The expected outcome, to be confirmed by running this: rule 1 is the right default, because
 * "has a loot table" is precisely the condition under which FiniteLoot has anything to distribute.
 * Rule 2 is useful only as a secondary signal, e.g. to decide whether hopper protection is relevant.
 *
 * <p>Note that on 1.21.1 {@link RandomizableContainerBlockEntity#getLootTable()} returns a
 * {@link ResourceKey}{@code <LootTable>}, and the concrete accessor used by the real port is
 * {@code getLootTable()} rather than Paper's {@code Lootable#getLootTable()} returning a
 * {@code NamespacedKey}.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class ContainerProbe {

    private ContainerProbe() {
    }

    /**
     * Classifies a block entity against both candidate rules.
     *
     * <p>Called by the real port before taking over any container, so the classification is reported
     * verbatim.
     */
    public static Classification classify(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);

        boolean isRandomizable = be instanceof RandomizableContainerBlockEntity;
        ResourceKey<LootTable> lootTable = isRandomizable
                ? ((RandomizableContainerBlockEntity) be).getLootTable()
                : null;
        long lootTableSeed = isRandomizable
                ? ((RandomizableContainerBlockEntity) be).getLootTableSeed()
                : 0L;

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        boolean hasItemCapability = handler != null;

        return new Classification(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                be == null ? "none" : be.getClass().getName(),
                isRandomizable,
                lootTable == null ? null : lootTable.location().toString(),
                lootTableSeed,
                hasItemCapability,
                handler == null ? 0 : handler.getSlots());
    }

    /**
     * Reads the current contents of a container, used to prove the server sees the real inventory.
     *
     * <p>The real port uses this to capture the "template" contents on first identification, matching
     * {@code ContainerManager.prepareNormalInventory} in the Paper code.
     */
    public static List<ItemStack> snapshot(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RandomizableContainerBlockEntity rcbe)) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>(rcbe.getContainerSize());
        for (int i = 0; i < rcbe.getContainerSize(); i++) {
            out.add(rcbe.getItem(i));
        }
        return out;
    }

    /**
     * Reports the vanilla subclasses we expect to match, so a mis-mapped import fails loudly rather
     * than silently classifying every chest as "not a container".
     */
    public static void selfCheck(ServerLevel level) {
        ProbeMod.LOG.info(
                "[P0-5] base-class sanity: ChestBlockEntity={} BarrelBlockEntity={} ShulkerBoxBlockEntity={}",
                RandomizableContainerBlockEntity.class.isAssignableFrom(ChestBlockEntity.class),
                RandomizableContainerBlockEntity.class.isAssignableFrom(BarrelBlockEntity.class),
                RandomizableContainerBlockEntity.class.isAssignableFrom(ShulkerBoxBlockEntity.class));
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p0")) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Classification c = classify(level, event.getPos());
        if (c.isRandomizable()) {
            ProbeMod.LOG.info(
                    "[P0-5] broke container {} | be={} | lootTable={} | itemCapability={} ({})",
                    c.blockId(), c.blockEntityClass(), c.lootTable(), c.hasItemCapability(), c.capabilitySlots());
        }
    }

    /** Result of classifying one block against both candidate rules. */
    public record Classification(
            String blockId,
            String blockEntityClass,
            boolean isRandomizable,
            String lootTable,
            long lootTableSeed,
            boolean hasItemCapability,
            int capabilitySlots) {

        /** Rule 1: the semantic "this container has finite loot" check. */
        public boolean matchesLootRule() {
            return isRandomizable && lootTable != null;
        }

        /** Rule 2: the broad "automation can reach it" check. */
        public boolean matchesCapabilityRule() {
            return hasItemCapability;
        }
    }
}
