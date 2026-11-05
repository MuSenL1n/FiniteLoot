package vip.naya.finiteloot.p0.pack;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.items.IItemHandler;

import vip.naya.finiteloot.FiniteLootRuntime;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.gui.RewardMenu;
import vip.naya.finiteloot.p0.ProbeGate;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * Verifies takeover of a container that belongs to <em>another mod</em>.
 *
 * <p>Every earlier phase used vanilla blocks. Those prove the logic, not the reach: FiniteLoot's
 * eligibility walk is registry-driven and names no block, so the open question was whether that walk
 * really covers a block entity type registered by someone else, whose own mod also provides an item
 * handler for it.
 *
 * <p>The subject is {@code fltest:loot_crate}, shipped by a separate mod jar (mod id {@code fltest})
 * that deliberately registers its own {@code IItemHandler} provider at the default priority -- the way
 * an ordinary tech mod does. If FiniteLoot's {@code HIGHEST} provider is consulted first, the refusal
 * wins and the mod's own wrapper is only reached for containers FiniteLoot leaves alone. That is the
 * whole inter-operation story, and this probe is what makes it a measurement.
 *
 * <p>Enable with {@code -Dfiniteloot.probes=pack}. Without the test mod installed the probe reports
 * INCONCLUSIVE rather than passing or failing: absence of the subject is not evidence either way.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class PackContainerProbe {

    private static final ResourceLocation CRATE = ResourceLocation.parse("fltest:loot_crate");
    /**
     * A vanilla loot table on purpose. The subject under test is the <em>block entity type</em> coming from
     * another mod; whether this mod's own data pack loads would add a second, unrelated variable (and a
     * missing table looks exactly like "the container is empty", which is the wrong thing to learn here).
     */
    private static final ResourceKey<LootTable> CRATE_LOOT =
            ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon"));

    private static final BlockPos CRATE_POS = new BlockPos(60, 70, 80);
    private static final double PLAYER_X = 62.5;
    private static final double PLAYER_Z = 80.5;

    private static Scenario scenario;
    private static boolean started;

    private PackContainerProbe() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ProbeGate.enabled("pack") || started || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        started = true;
        try {
            scenario = new Scenario(player);
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[PACK] setup FAILED", throwable);
            scenario = null;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (scenario == null) {
            return;
        }
        try {
            if (scenario.tick()) {
                scenario = null;
            }
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[PACK] FAILED with exception", throwable);
            scenario = null;
        }
    }

    private static final class Scenario {

        private final ServerPlayer player;
        private final ServerLevel level;
        private final FiniteLootRuntime runtime;

        private int phase;
        private int ticksInPhase;
        private int failures;
        private boolean clicked;
        private boolean clickCanceled;

        Scenario(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            this.runtime = FiniteLootRuntime.get();
        }

        boolean tick() {
            if (runtime == null) {
                ProbeMod.LOG.error("[PACK] FAILED - the runtime is not up");
                return true;
            }
            if (player.isRemoved() || level.getServer() == null
                    || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
                ProbeMod.LOG.error("[PACK] FAILED - player left in phase {}", phase);
                return true;
            }
            if (++ticksInPhase > 300) {
                ProbeMod.LOG.error("[PACK] FAILED - phase {} stuck", phase);
                return true;
            }
            switch (phase) {
                case 0 -> {
                    Block crate = BuiltInRegistries.BLOCK.get(CRATE);
                    if (crate == Blocks.AIR) {
                        ProbeMod.LOG.info("[PACK] INCONCLUSIVE - {} is not registered, so the test mod is "
                                + "not installed; cross-mod takeover is neither proven nor disproven",
                                CRATE);
                        return true;
                    }
                    check("a container registered by another mod is visible to the game", true);

                    clearBox(56, 70, 76, 68, 72, 84);
                    fillBox(56, 69, 76, 68, 69, 84, Blocks.STONE.defaultBlockState());
                    level.setBlock(CRATE_POS, crate.defaultBlockState(), 3);

                    BlockEntity tile = level.getBlockEntity(CRATE_POS);
                    if (!(tile instanceof RandomizableContainerBlockEntity lootable)) {
                        ProbeMod.LOG.error("[PACK] FAILED - {} did not create a loot-table block entity",
                                CRATE);
                        return true;
                    }
                    lootable.setLootTable(CRATE_LOOT, 1234L);
                    player.teleportTo(level, PLAYER_X, 70.0, PLAYER_Z, 0.0F, 0.0F);
                    phase = 1;
                }
                case 1 -> {
                    // One-shot work behind a flag, assertions after the wait: running the checks every
                    // waiting tick counts the same failure dozens of times (P7 paid for this lesson).
                    if (!clicked) {
                        clicked = true;
                        // Handler first, before anything reads the container: reading rolls the loot table.
                        IItemHandler handler = level.getCapability(
                                Capabilities.ItemHandler.BLOCK, CRATE_POS, Direction.UP);
                        boolean eligible = runtime.containers().isManagedOrEligible(level, CRATE_POS);
                        int slot = firstFilledSlot(handler);
                        boolean refusesExtraction = handler != null && slot >= 0
                                && handler.extractItem(slot, 1, true).isEmpty()
                                && handler.extractItem(slot, 1, false).isEmpty();

                        ProbeMod.LOG.info("[PACK] foreign container: block={} eligible={} handler={} slot={} "
                                        + "slots={} items={}",
                                CRATE, eligible, simpleName(handler), slot,
                                handler == null ? -1 : handler.getSlots(), itemCount(handler));

                        check("a loot container from another mod is taken over", eligible);
                        check("FiniteLoot's handler wins over the owning mod's own provider ["
                                + simpleName(handler) + "]", "ProtectedHandler".equals(simpleName(handler)));
                        check("the foreign container's handler refuses extraction [" + slot + "]",
                                refusesExtraction);
                        clickCanceled = click(CRATE_POS).isCanceled();
                    }
                    if (runtime.sessions().openSessionCount() == 0 && ticksInPhase < 40) {
                        return false;
                    }
                    check("right-click on a foreign container is intercepted", clickCanceled);
                    check("right-click opens the personal loot window",
                            player.containerMenu instanceof RewardMenu
                                    && runtime.sessions().openSessionCount() == 1);
                    player.closeContainer();
                    phase = 2;
                }
                case 2 -> {
                    if (runtime.sessions().openSessionCount() != 0 && ticksInPhase < 40) {
                        return false;
                    }
                    Optional<ContainerTarget> target = runtime.containers().resolve(level, CRATE_POS);
                    if (target.isEmpty()) {
                        check("the foreign container can be resolved for release", false);
                        return finish();
                    }
                    runtime.containers().removeIdentity(target.get());
                    IItemHandler afterRelease = level.getCapability(
                            Capabilities.ItemHandler.BLOCK, CRATE_POS, Direction.UP);
                    boolean unmanaged = !runtime.containers().isManagedOrEligible(level, CRATE_POS);
                    int slot = firstFilledSlot(afterRelease);
                    boolean drainable = afterRelease != null && slot >= 0
                            && !afterRelease.extractItem(slot, 1, true).isEmpty();
                    ProbeMod.LOG.info("[PACK] after release: managed={} handler={} slot={}",
                            !unmanaged, simpleName(afterRelease), slot);
                    check("a released foreign container is no longer managed", unmanaged);
                    check("the owning mod's own handler comes back after release ["
                            + simpleName(afterRelease) + "]", drainable);
                    return finish();
                }
                default -> {
                    return finish();
                }
            }
            return false;
        }

        private boolean finish() {
            if (failures == 0) {
                ProbeMod.LOG.info("[PACK] SUCCESS - a container from another mod is taken over");
            } else {
                ProbeMod.LOG.error("[PACK] FAILURE - {} assertion(s) failed", failures);
            }
            return true;
        }

        private void check(String what, boolean ok) {
            if (!ok) {
                failures++;
            }
            ProbeMod.LOG.info("[PACK] {} {}", what, ok ? "PASS" : "FAIL");
        }

        private PlayerInteractEvent.RightClickBlock click(BlockPos pos) {
            BlockHitResult hit = new BlockHitResult(
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                    Direction.UP, pos, false);
            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                    player, InteractionHand.MAIN_HAND, pos, hit);
            NeoForge.EVENT_BUS.post(event);
            return event;
        }

        private int firstFilledSlot(IItemHandler handler) {
            if (handler == null) {
                return -1;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!handler.getStackInSlot(slot).isEmpty()) {
                    return slot;
                }
            }
            return -1;
        }

        private int itemCount(IItemHandler handler) {
            if (handler == null) {
                return 0;
            }
            int total = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                total += handler.getStackInSlot(slot).getCount();
            }
            return total;
        }

        private static String simpleName(Object handler) {
            return handler == null ? "null" : handler.getClass().getSimpleName();
        }

        private void clearBox(int x1, int y1, int z1, int x2, int y2, int z2) {
            fillBox(x1, y1, z1, x2, y2, z2, Blocks.AIR.defaultBlockState());
        }

        private void fillBox(int x1, int y1, int z1, int x2, int y2, int z2,
                net.minecraft.world.level.block.state.BlockState state) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.getBlockState(pos).equals(state)) {
                            level.setBlock(pos, state, 3);
                        }
                    }
                }
            }
        }
    }
}
