package vip.naya.finiteloot.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The two platform-free pieces of the container layer, which is where the double-container id
 * normalisation and the exhaustion cache actually live.
 *
 * <p>{@code ContainerManager} itself needs a live server and is verified by the P3 probe; these two
 * classes are plain Java and are tested here so a regression shows up in a second rather than in a
 * server run.
 */
class ContainerIdentityTest {

    @Test
    void keepsTheFirstNonNullId() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicInteger generated = new AtomicInteger();
        UUID result = ContainerIdentity.normalize(List.of(first, second), () -> {
            generated.incrementAndGet();
            return UUID.randomUUID();
        });
        assertEquals(first, result);
        assertEquals(0, generated.get(), "an existing id must not trigger generation");
    }

    @Test
    void skipsNullsInTheCollection() {
        UUID actual = UUID.randomUUID();
        UUID result = ContainerIdentity.normalize(Arrays.asList(null, actual), UUID::randomUUID);
        assertEquals(actual, result);
    }

    @Test
    void generatesWhenNothingExists() {
        UUID generated = UUID.randomUUID();
        UUID result = ContainerIdentity.normalize(List.of(), () -> generated);
        assertEquals(generated, result);
    }

    @Test
    void generatesWhenOnlyNullsExist() {
        UUID generated = UUID.randomUUID();
        assertEquals(generated, ContainerIdentity.normalize(Arrays.asList(null, null), () -> generated));
    }

    @Test
    void doubleChestMergingIsOrderIndependent() {
        // A double chest has two halves; whichever half is read first must produce the same id, or the
        // two halves could disagree and the chest could be claimed twice.
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        UUID fromLeft = ContainerIdentity.normalize(Arrays.asList(left, right), UUID::randomUUID);
        UUID fromRight = ContainerIdentity.normalize(Arrays.asList(left, right), UUID::randomUUID);
        assertEquals(fromLeft, fromRight);
        assertEquals(left, fromLeft, "the deterministic ordering of the pair is what makes this stable");
    }

    @Test
    void exhaustedSetIsIdempotentAndPerId() {
        ExhaustedContainers exhausted = new ExhaustedContainers();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertFalse(exhausted.isExhausted(a));
        exhausted.markExhausted(a);
        exhausted.markExhausted(a);
        exhausted.markAllExhausted(List.of(a, b));

        assertTrue(exhausted.isExhausted(a));
        assertTrue(exhausted.isExhausted(b));

        exhausted.clear(a);
        assertFalse(exhausted.isExhausted(a), "clearing one id must not clear another");
        assertTrue(exhausted.isExhausted(b));
    }

    @Test
    void exhaustedSetSurvivesConcurrentMutation() throws Exception {
        // The Paper version used a concurrent set because the exhaustion flag is read from menu and
        // hopper paths on different threads. Keeping that property is the point of this test.
        ExhaustedContainers exhausted = new ExhaustedContainers();
        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        UUID[] ids = new UUID[threads * perThread];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
        }
        try {
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        exhausted.markExhausted(ids[base + i]);
                        assertTrue(exhausted.isExhausted(ids[base + i]));
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }
        for (UUID id : ids) {
            assertTrue(exhausted.isExhausted(id), "an id was lost under concurrency");
        }
    }
}
