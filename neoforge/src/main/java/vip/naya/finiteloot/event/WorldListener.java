package vip.naya.finiteloot.event;

import java.util.Iterator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import vip.naya.finiteloot.FiniteLootRuntime;
import vip.naya.finiteloot.compat.PiglinAnger;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.gui.ContainerPresentation;

import static vip.naya.finiteloot.p0.ProbeMod.LOG;
import static vip.naya.finiteloot.p0.ProbeMod.MOD_ID;

/**
 * The world-facing half of the mod: what happens when a player right-clicks, breaks, or blows up a container.
 *
 * <p>Port of Paper's {@code WorldListener}, with two structural differences:
 *
 * <ul>
 *   <li>Paper's listener owned {@code ClaimController} and {@code AdminActions} directly. Here everything is read
 *       from {@link FiniteLootRuntime}, because events fire whether or not the runtime is up (a broken config
 *       leaves the mod inert), and a listener holding instances would have to invent a lifecycle for them.
 *   <li>The reach check (R27) did not exist in Paper, because Paper only cancelled the interaction and let
 *       Bukkit's own rules apply. Here the container is replaced by a menu we open, so the reach vanilla would
 *       have enforced has to be enforced by us. {@code ChestMenu.stillValid} is an eight-block check, not the
 *       player's actual reach, so it is not a substitute.
 * </ul>
 *
 * <p>Priority is {@link EventPriority#HIGHEST} and already-cancelled events are ignored, matching Paper: another
 * plugin (or mod) that refuses the interaction should win, and one that allows it should not be able to override
 * the protection.
 */
@EventBusSubscriber(modid = MOD_ID)
public final class WorldListener {

    private WorldListener() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FiniteLootRuntime runtime = FiniteLootRuntime.get();
        if (runtime == null) {
            return;
        }
        BlockPos pos = event.getPos();

        // An armed administrative action takes the click before anything else: the admin is aiming at this
        // container, not trying to loot it.
        if (runtime.admin().hasPending(player)
                && runtime.admin().handleClick(player, level, pos)) {
            deny(event);
            return;
        }
        if (runtime.settings().get().isWorldExcluded(level.dimension().location().toString())
                || runtime.settings().get().isWorldExcluded(legacyWorldName(level))) {
            return;
        }
        if (!withinReach(player, pos)) {
            // Vanilla would have refused this click too. Doing nothing keeps the two paths identical: no claim,
            // no replacement window, no message about a container the player cannot actually touch.
            return;
        }

        runtime.containers().resolve(level, pos).ifPresent(target -> {
            deny(event);
            open(runtime, player, level, target);
        });
    }

    private static void open(FiniteLootRuntime runtime, ServerPlayer player, ServerLevel level,
            ContainerTarget target) {
        if (runtime.settings().get().playContainerAnimation()
                || runtime.settings().get().playContainerSounds()) {
            ContainerPresentation presentation = ContainerPresentation.of(target);
            if (runtime.settings().get().playContainerAnimation()) {
                presentation.animate(level, true);
            }
            if (runtime.settings().get().playContainerSounds()) {
                presentation.playSound(level, true);
            }
        }
        if (runtime.settings().get().triggerPiglinAnger()) {
            PiglinAnger.reactToOpen(player, level.getBlockState(target.tiles().getFirst().getBlockPos()));
        }
        runtime.claims().open(player, target);
    }

    /**
     * Cancelling is not enough on its own: a cancelled right-click on a chest still lets the client start the
     * "block is being used" animation, and more importantly {@code setUseBlock(DENY)} is what stops the vanilla
     * container from opening on the server. Paper's {@code setCancelled} covered both because Bukkit had a single
     * flag; NeoForge splits it.
     */
    private static void deny(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    /**
     * True when the player is close enough to touch the block.
     *
     * <p>Uses the entity's own block interaction range (attribute-driven: 4.5 blocks in survival, 5.5 in creative,
     * extendable by mods) rather than a constant, so a reach modifier behaves the same here as it does in vanilla.
     */
    private static boolean withinReach(ServerPlayer player, BlockPos pos) {
        // The range is an attribute in 1.21.1 (4.5 survival, 5.5 creative) rather than a constant, so a reach
        // modifier from another mod or a datapack behaves here exactly as it does for a vanilla container.
        double range = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= range * range;
    }

    /**
     * Paper's {@code excluded-worlds} entries were Bukkit world names ({@code world_nether}); the mod also accepts
     * registry keys ({@code minecraft:the_nether}). Both are checked so an existing config keeps working, which is
     * the same rule {@code ContainerManager.isExcluded} already applies.
     */
    private static String legacyWorldName(ServerLevel level) {
        return level.dimension().location().getPath();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        FiniteLootRuntime runtime = FiniteLootRuntime.get();
        if (runtime == null || !runtime.settings().get().preventBreaking()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!runtime.containers().isManagedOrEligible(level, event.getPos())) {
            return;
        }
        // An exhausted container can be broken open if the config says so -- that path releases it first, so the
        // block drops as a normal chest instead of vanishing.
        if (runtime.claims().releaseExhaustedContainer(level, event.getPos())) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        FiniteLootRuntime runtime = FiniteLootRuntime.get();
        if (runtime == null || !runtime.settings().get().preventExplosions()) {
            return;
        }
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        List<BlockPos> affected = event.getAffectedBlocks();
        int removed = 0;
        Iterator<BlockPos> iterator = affected.iterator();
        while (iterator.hasNext()) {
            if (runtime.containers().isManagedOrEligible(serverLevel, iterator.next())) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOG.debug("[EXPLOSION] protected {} container(s) from an explosion in {}", removed, serverLevel.dimension().location());
        }
    }

    /** Kept for the probe: the same predicate the interaction path uses, so a test can assert it directly. */
    public static boolean reachable(ServerPlayer player, BlockPos pos) {
        return withinReach(player, pos);
    }

    /** Kept for the probe: the state the piglin check keys on. */
    public static BlockState guardedState(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos);
    }
}
