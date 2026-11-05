package vip.naya.finiteloot.container;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ContainerIdentity {
    private ContainerIdentity() {
    }

    public static UUID normalize(Collection<UUID> existing, Supplier<UUID> generator) {
        return existing.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(generator);
    }
}

