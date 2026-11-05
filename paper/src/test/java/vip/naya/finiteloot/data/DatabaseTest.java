package vip.naya.finiteloot.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DatabaseTest {
    @Test
    void enforcesLimitAndRestoresRepeatedClaim() throws Exception {
        Path path = testPath("limit");
        UUID containerId = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        try (Database database = open(path)) {
            database.upsertContainer(container(containerId, 1)).get(5, TimeUnit.SECONDS);
            ClaimAllocation first = database.allocateClaim(containerId, firstPlayer, "first", true, false)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ClaimAllocation.Kind.NEW, first.kind());
            byte[] inventory = {1, 2, 3};
            database.finalizeClaim(containerId, firstPlayer, inventory, false).get(5, TimeUnit.SECONDS);

            ClaimAllocation repeated = database.allocateClaim(containerId, firstPlayer, "first", true, false)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ClaimAllocation.Kind.EXISTING, repeated.kind());
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), bytes(repeated.contents()));

            ClaimAllocation rejected = database.allocateClaim(
                            containerId, UUID.randomUUID(), "second", true, false)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ClaimAllocation.Kind.EXHAUSTED, rejected.kind());
        }
    }

    @Test
    void exactlyOneConcurrentPlayerWinsLastSlot() throws Exception {
        Path path = testPath("concurrent");
        UUID containerId = UUID.randomUUID();
        try (Database database = open(path)) {
            database.upsertContainer(container(containerId, 1)).get(5, TimeUnit.SECONDS);
            List<CompletableFuture<ClaimAllocation>> attempts = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                attempts.add(database.allocateClaim(
                        containerId, UUID.randomUUID(), "player-" + index, true, false));
            }
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            long winners = attempts.stream().map(CompletableFuture::join)
                    .filter(result -> result.kind() == ClaimAllocation.Kind.NEW).count();
            long exhausted = attempts.stream().map(CompletableFuture::join)
                    .filter(result -> result.kind() == ClaimAllocation.Kind.EXHAUSTED).count();
            assertEquals(1, winners);
            assertEquals(23, exhausted);
        }
    }

    @Test
    void survivesDatabaseRestart() throws Exception {
        Path path = testPath("restart");
        UUID containerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        try (Database database = open(path)) {
            database.upsertContainer(container(containerId, 3)).get(5, TimeUnit.SECONDS);
            database.allocateClaim(containerId, playerId, "persistent", true, false)
                    .get(5, TimeUnit.SECONDS);
            database.finalizeClaim(containerId, playerId, new byte[] {9, 8, 7}, false).get(5, TimeUnit.SECONDS);
        }
        try (Database reopened = open(path)) {
            ClaimAllocation restored = reopened.allocateClaim(containerId, playerId, "persistent", true, false)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ClaimAllocation.Kind.EXISTING, restored.kind());
            assertEquals(List.of((byte) 9, (byte) 8, (byte) 7), bytes(restored.contents()));
            assertEquals(1, reopened.findContainer(containerId).get(5, TimeUnit.SECONDS).claimCount());
        }
    }

    @Test
    void reappliesMissingMigrationWithoutLosingData() throws Exception {
        Path path = testPath("migration");
        UUID containerId = UUID.randomUUID();
        try (Database database = open(path)) {
            database.upsertContainer(container(containerId, 3)).get(5, TimeUnit.SECONDS);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM schema_version WHERE version=2");
            statement.execute("DROP INDEX IF EXISTS idx_claims_player");
            statement.execute("DROP INDEX IF EXISTS idx_claims_status");
        }
        try (Database migrated = open(path)) {
            assertNotNull(migrated.findContainer(containerId).get(5, TimeUnit.SECONDS));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
            result.next();
            assertEquals(2, result.getInt(1));
        }
    }

    @Test
    void completedInventoryCanBecomePersonalStorageWithoutAnotherClaim() throws Exception {
        Path path = testPath("reactivate");
        UUID containerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        try (Database database = open(path)) {
            database.upsertContainer(container(containerId, 1)).get(5, TimeUnit.SECONDS);
            assertEquals(ClaimAllocation.Kind.NEW,
                    database.allocateClaim(containerId, playerId, "storage", true, false)
                            .get(5, TimeUnit.SECONDS).kind());
            database.finalizeClaim(containerId, playerId, new byte[] {0}, true)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ClaimAllocation.Kind.COMPLETED,
                    database.allocateClaim(containerId, playerId, "storage", true, false)
                            .get(5, TimeUnit.SECONDS).kind());
            assertEquals(ClaimAllocation.Kind.EXISTING,
                    database.allocateClaim(containerId, playerId, "storage", true, true)
                            .get(5, TimeUnit.SECONDS).kind());
            assertEquals(1, database.findContainer(containerId).get(5, TimeUnit.SECONDS).claimCount());
        }
    }

    @Test
    void identifiesLastCountedClaimantForVanillaTransition() throws Exception {
        Path path = testPath("last-claimant");
        UUID containerId = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        UUID lastPlayer = UUID.randomUUID();
        try (Database database = open(path)) {
            database.upsertContainer(container(containerId, 2)).get(5, TimeUnit.SECONDS);
            database.allocateClaim(containerId, firstPlayer, "first", true, false)
                    .get(5, TimeUnit.SECONDS);
            database.finalizeClaim(containerId, firstPlayer, new byte[] {1}, false)
                    .get(5, TimeUnit.SECONDS);
            database.allocateClaim(containerId, lastPlayer, "last", true, false)
                    .get(5, TimeUnit.SECONDS);
            database.finalizeClaim(containerId, lastPlayer, new byte[] {2}, false)
                    .get(5, TimeUnit.SECONDS);

            assertFalse(database.isLastCountedClaimant(containerId, firstPlayer)
                    .get(5, TimeUnit.SECONDS));
            assertTrue(database.isLastCountedClaimant(containerId, lastPlayer)
                    .get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void listsOnlyFullyClaimedContainers() throws Exception {
        Path path = testPath("exhausted-scan");
        UUID exhaustedId = UUID.randomUUID();
        UUID openId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        try (Database database = open(path)) {
            database.upsertContainer(container(exhaustedId, 1)).get(5, TimeUnit.SECONDS);
            database.upsertContainer(container(openId, 2)).get(5, TimeUnit.SECONDS);
            database.allocateClaim(exhaustedId, playerId, "only", true, false).get(5, TimeUnit.SECONDS);
            database.finalizeClaim(exhaustedId, playerId, new byte[] {1}, false).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(exhaustedId),
                    database.listExhaustedContainerIds().get(5, TimeUnit.SECONDS));
        }
    }

    private Database open(Path path) throws Exception {
        Database database = new Database(path);
        database.initialize();
        return database;
    }

    private Path testPath(String name) throws Exception {
        Path directory = Path.of("build", "test-data", name + "-" + UUID.randomUUID());
        Files.createDirectories(directory);
        return directory.resolve("data.db");
    }

    private ContainerRecord container(UUID id, int maxClaims) {
        return new ContainerRecord(
                id, UUID.randomUUID(), 1, 64, 2, null, null, null,
                "minecraft:chests/simple_dungeon", null, maxClaims, 0, false);
    }

    private List<Byte> bytes(byte[] values) {
        List<Byte> result = new ArrayList<>();
        for (byte value : values) {
            result.add(value);
        }
        return result;
    }
}
