package vip.naya.finiteloot.p0.hopper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import vip.naya.finiteloot.p0.ProbeMod;
import vip.naya.finiteloot.p0.containers.ContainerProbe;

/**
 * P0-4, answered by experiment instead of by reading source.
 *
 * <p>The question that decides the whole hopper-protection design is: <em>when a hopper pulls from a
 * chest, which capability provider is consulted?</em> Reading {@code BlockCapability} says providers
 * are walked in registration order and the first non-null wins, and that NeoForge registers its own
 * provider for {@code Blocks.CHEST}. If NeoForge's registration runs first, then adding a second
 * provider for the same block is dead code and hopper interception needs a Mixin.
 *
 * <p>Reading order in the source is suggestive but not proof, because the event listener order
 * between our mod and {@code CapabilityHooks} is decided at runtime. So this builds a real chest above
 * a real hopper in the overworld and watches whether {@link MarkedItemHandler} is ever asked to
 * extract. That is the only evidence that distinguishes the two designs, and it is cheap to obtain.
 *
 * <p>Runs once, then reports and stops. The test area is high in the air far from spawn so it cannot
 * collide with anything a player has built.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class HopperWorldTest {

    private static final BlockPos CHEST_POS = new BlockPos(8, 220, 8);
    private static final BlockPos HOPPER_POS = new BlockPos(8, 219, 8);

    /**
     * Second rig, for P0-4b. A barrel instead of a chest, on purpose: NeoForge registers the barrel's
     * item handler through {@code registerBlockEntity(BARREL, ...)}, whereas the chest goes through
     * {@code registerBlock(Blocks.CHEST, ...)}. Modded containers are registered the barrel way, so
     * this rig is the one that actually tests whether the generic takeover reaches modded containers
     * -- the chest result alone does not transfer, because it is a different registration path.
     */
    private static final BlockPos BARREL_POS = new BlockPos(20, 220, 8);
    private static final BlockPos HOPPER2_POS = new BlockPos(20, 219, 8);

    private static final int TICKS_BEFORE_REPORT = 100;

    private static boolean ran;
    private static int ticksAfterSetup = -1;

    private HopperWorldTest() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p0")) {
            return;
        }
        if (ran) {
            return;
        }
        ran = true;
        try {
            setup(event.getServer());
        } catch (Throwable t) {
            ProbeMod.LOG.error("[P0-4] world test setup failed", t);
        }
    }

    private static void setup(MinecraftServer server) {
        ServerLevel level = server.overworld();

        // Force-load the two chunks so a hopper far from any player still ticks.
        level.setChunkForced(CHEST_POS.getX() >> 4, CHEST_POS.getZ() >> 4, true);

        // Clear anything an earlier run left behind, then place the chest-over-hopper pair.
        level.setBlockAndUpdate(CHEST_POS, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(HOPPER_POS, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(CHEST_POS, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(HOPPER_POS, Blocks.HOPPER.defaultBlockState());
        level.invalidateCapabilities(CHEST_POS);
        level.invalidateCapabilities(HOPPER_POS);

        BlockEntity be = level.getBlockEntity(CHEST_POS);
        if (!(be instanceof RandomizableContainerBlockEntity chest)) {
            ProbeMod.LOG.error("[P0-4] world test: chest block entity missing/not randomizable: {}", be);
            return;
        }

        // Give it a real loot table so P0-5's loot rule is exercised on a genuine container, then
        // clear the table and stock it by hand: the hopper test must observe explicit items moving.
        ResourceLocation lootId = ResourceLocation.withDefaultNamespace("chests/simple_dungeon");
        chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, lootId), 12345L);
        ContainerProbe.Classification withLootTable = ContainerProbe.classify(level, CHEST_POS);
        ProbeMod.LOG.info("[P0-5] chest with loot table at {} => {}", CHEST_POS, withLootTable);
        ProbeMod.LOG.info("[P0-5] VERDICT lootRule={} capabilityRule={} (both expected true for a chest)",
                withLootTable.matchesLootRule(), withLootTable.matchesCapabilityRule());

        chest.setLootTable(null, 0L);
        for (int i = 0; i < 3; i++) {
            chest.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }
        chest.setChanged();

        int before = countChestDiamonds(level);
        ProbeMod.LOG.info("[P0-4] world test armed: chest@{} hopper@{} diamondsInChest={} "
                        + "ourProviderInvocations={}",
                CHEST_POS, HOPPER_POS, before, HopperProbe.ourProviderInvocations());

        setupBarrelRig(level);

        ticksAfterSetup = 0;
    }

    /**
     * Builds the P0-4b rig: a barrel above a hopper, on the {@code registerBlockEntity} path that
     * modded containers use. Runs in the same deny state as phase 2 would, so a single observation
     * answers "does the generic takeover reach a block-entity-registered container".
     */
    private static void setupBarrelRig(ServerLevel level) {
        level.setChunkForced(BARREL_POS.getX() >> 4, BARREL_POS.getZ() >> 4, true);
        level.setBlockAndUpdate(BARREL_POS, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(HOPPER2_POS, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(BARREL_POS, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(HOPPER2_POS, Blocks.HOPPER.defaultBlockState());
        level.invalidateCapabilities(BARREL_POS);
        level.invalidateCapabilities(HOPPER2_POS);

        BlockEntity be = level.getBlockEntity(BARREL_POS);
        if (!(be instanceof RandomizableContainerBlockEntity barrel)) {
            ProbeMod.LOG.error("[P0-4b] barrel rig: block entity missing/not randomizable: {}", be);
            return;
        }

        // A barrel with a loot table is exactly the shape of a modded loot crate, so this also
        // re-checks the P0-5 loot rule on a non-chest container registered by block entity type.
        ResourceLocation lootId = ResourceLocation.withDefaultNamespace("chests/simple_dungeon");
        barrel.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, lootId), 999L);
        ContainerProbe.Classification classified = ContainerProbe.classify(level, BARREL_POS);
        ProbeMod.LOG.info("[P0-5] barrel with loot table at {} => {}", BARREL_POS, classified);
        ProbeMod.LOG.info("[P0-5] barrel VERDICT lootRule={} capabilityRule={}",
                classified.matchesLootRule(), classified.matchesCapabilityRule());

        barrel.setLootTable(null, 0L);
        for (int i = 0; i < 3; i++) {
            barrel.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }
        barrel.setChanged();

        // Deny from the start on this rig: the question is whether the generic provider is reached
        // at all, and denial makes a win/miss immediately visible in the item counts.
        HopperProbe.denyAt(BARREL_POS);
        ProbeMod.LOG.info("[P0-4b] barrel rig armed (extraction DENIED): barrel@{} hopper@{} "
                + "diamondsInBarrel={} genericTypesClaimed={}",
                BARREL_POS, HOPPER2_POS, countBarrelDiamonds(level), HopperProbe.genericTypesClaimed());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p0")) {
            return;
        }
        if (ticksAfterSetup < 0) {
            return;
        }
        ticksAfterSetup++;

        if (ticksAfterSetup == TICKS_BEFORE_REPORT) {
            ServerLevel level = event.getServer().overworld();
            int chestDiamonds = countChestDiamonds(level);
            int hopperDiamonds = countHopperDiamonds(level);
            boolean hopperMoved = chestDiamonds < 3 || hopperDiamonds > 0;
            boolean ourHandlerConsulted = HopperProbe.ourProviderInvocations() > 0;
            ProbeMod.LOG.info("[P0-4] phase 1 result after {} ticks: chestDiamonds={} hopperDiamonds={} "
                            + "hopperMovedItems={} ourProviderInvocations={} ourHandlerConsulted={}",
                    TICKS_BEFORE_REPORT, chestDiamonds, hopperDiamonds, hopperMoved,
                    HopperProbe.ourProviderInvocations(), ourHandlerConsulted);
            if (ourHandlerConsulted) {
                ProbeMod.LOG.info("[P0-4] PHASE 1 VERDICT: OUR provider WAS consulted. A competing "
                        + "provider registered at HIGHEST priority beats NeoForge's own -> capability "
                        + "override is a live interception point.");
            } else {
                ProbeMod.LOG.info("[P0-4] PHASE 1 VERDICT: our provider was NOT consulted; NeoForge's "
                        + "won the lookup and a second provider is dead code.");
            }
            return;
        }

        // Phase 2 starts after phase 1 is reported: reload the chest, flip our handler to refuse, and
        // check that the hopper can no longer take anything. Phase 1 proves the provider is consulted;
        // phase 2 proves interception is actually effective, which is the part the real port depends on.
        if (ticksAfterSetup == TICKS_BEFORE_REPORT + 1) {
            ServerLevel level = event.getServer().overworld();
            BlockEntity be = level.getBlockEntity(CHEST_POS);
            if (be instanceof RandomizableContainerBlockEntity chest) {
                for (int i = 0; i < 3; i++) {
                    chest.setItem(i, new ItemStack(Items.DIAMOND, 1));
                }
                chest.setChanged();
            }
            // Empty the hopper first. Phase 1 legitimately moved 3 diamonds into it, and leaving them
            // there would make the phase 2 assertion ambiguous: a hopper already holding items is not
            // evidence that the chest was drained. Clearing it means any diamond in the hopper at the
            // end of phase 2 must have arrived after denial was switched on.
            BlockEntity hopperBe = level.getBlockEntity(HOPPER_POS);
            if (hopperBe instanceof net.minecraft.world.Container hopper) {
                hopper.clearContent();
                hopper.setChanged();
            }
            HopperProbe.denyAt(CHEST_POS);
            ProbeMod.LOG.info("[P0-4] phase 2 armed: extraction DENIED, chest re-stocked 3 diamonds, "
                    + "hopper emptied (chest={} hopper={})", countChestDiamonds(level),
                    countHopperDiamonds(level));
            return;
        }

        if (ticksAfterSetup != TICKS_BEFORE_REPORT + 1 + TICKS_BEFORE_REPORT) {
            return;
        }
        ticksAfterSetup = -1; // report exactly once

        ServerLevel level = event.getServer().overworld();
        int chestDiamonds = countChestDiamonds(level);
        int hopperDiamonds = countHopperDiamonds(level);
        HopperProbe.allowAt(CHEST_POS);
        HopperProbe.allowAt(BARREL_POS);

        if (chestDiamonds == 3 && hopperDiamonds == 0) {
            ProbeMod.LOG.info("[P0-4] PHASE 2 VERDICT: SUCCESS - extraction refused, chestDiamonds={} "
                    + "hopperDiamonds={}. Capability-provider interception is EFFECTIVE; no Mixin is "
                    + "needed to protect containers from hoppers.", chestDiamonds, hopperDiamonds);
        } else {
            ProbeMod.LOG.error("[P0-4] PHASE 2 VERDICT: FAILED - items still escaped (chestDiamonds={} "
                    + "hopperDiamonds={}). Refusing extractItem is not sufficient.", chestDiamonds,
                    hopperDiamonds);
        }

        // P0-4b: the barrel goes through registerBlockEntity, the same path a modded container uses.
        int barrelDiamonds = countBarrelDiamonds(level);
        int hopper2Diamonds = countDiamonds2(level.getBlockEntity(HOPPER2_POS));
        ProbeMod.LOG.info("[P0-4b] barrel result: barrelDiamonds={} hopperDiamonds={} "
                        + "genericProviderInvocations={} genericWins={} genericTypesClaimed={}",
                barrelDiamonds, hopper2Diamonds, HopperProbe.genericInvocations(),
                HopperProbe.genericWins(), HopperProbe.genericTypesClaimed());

        if (barrelDiamonds == 3 && hopper2Diamonds == 0 && HopperProbe.genericWins() > 0) {
            ProbeMod.LOG.info("[P0-4b] VERDICT: SUCCESS - the generic takeover (no hard-coded block "
                    + "names, registered per block entity type) reached a container that NeoForge "
                    + "registers via registerBlockEntity, and denial held. This is the path modded "
                    + "containers use, so generic takeover of pack containers is viable.");
        } else if (barrelDiamonds == 3 && hopper2Diamonds == 0) {
            ProbeMod.LOG.error("[P0-4b] PARTIAL: the barrel kept its items but our generic provider "
                    + "never ran (wins=0). Items stayed put for some other reason, so this is NOT "
                    + "evidence of interception.");
        } else {
            ProbeMod.LOG.error("[P0-4b] VERDICT: FAILED - barrel items escaped "
                    + "(barrelDiamonds={} hopperDiamonds={}). The generic takeover did not hold on the "
                    + "registerBlockEntity path.", barrelDiamonds, hopper2Diamonds);
        }
    }

    private static int countBarrelDiamonds(ServerLevel level) {
        return countDiamonds(level.getBlockEntity(BARREL_POS));
    }

    private static int countDiamonds2(BlockEntity be) {
        return countDiamonds(be);
    }

    private static int countChestDiamonds(ServerLevel level) {
        return countDiamonds(level.getBlockEntity(CHEST_POS));
    }

    private static int countHopperDiamonds(ServerLevel level) {
        return countDiamonds(level.getBlockEntity(HOPPER_POS));
    }

    private static int countDiamonds(BlockEntity be) {
        if (!(be instanceof net.minecraft.world.Container container)) {
            return -1;
        }
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.is(Items.DIAMOND)) {
                total += s.getCount();
            }
        }
        return total;
    }
}
