package vip.naya.finiteloot.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class Database implements AutoCloseable {
    private static final List<String> MIGRATIONS = List.of(
            "db/migration/V1__initial.sql",
            "db/migration/V2__integrity_indexes.sql");
    private final Path databasePath;
    private final ExecutorService executor;
    private Connection connection;

    public Database(Path databasePath) {
        this.databasePath = databasePath;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FiniteLoot-Database");
            thread.setDaemon(false);
            return thread;
        });
    }

    public void initialize() throws SQLException, IOException {
        Path parent = Objects.requireNonNull(databasePath.getParent());
        Files.createDirectories(parent);
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 10000");
        }
        migrateInternal(true);
    }

    public CompletableFuture<ContainerRecord> upsertContainer(ContainerRecord record) {
        return submit(() -> {
            long now = System.currentTimeMillis();
            String sql = """
                    INSERT INTO containers (
                        id, world_uid, x, y, z, partner_x, partner_y, partner_z,
                        loot_table, template_contents, max_claims, claim_count, manual, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        world_uid=excluded.world_uid, x=excluded.x, y=excluded.y, z=excluded.z,
                        partner_x=excluded.partner_x, partner_y=excluded.partner_y, partner_z=excluded.partner_z,
                        loot_table=COALESCE(excluded.loot_table, containers.loot_table),
                        template_contents=COALESCE(excluded.template_contents, containers.template_contents),
                        max_claims=excluded.max_claims, manual=excluded.manual, updated_at=excluded.updated_at
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.id().toString());
                statement.setString(2, record.worldId().toString());
                statement.setInt(3, record.x());
                statement.setInt(4, record.y());
                statement.setInt(5, record.z());
                setNullableInt(statement, 6, record.partnerX());
                setNullableInt(statement, 7, record.partnerY());
                setNullableInt(statement, 8, record.partnerZ());
                statement.setString(9, record.lootTable());
                statement.setBytes(10, record.templateContents());
                statement.setInt(11, record.maxClaims());
                statement.setInt(12, record.manual() ? 1 : 0);
                statement.setLong(13, now);
                statement.setLong(14, now);
                statement.executeUpdate();
                return findContainerInternal(record.id());
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    public CompletableFuture<ContainerRecord> findContainer(UUID id) {
        return submit(() -> {
            try {
                return findContainerInternal(id);
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    public CompletableFuture<ClaimAllocation> allocateClaim(
            UUID containerId, UUID playerId, String playerName, boolean counted, boolean reopenCompleted) {
        return submit(() -> immediateTransaction(() -> {
            ClaimAllocation existing = existingClaim(containerId, playerId, reopenCompleted);
            if (existing != null) {
                return existing;
            }

            if (counted) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE containers SET claim_count = claim_count + 1, updated_at = ?
                        WHERE id = ? AND claim_count < max_claims
                        """)) {
                    update.setLong(1, System.currentTimeMillis());
                    update.setString(2, containerId.toString());
                    if (update.executeUpdate() == 0) {
                        return existsInternal(containerId)
                                ? ClaimAllocation.of(ClaimAllocation.Kind.EXHAUSTED)
                                : ClaimAllocation.of(ClaimAllocation.Kind.NOT_FOUND);
                    }
                }
            } else if (!existsInternal(containerId)) {
                return ClaimAllocation.of(ClaimAllocation.Kind.NOT_FOUND);
            }

            long now = System.currentTimeMillis();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO claims(container_id, player_uuid, player_name, counted, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
                    """)) {
                insert.setString(1, containerId.toString());
                insert.setString(2, playerId.toString());
                insert.setString(3, playerName);
                insert.setInt(4, counted ? 1 : 0);
                insert.setLong(5, now);
                insert.setLong(6, now);
                insert.executeUpdate();
            }
            return ClaimAllocation.of(ClaimAllocation.Kind.NEW);
        }));
    }

    public CompletableFuture<Void> finalizeClaim(
            UUID containerId, UUID playerId, byte[] contents, boolean completed) {
        return submit(() -> immediateTransaction(() -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO personal_inventories(container_id, player_uuid, contents, completed, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(container_id, player_uuid) DO NOTHING
                    """)) {
                insert.setString(1, containerId.toString());
                insert.setString(2, playerId.toString());
                insert.setBytes(3, contents);
                insert.setInt(4, completed ? 1 : 0);
                insert.setLong(5, now);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE claims SET status=?, updated_at=?
                    WHERE container_id=? AND player_uuid=? AND status='PENDING'
                    """)) {
                update.setString(1, completed ? "COMPLETED" : "ACTIVE");
                update.setLong(2, now);
                update.setString(3, containerId.toString());
                update.setString(4, playerId.toString());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Pending claim disappeared before inventory persistence");
                }
            }
            return null;
        }));
    }

    public CompletableFuture<Void> releasePendingClaim(UUID containerId, UUID playerId) {
        return submit(() -> immediateTransaction(() -> {
            boolean counted = false;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT counted FROM claims
                    WHERE container_id=? AND player_uuid=? AND status='PENDING'
                    """)) {
                query.setString(1, containerId.toString());
                query.setString(2, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    counted = result.getInt(1) != 0;
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM claims WHERE container_id=? AND player_uuid=? AND status='PENDING'
                    """)) {
                delete.setString(1, containerId.toString());
                delete.setString(2, playerId.toString());
                delete.executeUpdate();
            }
            if (counted) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE containers SET claim_count=MAX(0, claim_count-1), updated_at=? WHERE id=?
                        """)) {
                    update.setLong(1, System.currentTimeMillis());
                    update.setString(2, containerId.toString());
                    update.executeUpdate();
                }
            }
            return null;
        }));
    }

    public CompletableFuture<Void> saveInventory(
            UUID containerId, UUID playerId, byte[] contents, boolean completed) {
        return submit(() -> immediateTransaction(() -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement updateInventory = connection.prepareStatement("""
                    UPDATE personal_inventories SET contents=?, completed=?, updated_at=?
                    WHERE container_id=? AND player_uuid=?
                    """)) {
                updateInventory.setBytes(1, contents);
                updateInventory.setInt(2, completed ? 1 : 0);
                updateInventory.setLong(3, now);
                updateInventory.setString(4, containerId.toString());
                updateInventory.setString(5, playerId.toString());
                if (updateInventory.executeUpdate() != 1) {
                    throw new SQLException("Cannot save missing personal inventory");
                }
            }
            try (PreparedStatement updateClaim = connection.prepareStatement("""
                    UPDATE claims SET status=?, updated_at=? WHERE container_id=? AND player_uuid=?
                    """)) {
                updateClaim.setString(1, completed ? "COMPLETED" : "ACTIVE");
                updateClaim.setLong(2, now);
                updateClaim.setString(3, containerId.toString());
                updateClaim.setString(4, playerId.toString());
                updateClaim.executeUpdate();
            }
            return null;
        }));
    }

    public CompletableFuture<Void> resetContainer(UUID containerId) {
        return submit(() -> immediateTransaction(() -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM claims WHERE container_id=?")) {
                delete.setString(1, containerId.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE containers SET claim_count=0, updated_at=? WHERE id=?")) {
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, containerId.toString());
                update.executeUpdate();
            }
            return null;
        }));
    }

    public CompletableFuture<Void> resetPlayer(UUID containerId, UUID playerId) {
        return submit(() -> immediateTransaction(() -> {
            boolean counted = false;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT counted FROM claims WHERE container_id=? AND player_uuid=?")) {
                query.setString(1, containerId.toString());
                query.setString(2, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    counted = result.next() && result.getInt(1) != 0;
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM claims WHERE container_id=? AND player_uuid=?")) {
                delete.setString(1, containerId.toString());
                delete.setString(2, playerId.toString());
                delete.executeUpdate();
            }
            if (counted) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE containers SET claim_count=MAX(0, claim_count-1), updated_at=? WHERE id=?
                        """)) {
                    update.setLong(1, System.currentTimeMillis());
                    update.setString(2, containerId.toString());
                    update.executeUpdate();
                }
            }
            return null;
        }));
    }

    public CompletableFuture<Void> removeContainer(UUID containerId) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM containers WHERE id=?")) {
                statement.setString(1, containerId.toString());
                statement.executeUpdate();
                return null;
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    public CompletableFuture<List<UUID>> listExhaustedContainerIds() {
        return submit(() -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM containers WHERE claim_count >= max_claims");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(UUID.fromString(result.getString(1)));
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return List.copyOf(ids);
        });
    }

    public CompletableFuture<Boolean> isLastCountedClaimant(UUID containerId, UUID playerId) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid FROM claims
                    WHERE container_id=? AND counted=1
                    ORDER BY created_at DESC, rowid DESC
                    LIMIT 1
                    """)) {
                statement.setString(1, containerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && playerId.toString().equals(result.getString(1));
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    public CompletableFuture<ContainerInspection> inspect(UUID containerId) {
        return submit(() -> {
            try {
                ContainerRecord record = findContainerInternal(containerId);
                if (record == null) {
                    return null;
                }
                List<String> claims = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT player_name, player_uuid, counted, status FROM claims
                        WHERE container_id=? ORDER BY created_at
                        """)) {
                    statement.setString(1, containerId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            claims.add(result.getString(1) + " (" + result.getString(2) + ", "
                                    + result.getString(4) + (result.getInt(3) == 0 ? ", uncounted" : "") + ")");
                        }
                    }
                }
                return new ContainerInspection(record, List.copyOf(claims));
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    public CompletableFuture<Path> backupMigrateAndClean(Path backupDirectory) {
        return submit(() -> {
            try {
                Files.createDirectories(backupDirectory);
                String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                        .withZone(ZoneOffset.UTC).format(Instant.now());
                Path backup = backupDirectory.resolve("data-" + timestamp + ".db").toAbsolutePath();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA wal_checkpoint(FULL)");
                    String escaped = backup.toString().replace("\\", "/").replace("'", "''");
                    statement.execute("VACUUM INTO '" + escaped + "'");
                }
                migrateInternal(false);
                immediateTransaction(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("""
                                DELETE FROM personal_inventories
                                WHERE NOT EXISTS (
                                    SELECT 1 FROM claims
                                    WHERE claims.container_id=personal_inventories.container_id
                                      AND claims.player_uuid=personal_inventories.player_uuid)
                                """);
                        statement.executeUpdate("""
                                DELETE FROM claims
                                WHERE NOT EXISTS (SELECT 1 FROM containers WHERE containers.id=claims.container_id)
                                """);
                    }
                    return null;
                });
                validateInternal();
                return backup;
            } catch (SQLException | IOException exception) {
                throw failure(exception);
            }
        });
    }

    public CompletableFuture<Void> flush() {
        return submit(() -> null);
    }

    private ClaimAllocation existingClaim(
            UUID containerId, UUID playerId, boolean reopenCompleted) throws SQLException {
        String status;
        byte[] contents;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.status, p.contents
                FROM claims c
                LEFT JOIN personal_inventories p
                  ON p.container_id=c.container_id AND p.player_uuid=c.player_uuid
                WHERE c.container_id=? AND c.player_uuid=?
                """)) {
            statement.setString(1, containerId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                status = result.getString(1);
                contents = result.getBytes(2);
            }
        }
        if ("COMPLETED".equals(status) && reopenCompleted) {
            long now = System.currentTimeMillis();
            try (PreparedStatement claim = connection.prepareStatement("""
                    UPDATE claims SET status='ACTIVE', updated_at=?
                    WHERE container_id=? AND player_uuid=? AND status='COMPLETED'
                    """)) {
                claim.setLong(1, now);
                claim.setString(2, containerId.toString());
                claim.setString(3, playerId.toString());
                claim.executeUpdate();
            }
            try (PreparedStatement inventory = connection.prepareStatement("""
                    UPDATE personal_inventories SET completed=0, updated_at=?
                    WHERE container_id=? AND player_uuid=?
                    """)) {
                inventory.setLong(1, now);
                inventory.setString(2, containerId.toString());
                inventory.setString(3, playerId.toString());
                inventory.executeUpdate();
            }
            return new ClaimAllocation(ClaimAllocation.Kind.EXISTING, contents);
        }
        return switch (status) {
            case "COMPLETED" -> ClaimAllocation.of(ClaimAllocation.Kind.COMPLETED);
            case "ACTIVE" -> new ClaimAllocation(ClaimAllocation.Kind.EXISTING, contents);
            default -> ClaimAllocation.of(ClaimAllocation.Kind.PENDING);
        };
    }

    private ContainerRecord findContainerInternal(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM containers WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ContainerRecord(
                        UUID.fromString(result.getString("id")),
                        UUID.fromString(result.getString("world_uid")),
                        result.getInt("x"), result.getInt("y"), result.getInt("z"),
                        nullableInt(result, "partner_x"),
                        nullableInt(result, "partner_y"),
                        nullableInt(result, "partner_z"),
                        result.getString("loot_table"),
                        result.getBytes("template_contents"),
                        result.getInt("max_claims"),
                        result.getInt("claim_count"),
                        result.getInt("manual") != 0);
            }
        }
    }

    private boolean existsInternal(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM containers WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void migrateInternal(boolean validate) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        installed_at INTEGER NOT NULL)
                    """);
        }
        int current = 0;
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            if (result.next()) {
                current = result.getInt(1);
            }
        }
        if (current > MIGRATIONS.size()) {
            throw new SQLException("Database schema is newer than this plugin: " + current);
        }
        for (int index = current; index < MIGRATIONS.size(); index++) {
            int version = index + 1;
            String resource = MIGRATIONS.get(index);
            String sql = readResource(resource);
            immediateTransaction(() -> {
                for (String statementSql : sql.split(";")) {
                    if (!statementSql.isBlank()) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute(statementSql);
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO schema_version(version, description, installed_at) VALUES (?, ?, ?)
                        """)) {
                    insert.setInt(1, version);
                    insert.setString(2, resource);
                    insert.setLong(3, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                return null;
            });
        }
        if (validate) {
            validateInternal();
        }
    }

    private void validateInternal() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite integrity_check failed");
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new SQLException("SQLite foreign_key_check found orphaned data");
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT id FROM containers
                        WHERE claim_count != (
                            SELECT COUNT(*) FROM claims WHERE claims.container_id=containers.id AND counted=1)
                        LIMIT 1
                        """)) {
            if (result.next()) {
                throw new SQLException("Claim count mismatch for container " + result.getString(1));
            }
        }
    }

    private String readResource(String name) throws IOException {
        try (InputStream stream = Database.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing migration resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private <T> T immediateTransaction(SqlSupplier<T> supplier) {
        try (Statement begin = connection.createStatement()) {
            begin.execute("BEGIN IMMEDIATE");
            try {
                T result = supplier.get();
                try (Statement commit = connection.createStatement()) {
                    commit.execute("COMMIT");
                }
                return result;
            } catch (Throwable throwable) {
                try (Statement rollback = connection.createStatement()) {
                    rollback.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    throwable.addSuppressed(rollbackFailure);
                }
                throw throwable;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet result, String name) throws SQLException {
        int value = result.getInt(name);
        return result.wasNull() ? null : value;
    }

    private static IllegalStateException failure(Throwable exception) {
        return new IllegalStateException("FiniteLoot database operation failed", exception);
    }

    @Override
    public void close() {
        CompletableFuture<Void> closeFuture = submit(() -> {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException exception) {
                    throw failure(exception);
                }
            }
            return null;
        });
        try {
            closeFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw failure(exception);
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
