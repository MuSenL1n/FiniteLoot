package vip.naya.finiteloot.p0.gui;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * Opens the P0-2 virtual container automatically when a player joins, so the check needs no human at
 * the keyboard.
 *
 * <p>P0-2 previously could only be exercised by a real player typing {@code /p0 gui}. That made it the
 * one P0 item that could not be verified in an automated run: a protocol-level client can reach PLAY,
 * but it cannot easily issue a command. Opening the menu on join inverts the dependency -- the server
 * pushes the screen, and the probe client simply observes the resulting packet.
 *
 * <p>This also happens to mirror what the real port does on a player's first container interaction,
 * so it exercises the same code path.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class GuiAutoOpen {

    /** Guards against re-opening in a loop if a join event is ever re-fired during this session. */
    private static boolean openedOnce;

    private GuiAutoOpen() {
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p0")) {
            return;
        }
        if (openedOnce) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        openedOnce = true;
        // One tick later: the client is not yet in a state to accept a screen during the join event
        // itself, and sending too early is a classic source of "screen did not open" false negatives.
        player.server.execute(() -> {
            try {
                GuiProbe.open(player, 3);
                ProbeMod.LOG.info("[P0-2] auto-opened virtual container for {} on join", player.getName().getString());
            } catch (Throwable t) {
                ProbeMod.LOG.error("[P0-2] auto-open failed", t);
            }
        });
    }
}
