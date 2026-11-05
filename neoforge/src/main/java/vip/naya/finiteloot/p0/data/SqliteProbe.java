package vip.naya.finiteloot.p0.data;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import vip.naya.finiteloot.p0.ProbeMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * P0-3: prove that a {@code jarJar}-bundled {@code sqlite-jdbc} actually works at runtime.
 *
 * <p>The Paper plugin declares {@code org.xerial:sqlite-jdbc} in {@code paper-plugin.yml}'s
 * {@code libraries:}, and the server downloads it. A mod has no such mechanism, so the driver must be
 * nested inside our jar via {@code jarJar} and loaded by FML's jar-in-jar loader. That failure mode
 * would be total (no database, mod unusable) and would only appear in a production build, never in a
 * dev run -- which is exactly why it is a P0 item.
 *
 * <p>sqlite-jdbc ships native binaries for every platform inside its jar and extracts the right one to
 * a temp directory before {@code System.load}. The interesting question is whether that extraction
 * still works when the jar is nested inside a mod jar rather than sitting on the plain classpath.
 *
 * <p>The probe exercises the real code path end to end: load the class, open a file-backed database,
 * run the actual V1 schema, write a row, read it back, and check WAL mode.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class SqliteProbe {

    private SqliteProbe() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p1")) {
            return;
        }
        Path dir = Path.of("finiteloot-p0");
        try {
            Files.createDirectories(dir);
            Path db = dir.resolve("probe.db");
            run(db);
        } catch (Throwable t) {
            // A failure here is a P0 result, not a crash: report it loudly and keep the server up so
            // the other P0 checks can still run in the same session.
            ProbeMod.LOG.error("[P0-3] FAILED - sqlite-jdbc is NOT usable from the mod jar. "
                    + "MIGRATION-PLAN risk R7 is real; fall back to SavedData (decision D7).", t);
        }
    }

    private static void run(Path db) throws Exception {
        // Step 1: is the driver class even present/loadable through the jar-in-jar classloader?
        Class<?> driverClass = Class.forName("org.sqlite.JDBC");
        ProbeMod.LOG.info("[P0-3] driver class loaded: {} from {}",
                driverClass.getName(), driverClass.getProtectionDomain().getCodeSource().getLocation());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            // Step 2: WAL + pragmas used by the real Database implementation.
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA busy_timeout=10000");
            }
            String journalMode;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                journalMode = rs.next() ? rs.getString(1) : "?";
            }

            // Step 3: run a representative slice of the real V1 schema, including a BLOB.
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS containers (
                            id TEXT PRIMARY KEY,
                            world_uid TEXT NOT NULL,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            z INTEGER NOT NULL,
                            loot_table TEXT,
                            template_contents BLOB,
                            max_claims INTEGER NOT NULL CHECK (max_claims > 0),
                            claim_count INTEGER NOT NULL DEFAULT 0 CHECK (claim_count >= 0)
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS claims (
                            container_id TEXT NOT NULL,
                            player_uuid TEXT NOT NULL,
                            player_name TEXT,
                            counted INTEGER NOT NULL DEFAULT 1,
                            status TEXT NOT NULL CHECK (status IN ('PENDING','ACTIVE','COMPLETED')),
                            PRIMARY KEY (container_id, player_uuid)
                        )""");
            }

            // Step 4: round-trip a BLOB, because that is how personal inventories are stored.
            byte[] payload = new byte[] {1, 2, 3, 4, 5, 0, -1, 127, -128};
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO containers (id, world_uid, x, y, z, loot_table, template_contents, max_claims, claim_count) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, "probe-1");
                ps.setString(2, "minecraft:overworld");
                ps.setInt(3, 10);
                ps.setInt(4, 64);
                ps.setInt(5, -20);
                ps.setString(6, "minecraft:simple_dungeon");
                ps.setBytes(7, payload);
                ps.setInt(8, 3);
                ps.setInt(9, 0);
                ps.executeUpdate();
            }

            // Step 5: the concurrency primitive the claim allocator depends on.
            // NB: setAutoCommit(false) already opens a transaction, so issuing BEGIN IMMEDIATE here
            // would fail with "cannot start a transaction within a transaction". The real Database
            // uses a plain Statement.executeUpdate inside a manually managed transaction; replicate
            // that shape rather than nesting BEGIN.
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("UPDATE containers SET claim_count = claim_count + 1 "
                            + "WHERE id = 'probe-1' AND claim_count < max_claims");
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

            int claimCount;
            byte[] readBack;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT claim_count, template_contents FROM containers WHERE id = 'probe-1'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                claimCount = rs.getInt(1);
                readBack = rs.getBytes(2);
            }

            boolean blobOk = java.util.Arrays.equals(payload, readBack);
            boolean checkOk = claimCount == 1;

            ProbeMod.LOG.info(
                    "[P0-3] SUCCESS - sqlite usable from the mod jar. journal_mode={} blobRoundTrip={} claimCounter={} db={}",
                    journalMode, blobOk, checkOk, db.toAbsolutePath());

            if (!blobOk || !checkOk || !"wal".equalsIgnoreCase(journalMode)) {
                ProbeMod.LOG.error("[P0-3] PARTIAL - a sub-check failed (blob={}, claim={}, wal={})",
                        blobOk, checkOk, journalMode);
            }
        }
    }
}
