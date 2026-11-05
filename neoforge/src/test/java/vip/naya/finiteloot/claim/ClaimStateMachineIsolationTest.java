package vip.naya.finiteloot.claim;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Guards the promise that the claim state machine stays platform-free.
 *
 * <p>The compile would still succeed if someone imported {@code net.minecraft} here, because the test
 * source set has Minecraft on its classpath. The only thing that would notice is this test: it reads the
 * compiled class and looks for platform package names in the constant pool.
 *
 * <p>This is the same check P1 ran by hand with {@code javap} over the data layer; doing it as a test
 * means it keeps running instead of being a one-off observation.
 */
class ClaimStateMachineIsolationTest {

    private static final List<String> FORBIDDEN = List.of(
            "net/minecraft",
            "net/neoforged",
            "org/bukkit",
            "com/mojang/blaze3d",
            "io/netty");

    @Test
    void claimStateMachineReferencesNoPlatformTypes() throws Exception {
        assertNoPlatformReferences(ClaimStateMachine.class);
        assertNoPlatformReferences(ClaimAction.class);
        assertNoPlatformReferences(GeneratedClaimAction.class);
    }

    @Test
    void claimContextRecordReferencesNoPlatformTypes() throws Exception {
        assertNoPlatformReferences(ClaimStateMachine.ClaimContext.class);
    }

    private static void assertNoPlatformReferences(Class<?> type) throws Exception {
        // Nested classes live in ClaimStateMachine$ClaimContext.class, so the simple name is not the
        // resource name. Deriving it from the binary name also keeps this working for any future nesting.
        String relative = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        byte[] bytes;
        try (InputStream stream = type.getResourceAsStream(relative)) {
            assertNotNull(stream, "could not read the compiled class for " + type.getName());
            bytes = stream.readAllBytes();
        }
        // Class files store type names as modified-UTF-8 in the constant pool, so a plain ASCII search is
        // enough to catch an import.
        String constantPool = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String forbidden : FORBIDDEN) {
            assertTrue(!constantPool.contains(forbidden),
                    type.getSimpleName() + " must stay platform-free but references " + forbidden
                            + "; the claim state machine has to remain unit-testable without a server");
        }
    }
}
