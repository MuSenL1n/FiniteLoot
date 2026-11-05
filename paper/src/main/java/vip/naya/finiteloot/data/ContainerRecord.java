package vip.naya.finiteloot.data;

import java.util.UUID;

public record ContainerRecord(
        UUID id,
        UUID worldId,
        int x,
        int y,
        int z,
        Integer partnerX,
        Integer partnerY,
        Integer partnerZ,
        String lootTable,
        byte[] templateContents,
        int maxClaims,
        int claimCount,
        boolean manual) {
}

