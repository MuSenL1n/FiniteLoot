package vip.naya.finiteloot.command;

import java.util.UUID;

/**
 * An administrative action waiting for the admin to right-click a container.
 *
 * <p>Ported unchanged from Paper, with one addition: {@link Type#ADOPT}, which has no Paper equivalent.
 * Paper released a container by clearing its identity, and had no way back; here the identity is replaced by
 * an "ignored" flag, and `adopt` is the deliberate undo.
 *
 * <p>The arm-then-click shape was Paper's and is kept because it is genuinely better than coordinates: the
 * admin is already looking at the container, and there is no way to mistype a position into the wrong chest.
 */
public record PendingAction(Type type, Integer maxClaims, UUID targetPlayer) {

    public enum Type {
        INSPECT,
        SET,
        RESET_CONTAINER,
        RESET_PLAYER,
        REMOVE,
        ADOPT
    }

    public static PendingAction simple(Type type) {
        return new PendingAction(type, null, null);
    }
}
