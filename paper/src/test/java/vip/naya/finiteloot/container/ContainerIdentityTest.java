package vip.naya.finiteloot.container;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerIdentityTest {
    @Test
    void bothDoubleChestHalvesUseExistingIdentity() {
        UUID existing = UUID.randomUUID();
        UUID generated = UUID.randomUUID();
        assertEquals(existing, ContainerIdentity.normalize(List.of(existing), () -> generated));
    }

    @Test
    void newDoubleChestGetsOneGeneratedIdentity() {
        UUID generated = UUID.randomUUID();
        assertEquals(generated, ContainerIdentity.normalize(List.of(), () -> generated));
    }
}

