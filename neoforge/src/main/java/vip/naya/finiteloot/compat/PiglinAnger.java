package vip.naya.finiteloot.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes opening a bastion-type container anger the piglins, as the real container would.
 *
 * <p>This is a deliberate simplification of Paper's {@code PiglinAngerService}, which was ~170 lines: it kept a
 * lease per piglin, rolled a random 20-39 second anger duration, re-validated on expiry, and cleaned up when
 * entities unloaded. Decision D1 was to use vanilla's own reaction instead, for three reasons:
 *
 * <ul>
 *   <li>{@code PiglinAi.angerNearbyPiglins} is the call the vanilla container makes. Whatever players expect from
 *       piglins and containers, this is that behaviour -- including the universal-anger gamerule, line of sight,
 *       and which piglins are eligible. Re-implementing it meant re-deciding all of that, and Paper's version had
 *       already drifted: its lease expiry could clear anger that vanilla would have kept.
 *   <li>The anger then lives in the piglin's own memory and decays the way the game decays it. A lease stored in
 *       our process would be lost on a restart, leaving an angry piglin nobody tracks.
 *   <li>It is one call. The port has no business owning a scheduler for something the game already schedules.
 * </ul>
 *
 * <p>What is lost: Paper only angered piglins that were idle, unaware, not already angry, and had line of sight,
 * and under universal anger it could pick a <em>different</em> nearby player. Vanilla applies its own filters and
 * always targets the opener. If a pack needs Paper's exact selection, that is a conscious feature request rather
 * than an oversight.
 *
 * <p>The tag is {@code BlockTags.GUARDED_BY_PIGLINS}, the same check the vanilla container uses, so datapacks that
 * extend it are honoured without a config key.
 */
public final class PiglinAnger {

    private PiglinAnger() {
    }

    public static void reactToOpen(ServerPlayer opener, BlockState state) {
        if (!state.is(BlockTags.GUARDED_BY_PIGLINS)) {
            return;
        }
        // The boolean is "only if the piglin can see the player", which is how vanilla does it: a piglin on the
        // other side of a wall does not notice a chest opening.
        PiglinAi.angerNearbyPiglins(opener, true);
    }
}
