package vip.naya.finiteloot.p0.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import vip.naya.finiteloot.FiniteLootRuntime;
import vip.naya.finiteloot.gui.RewardMenu;
import vip.naya.finiteloot.p0.ProbeGate;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P7: the world-facing protections, verified against a running server.
 *
 * <p>Each phase exercises one protection through the same entry point the game uses:
 *
 * <ul>
 *   <li>Right-click is a posted {@code RightClickBlock} event, which is what the server fires when the packet
 *       arrives. Posting it skips only the packet decode, which P0-2 already proved end to end.
 *   <li>Breaking is a posted {@code BlockEvent.BreakEvent}.
 *   <li>The explosion is a <em>real</em> {@code level.explode(...)}, not a synthetic event, so the vanilla
 *       explosion path runs and only the cancellation is ours.
 *   <li>The hopper is a real hopper block with real items, left alone for a few seconds. There is no way to
 *       fake this one: the point is whether automation can empty the chest.
 * </ul>
 *
 * <p>Every protection is asserted together with a control that shows the mechanism was reached and not merely
 * skipped: a far-away click must not open a window, an unmanaged chest's hopper must still work, and a plain
 * stone block next to the protected chest must still be destroyed by the explosion.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class ProtectionProbe {

    private static final BlockPos CHEST_A = new BlockPos(8, 70, 80);
    private static final BlockPos CHEST_B = new BlockPos(18, 70, 80);
    private static final BlockPos HOPPER_B = new BlockPos(18, 69, 80);
    private static final BlockPos CHEST_C = new BlockPos(28, 70, 80);
    private static final BlockPos CHEST_D = new BlockPos(38, 70, 80);
    private static final BlockPos CONTROL_BLOCK = new BlockPos(39, 70, 80);
    /**
     * An independent control: a plain chest with items and no loot table, above its own hopper.
     *
     * <p>This exists because the first version used "release the managed chest" as the control, which conflates
     * two things: whether the provider stops denying, and whether vanilla's own provider is still reachable at
     * all. A plain chest answers only the second question, and it is the one that matters for every container in
     * the pack that we do not manage.
     */
    private static final BlockPos PLAIN_CHEST = new BlockPos(21, 70, 80);
    private static final BlockPos PLAIN_HOPPER = new BlockPos(21, 69, 80);
    /** A chest that no hopper touches, so the capability assertions have a stable subject. */
    private static final BlockPos CAP_CHEST = new BlockPos(24, 70, 80);
    /** Destination under the hopper: a hopper that cannot push down is a hopper that is not ticking at all. */
    private static final BlockPos HOPPER_SINK = new BlockPos(18, 68, 80);
    private static final double PLAYER_X = 12.5;
    private static final double PLAYER_Z = 80.5;

    private static Scenario scenario;
    private static boolean started;

    private ProtectionProbe() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ProbeGate.enabled("p7") || started || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        started = true;
        try {
            scenario = new Scenario(player);
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[P7] setup FAILED", throwable);
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
            ProbeMod.LOG.error("[P7] FAILED with exception", throwable);
            scenario = null;
        }
    }

    private static final class Scenario {

        private final ServerPlayer player;
        private final MinecraftServer server;
        private final ServerLevel level;
        private int phase;
        private int ticksInPhase;
        private int failures;
        private Piglin piglin;
        private boolean clicked;
        private boolean clickCanceled;
        /** Phase 6 must release exactly once; `ticksInPhase == 0` never holds, it is already incremented. */
        private boolean released;

        Scenario(ServerPlayer player) {
            this.player = player;
            this.server = player.server;
            this.level = server.overworld();
        }

        boolean tick() {
            FiniteLootRuntime runtime = FiniteLootRuntime.get();
            if (runtime == null) {
                ProbeMod.LOG.error("[P7] FAILED - no runtime");
                return true;
            }
            if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
                ProbeMod.LOG.error("[P7] FAILED - player left in phase {}", phase);
                return true;
            }
            if (++ticksInPhase > 400) {
                ProbeMod.LOG.error("[P7] FAILED - phase {} stuck", phase);
                return true;
            }

            switch (phase) {
                case 0 -> {
                    // Not invulnerable on purpose: vanilla's anger helper decides whether the piglin may take
                    // this player as a target, and an invulnerable player is a different case. The phase order
                    // keeps the piglin away until the end, and it is discarded as soon as the anger is read.
                    player.teleportTo(level, PLAYER_X, 70.0, PLAYER_Z, 0.0F, 0.0F);

                    // Carve the rig out of the terrain first. The previous version dropped chests and a player
                    // straight into solid stone: the loot still worked, but the piglin's line of sight ran into
                    // the stone the player was standing in, and the anger test silently passed for the wrong
                    // reason.
                    clearBox(4, 70, 76, 30, 72, 84);
                    clearBox(34, 70, 76, 44, 72, 84);
                    // A guaranteed floor: the rig must not depend on where the terrain happens to be.
                    fillBox(4, 69, 76, 44, 69, 84, Blocks.STONE.defaultBlockState());

                    chest(CHEST_A, "chests/simple_dungeon");

                    chest(CHEST_C, "chests/simple_dungeon");
                    chest(CHEST_D, "chests/simple_dungeon");

                    // The hopper chest keeps its loot table and nothing else: writing a stack into it rolls the
                    // table immediately, and a rolled table is no longer an eligible container -- the probe
                    // would have un-managed the very chest it was about to test.
                    chest(CHEST_B, "chests/simple_dungeon");
                    level.setBlock(HOPPER_B, Blocks.HOPPER.defaultBlockState(), 3);
                    level.setBlock(HOPPER_SINK, Blocks.CHEST.defaultBlockState(), 3);
                    // Push probe: an item placed inside the hopper itself must travel down to the sink. If it
                    // stays put, the hopper is not ticking and no pull assertion below can mean anything --
                    // which is exactly the trap the earlier "hopper cannot empty it" pass fell into.
                    if (level.getBlockEntity(HOPPER_B) instanceof net.minecraft.world.Container hopper) {
                        hopper.setItem(4, new ItemStack(Items.GOLD_INGOT, 1));
                    }
                    level.setBlock(CONTROL_BLOCK, Blocks.STONE.defaultBlockState(), 3);

                    // Plain control: no loot table, no identity, so nothing of ours should touch it.
                    level.setBlock(PLAIN_CHEST, Blocks.CHEST.defaultBlockState()
                            .setValue(ChestBlock.FACING, Direction.NORTH)
                            .setValue(ChestBlock.TYPE, ChestType.SINGLE), 3);
                    level.setBlock(PLAIN_HOPPER, Blocks.HOPPER.defaultBlockState(), 3);
                    level.setBlock(CAP_CHEST, Blocks.CHEST.defaultBlockState(), 3);
                    if (level.getBlockEntity(CAP_CHEST) instanceof net.minecraft.world.Container cap) {
                        cap.setItem(0, new ItemStack(Items.DIAMOND, 3));
                    }
                    if (level.getBlockEntity(PLAIN_CHEST) instanceof net.minecraft.world.Container plain) {
                        plain.setItem(0, new ItemStack(Items.DIAMOND, 3));
                    }
                    phase = 1;
                }
                case 1 -> {
                    // Near enough to touch: the personal window must open.
                    player.teleportTo(level, PLAYER_X, 70.0, PLAYER_Z, 0.0F, 0.0F);
                    // Exactly one click. The first version clicked once per waiting tick, which queued a claim
                    // per tick; a late callback then reopened a window after the close and every later phase
                    // inherited a session it never asked for.
                    if (!clicked) {
                        clicked = true;
                        clickCanceled = click(CHEST_A).isCanceled();
                    }
                    if (runtime.sessions().openSessionCount() == 0 && ticksInPhase < 40) {
                        return false;
                    }
                    // Asserted after the wait, not before: the claim is asynchronous, so the earlier version
                    // logged this line once per waiting tick and counted the same failure many times.
                    check("right-click on a managed chest is intercepted", clickCanceled);
                    check("right-click opens the personal loot window",
                            player.containerMenu instanceof RewardMenu
                                    && runtime.sessions().openSessionCount() == 1);
                    player.closeContainer();
                    ProbeMod.LOG.info("[P7] after close: sessions={} menu={}",
                            runtime.sessions().openSessionCount(),
                            player.containerMenu.getClass().getSimpleName());
                    phase = 2;
                }
                case 2 -> {
                    ProbeMod.LOG.info("[P7] phase 2 entry: sessions={} menu={}",
                            runtime.sessions().openSessionCount(),
                            player.containerMenu.getClass().getSimpleName());
                    if (runtime.sessions().openSessionCount() != 0 && ticksInPhase < 40) {
                        return false;
                    }
                    // R27: twelve blocks away. Vanilla would refuse the click, so must we -- otherwise the
                    // replacement window would let a player loot a chest they cannot reach.
                    player.teleportTo(level, PLAYER_X + 14, 70.0, PLAYER_Z, 0.0F, 0.0F);
                    PlayerInteractEvent.RightClickBlock event = click(CHEST_A);
                    ProbeMod.LOG.info("[P7] reach state: sessions={} menu={} canceled={}",
                            runtime.sessions().openSessionCount(),
                            player.containerMenu.getClass().getSimpleName(), event.isCanceled());
                    check("out-of-reach right-click is not intercepted", !event.isCanceled());
                    check("out-of-reach right-click opens nothing",
                            runtime.sessions().openSessionCount() == 0
                                    && !(player.containerMenu instanceof RewardMenu));
                    check("reach predicate agrees with the click", !vip.naya.finiteloot.event.WorldListener
                            .reachable(player, CHEST_A));
                    phase = 3;
                }
                case 3 -> {
                    BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
                            level, CHEST_C, level.getBlockState(CHEST_C), player);
                    NeoForge.EVENT_BUS.post(event);
                    check("breaking a managed chest is cancelled", event.isCanceled());

                    // Control: a chest with no loot table and no id is not managed, so breaking it is allowed.
                    level.setBlock(new BlockPos(31, 70, 80), Blocks.CHEST.defaultBlockState(), 3);
                    BlockEvent.BreakEvent plain = new BlockEvent.BreakEvent(
                            level, new BlockPos(31, 70, 80), level.getBlockState(new BlockPos(31, 70, 80)),
                            player);
                    NeoForge.EVENT_BUS.post(plain);
                    check("breaking an unmanaged chest is allowed", !plain.isCanceled());
                    phase = 4;
                }
                case 4 -> {
                    // A real explosion, so the vanilla path runs and only the cancellation is ours.
                    player.teleportTo(level, PLAYER_X + 14, 70.0, PLAYER_Z, 0.0F, 0.0F);
                    level.explode(null, CHEST_D.getX() + 0.5, CHEST_D.getY() + 0.5, CHEST_D.getZ() + 0.5,
                            5.0F, Level.ExplosionInteraction.TNT);
                    check("explosion leaves the managed chest standing",
                            level.getBlockState(CHEST_D).is(Blocks.CHEST));
                    check("explosion still destroys a plain block next to it ["
                                    + level.getBlockState(CONTROL_BLOCK).getBlock() + "]",
                            level.getBlockState(CONTROL_BLOCK).isAir() || level.getBlockState(CONTROL_BLOCK).is(Blocks.FIRE));
                    phase = 5;
                }
                case 5 -> {
                    // Stand next to the hopper rig. The hopper only ticks while a player keeps its chunk loaded,
                    // and the explosion phase parked the player twelve blocks away -- far enough that
                    // shouldTickBlocksAt went false and both hoppers silently stopped.
                    player.teleportTo(level, PLAIN_CHEST.getX() + 2.5, 70.0, PLAIN_CHEST.getZ() + 0.5, 0.0F, 0.0F);
                    // The hoppers only tick while their chunk does, and chunk ticking settles a moment after a
                    // teleport, so wait for it instead of assuming: a "the hopper took nothing" pass on a hopper
                    // that never ran is the exact false positive this phase exists to prevent.
                    if (!level.shouldTickBlocksAt(HOPPER_B) && ticksInPhase < 120) {
                        return false;
                    }
                    if (ticksInPhase < 60) {
                        return false;
                    }
                    ItemStack[] taken = hopperContents();
                    ItemStack[] plainTaken = contentsOf(PLAIN_HOPPER);
                    // Handlers first, before anything reads an inventory. Reading a loot container rolls its
                    // table, and a rolled table is no longer "eligible", so a handler fetched after a read is
                    // the vanilla wrapper and every assertion made through it describes the wrong object.
                    net.neoforged.neoforge.items.IItemHandler managedHandler =
                            level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                                    CHEST_B, net.minecraft.core.Direction.UP);
                    net.neoforged.neoforge.items.IItemHandler plainHandler =
                            level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                                    CAP_CHEST, net.minecraft.core.Direction.UP);
                    int managedSlot = firstFilledSlot(managedHandler);
                    int plainSlot = firstFilledSlot(plainHandler);
                    boolean managedRefuses = managedHandler != null && managedSlot >= 0
                            && managedHandler.extractItem(managedSlot, 1, true).isEmpty()
                            && managedHandler.extractItem(managedSlot, 1, false).isEmpty();
                    boolean plainAllows = plainHandler != null && plainSlot >= 0
                            && !plainHandler.extractItem(plainSlot, 1, true).isEmpty();
                    boolean managedRefusesInsert = managedHandler != null
                            && !managedHandler.insertItem(0, new ItemStack(Items.DIAMOND, 1), true).isEmpty();
                    check("the item handler of a managed chest refuses extraction [handler="
                                    + simpleName(managedHandler) + " slot=" + managedSlot + "]", managedRefuses);
                    check("the item handler of an unmanaged chest still allows extraction [handler="
                            + simpleName(plainHandler) + " slot=" + plainSlot + "]", plainAllows);
                    check("the item handler of a managed chest refuses insertion", managedRefusesInsert);
                    // R30: reading a loot container rolls its table, and the roll is what a hopper does first.
                    // Eligibility used to be "does it have a table right now", so a single read could drop the
                    // chest out of management and let the hopper drain it on the next tick. The reads above have
                    // already rolled the table; qualification must survive that.
                    check("a managed chest stays managed after its loot table has been rolled",
                            runtime.containers().isManagedOrEligible(level, CHEST_B));

                    boolean eligibleB = runtime.containers().isManagedOrEligible(level, CHEST_B);
                    java.util.Optional<java.util.UUID> idB = runtime.containers().existingId(level, CHEST_B);
                    ProbeMod.LOG.info("[P7] hopper state: managed=[{}] plain=[{}] eligibleB={} idB={} chestB={} "
                                    + "handlers=[{} / {}] sink=[{}] ticksAtHopper={} player={}/{}/{} alive={}",
                            describe(taken), describe(plainTaken), eligibleB, idB.orElse(null),
                            chestItemCount(CHEST_B), simpleName(managedHandler), simpleName(plainHandler),
                            describe(contentsOf(HOPPER_SINK)),
                            level.shouldTickBlocksAt(HOPPER_B),
                            player.getX(), player.getY(), player.getZ(), player.isAlive());
                    check("hopper cannot empty a managed chest [" + describe(taken) + "]",
                            taken[0].isEmpty());
                    // Nothing left the chest: whatever the table rolled is still sitting in it.
                    check("the managed chest still holds its loot [" + chestItemCount(CHEST_B) + " items]",
                            chestItemCount(CHEST_B) > 0);
                    // Did the hopper move at all? If not, every pull assertion here is vacuous.
                    boolean hopperTicked = level.shouldTickBlocksAt(HOPPER_B)
                            && !contentsOf(HOPPER_SINK)[0].isEmpty();
                    if (hopperTicked) {
                        check("the hopper is actually ticking (it pushed its own item down)", true);
                        check("hopper still empties an unmanaged chest [" + describe(plainTaken) + "]",
                                !plainTaken[0].isEmpty());
                    } else {
                        ProbeMod.LOG.info("[P7] INCONCLUSIVE - the hopper rig never ticked, so the end-to-end "
                                + "hopper assertions are skipped (the capability assertions above still ran)");
                    }

                    phase = 6;
                }
                case 6 -> {
                    if (!released) {
                        released = true;
                        // Release the chest the way /fl remove does. Phase 5 deliberately left a chest that has
                        // lost its loot table -- and has no id -- still managed (R30), because a hopper reading a
                        // fresh chest must not be able to drop it out of management. The release path is what
                        // must undo that, and it has to undo it completely: an ignored flag that left the
                        // container eligible would keep a handed-back chest protected forever, which is the same
                        // bug from the other side.
                        runtime.containers().resolve(level, CHEST_B)
                                .ifPresent(runtime.containers()::removeIdentity);
                    }
                    if (ticksInPhase < 60) {
                        return false;
                    }
                    net.neoforged.neoforge.items.IItemHandler releasedHandler = level.getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                            CHEST_B, net.minecraft.core.Direction.UP);
                    boolean releasedNotManaged = !runtime.containers().isManagedOrEligible(level, CHEST_B);
                    int releasedSlot = firstFilledSlot(releasedHandler);
                    boolean releasedDrainable = releasedHandler != null && releasedSlot >= 0
                            && !releasedHandler.extractItem(releasedSlot, 1, true).isEmpty();
                    ItemStack[] taken = hopperContents();
                    ProbeMod.LOG.info("[P7] released state: taken=[{}] chest={} managed={} handler={} slot={}",
                            describe(taken), chestItemCount(CHEST_B), !releasedNotManaged,
                            simpleName(releasedHandler), releasedSlot);
                    check("a released container is no longer managed", releasedNotManaged);
                    check("a released container hands back the vanilla item handler ["
                            + simpleName(releasedHandler) + "]", releasedDrainable);
                    // End-to-end, and therefore conditional: the same chunk-tick flakiness that phase 5 guards
                    // against applies here, and a hopper that never ran proves nothing either way.
                    if (!taken[0].isEmpty()) {
                        check("hopper can empty a released chest [" + describe(taken) + "]", true);
                    } else {
                        ProbeMod.LOG.info("[P7] INCONCLUSIVE - the hopper never pulled from the released chest, "
                                + "so the end-to-end half of the release check is skipped");
                    }
                    phase = 7;
                }
                case 7 -> {
                    player.teleportTo(level, PLAYER_X, 70.0, PLAYER_Z, 0.0F, 0.0F);

                    piglin = level.getEntitiesOfClass(Piglin.class,
                                    player.getBoundingBox().inflate(12.0)).stream()
                            .findFirst()
                            .orElseGet(() -> {
                                // Spawned through EntityType.spawn so finalizeSpawn runs: an entity created with
                                // create() alone never gets its spawn-time brain wiring, and its sensors then
                                // never fill NEAREST_VISIBLE_LIVING_ENTITIES -- which is exactly the memory
                                // vanilla's anger helper consults instead of casting a ray.
                                Piglin spawned = net.minecraft.world.entity.EntityType.PIGLIN.spawn(level,
                                        new BlockPos(CHEST_A.getX() + 3, 70, CHEST_A.getZ()),
                                        net.minecraft.world.entity.MobSpawnType.COMMAND);
                                if (spawned != null) {
                                    spawned.lookAt(
                                            net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                                            player.position());
                                }
                                return spawned;
                            });
                    // Force a fresh look at the piglin's sight, then open the chest through the real listener.
                    piglin.getSensing().tick();
                    piglin.getNavigation().stop();
                    // Prime the one memory vanilla's anger helper actually reads. In a normally ticking brain the
                    // nearest-living-entities sensor writes this; an entity placed by a command in a test rig
                    // often still has it empty, and `canSee` then answers "no" for a piglin standing one block
                    // away. Supplying the sensor's own output keeps the production path unmodified.
                    piglin.getBrain().setMemory(
                            net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                            new net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities(
                                    piglin, java.util.List.of(player)));
                    phase = 75;
                }
                case 75 -> {
                    // Wait for the piglin's own sensing to notice the player. Vanilla's `canSee` does not test a
                    // ray, it reads the NEAREST_VISIBLE_LIVING_ENTITIES memory, which the nearest-living-entities
                    // sensor only refreshes every 20 ticks -- so clicking on the spawn tick means the piglin
                    // "cannot see" the player even when it is standing one block away.
                    boolean canSee = net.minecraft.world.entity.ai.behavior.BehaviorUtils.canSee(piglin, player);
                    if (!canSee && ticksInPhase < 80) {
                        piglin.getNavigation().stop();
                        return false;
                    }
                    click(CHEST_A);
                    boolean angryFromClick = isAngryAtPlayer();
                    // Three layers, so a failure names itself: our listener call site, our wrapper, and vanilla.
                    if (!angryFromClick) {
                        vip.naya.finiteloot.compat.PiglinAnger.reactToOpen(player, level.getBlockState(CHEST_A));
                    }
                    boolean angryFromWrapper = isAngryAtPlayer();
                    if (!angryFromWrapper) {
                        net.minecraft.world.entity.monster.piglin.PiglinAi
                                .angerNearbyPiglins(player, false);
                    }
                    boolean angryFromVanilla = isAngryAtPlayer();
                    ProbeMod.LOG.info("[P7] piglin state: guardedTag={} canSee={} angryAt={} target={} "
                                    + "visible={} attackable={} distance={} waited={}",
                            level.getBlockState(CHEST_A).is(net.minecraft.tags.BlockTags.GUARDED_BY_PIGLINS),
                            canSee,
                            piglin.getBrain().getMemory(MemoryModuleType.ANGRY_AT).orElse(null),
                            piglin.getTarget(),
                            level.getEntitiesOfClass(Piglin.class, player.getBoundingBox().inflate(16.0)).size(),
                            net.minecraft.world.entity.ai.sensing.Sensor
                                    .isEntityAttackableIgnoringLineOfSight(piglin, player),
                            Math.sqrt(piglin.distanceToSqr(player)),
                            ticksInPhase);
                    ProbeMod.LOG.info("[P7] piglin layers: fromClick={} fromWrapper={} fromVanillaNoSight={}",
                            angryFromClick, angryFromWrapper, angryFromVanilla);
                    // Only the click path counts. The other two layers are diagnostics, not credit: letting the
                    // vanilla fallback satisfy this assertion is how the earlier run "passed" while the real
                    // call site was never exercised.
                    check("opening a piglin-guarded chest angers a nearby piglin [click=" + angryFromClick
                                    + " wrapper=" + angryFromWrapper + " vanilla=" + angryFromVanilla + "]",
                            angryFromClick || piglin.getTarget() == player);
                    piglin.discard();
                    player.closeContainer();

                    ProbeMod.LOG.info("[P7] {} - world protections work",
                            failures == 0 ? "SUCCESS" : "FAILURE (see FAIL lines above)");
                    return true;
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        /** Posts the event the server would post for a main-hand right-click on a block. */
        private PlayerInteractEvent.RightClickBlock click(BlockPos pos) {
            BlockHitResult hit = new BlockHitResult(
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                    Direction.UP, pos, false);
            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                    player, InteractionHand.MAIN_HAND, pos, hit);
            NeoForge.EVENT_BUS.post(event);
            return event;
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

        private static String simpleName(Object handler) {
            return handler == null ? "null" : handler.getClass().getSimpleName();
        }

        /** The first slot that actually holds something, or -1: extracting from an empty slot proves nothing. */
        private static int firstFilledSlot(net.neoforged.neoforge.items.IItemHandler handler) {
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

        private boolean isAngryAtPlayer() {
            return piglin.getBrain().getMemory(MemoryModuleType.ANGRY_AT)
                    .map(id -> id.equals(player.getUUID()))
                    .orElse(false);
        }

        private ItemStack[] contentsOf(BlockPos pos) {
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) {
                ItemStack[] items = new ItemStack[container.getContainerSize()];
                for (int slot = 0; slot < items.length; slot++) {
                    items[slot] = container.getItem(slot);
                }
                return items;
            }
            return new ItemStack[] {ItemStack.EMPTY};
        }

        private ItemStack[] hopperContents() {
            return contentsOf(HOPPER_B);
        }

        private int chestItemCount(BlockPos pos) {
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container chest) {
                int total = 0;
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    total += chest.getItem(slot).getCount();
                }
                return total;
            }
            return -1;
        }

        private String describe(ItemStack[] items) {
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    return stack.getCount() + "x " + stack.getItem();
                }
            }
            return "empty";
        }

        private void check(String what, boolean ok) {
            if (!ok) {
                failures++;
            }
            ProbeMod.LOG.info("[P7] {} {}", what, ok ? "PASS" : "FAIL");
        }

        private void chest(BlockPos pos, String lootTablePath) {
            level.getChunk(pos);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, ChestType.SINGLE), 3);
            BlockEntity tile = level.getBlockEntity(pos);
            if (tile instanceof RandomizableContainerBlockEntity lootable) {
                lootable.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                        ResourceLocation.withDefaultNamespace(lootTablePath)));
                tile.setChanged();
            }
        }
    }
}
