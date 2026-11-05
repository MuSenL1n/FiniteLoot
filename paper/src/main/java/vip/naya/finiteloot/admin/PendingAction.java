package vip.naya.finiteloot.admin;

import java.util.UUID;

public record PendingAction(Type type, Integer maxClaims, UUID targetPlayer) {
    public enum Type {
        INSPECT,
        SET,
        RESET_CONTAINER,
        RESET_PLAYER,
        REMOVE
    }

    public static PendingAction simple(Type type) {
        return new PendingAction(type, null, null);
    }
}

