package vip.naya.finiteloot.claim;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import vip.naya.finiteloot.container.ContainerTarget;

/**
 * The seam between "decide what should happen" and "show it to the player".
 *
 * <p>In the Paper original the {@code ClaimController} talked to a {@code RewardSessionManager} that owned
 * the personal-inventory GUI, so the state machine and the presentation were welded together. Splitting
 * them lets the claim flow be exercised in P4 with a recording presenter, and lets the real GUI arrive in
 * P5 without touching the state machine or the database order.
 *
 * <p>Everything here runs on the server thread.
 */
public interface RewardPresenter {

    /** Show the player the loot stored for them. */
    void openPersonal(ServerPlayer player, UUID containerId, ItemStack[] contents, ContainerTarget target);

    /** Show the real container as an ordinary chest. */
    void openNormal(ServerPlayer player, ContainerTarget target);

    /** Close any personal inventory currently open for this container, discarding nothing. */
    void closeContainer(UUID containerId);

    /**
     * Close every personal inventory for this container and flush what is inside them.
     *
     * <p>Returns a future because the flush writes to the database. The caller waits for it before handing
     * the container back to vanilla -- otherwise items a player moved a moment earlier could be lost when
     * the inventory is replaced.
     */
    CompletableFuture<Void> closeContainerAndSave(UUID containerId);
}
