package vip.naya.finiteloot.container;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExhaustedContainers {
    private final Set<UUID> ids = ConcurrentHashMap.newKeySet();

    public void markExhausted(UUID id) {
        ids.add(id);
    }

    public void markAllExhausted(Collection<UUID> exhausted) {
        ids.addAll(exhausted);
    }

    public void clear(UUID id) {
        ids.remove(id);
    }

    public boolean isExhausted(UUID id) {
        return ids.contains(id);
    }
}
