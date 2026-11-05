package vip.naya.finiteloot.p0.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import vip.naya.finiteloot.data.ClaimAllocation;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P1: runs the ported data layer inside a <em>real production server</em>, from the built mod jar.
 *
 * <p>The JUnit suite already proves the data layer's logic. What it cannot prove is packaging and
 * classloading, and those are exactly where this port has non-obvious risk:
 *
 * <ol>
 *   <li>{@link Database#readResource} loads {@code db/migration/*.sql} through
 *       {@code Database.class.getClassLoader()}. NeoForge runs mods under a transforming classloader
 *       built on SecureJarHandler, so "the classloader can see the mod's own resources" is an
 *       assumption worth testing rather than assuming.
 *   <li>{@code DriverManager.getConnection("jdbc:sqlite:...")} relies on the JDBC service loader to
 *       find {@code org.sqlite.JDBC} in the jarJar-nested driver. P0-3 showed the driver resolves in
 *       production but not in a dev run, so this must be re-proven here.
 *   <li>The test suite skipped {@code backupMigrateAndClean}, {@code inspect} and {@code saveInventory};
 *       this exercises them so no production-only path stays untested.
 * </ol>
 *
 * <p>Judged by grepping for the {@code [P1-} prefix in the server log.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class DataLayerProbe {

    private DataLayerProbe() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p1")) {
            return;
        }
        try {
            run();
        } catch (Throwable t) {
            ProbeMod.LOG.error("[P1] FAILED with exception", t);
        }
    }

    private static void run() throws Exception {
        ProbeMod.LOG.info("[P1] data layer probe starting");

        // Where the real port would put it: <server>/config/finiteloot/data.db. Kept separate from the
        // P0-3 probe file so neither can mask the other.
        Path root = Path.of("config", "finiteloot-p1");
        Files.createDirectories(root);
        Path dbPath = root.resolve("data.db");

        UUID containerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        // (1) initialize(): this is the load-bearing step -- it needs the migration SQL from the mod
        // jar AND the sqlite driver from the nested jar.
        try (Database database = new Database(dbPath)) {
            database.initialize();
            ProbeMod.LOG.info("[P1] initialize OK - migrations loaded from the mod jar, driver resolved");

            ContainerRecord record = new ContainerRecord(
                    containerId, UUID.randomUUID(), 128, 64, -256,
                    null, null, null,
                    "minecraft:chests/simple_dungeon", null, 2, 0, false);
            database.upsertContainer(record).get(5, TimeUnit.SECONDS);
            ProbeMod.LOG.info("[P1] upsertContainer OK");

            ClaimAllocation first = database.allocateClaim(containerId, playerId, "ProbePlayer", true, false)
                    .get(5, TimeUnit.SECONDS);
            ProbeMod.LOG.info("[P1] allocateClaim first = {}", first.kind());

            // (3) saveInventory: exercised here because the unit suite does not cover it.
            database.finalizeClaim(containerId, playerId, new byte[] {11, 22, 33}, false)
                    .get(5, TimeUnit.SECONDS);
            database.saveInventory(containerId, playerId, new byte[] {44, 55}, false)
                    .get(5, TimeUnit.SECONDS);
            ProbeMod.LOG.info("[P1] finalizeClaim + saveInventory OK");

            ClaimAllocation repeat = database.allocateClaim(containerId, playerId, "ProbePlayer", true, false)
                    .get(5, TimeUnit.SECONDS);
            List<Byte> restored = new java.util.ArrayList<>();
            if (repeat.contents() != null) {
                for (byte b : repeat.contents()) {
                    restored.add(b);
                }
            }

            // (3) inspect: an admin path the unit suite never touched.
            var inspection = database.inspect(containerId).get(5, TimeUnit.SECONDS);
            ProbeMod.LOG.info("[P1] inspect OK - container={} claims={}",
                    inspection != null && inspection.container() != null,
                    inspection == null ? -1 : inspection.claims().size());

            // (2) isLastCountedClaimant
            boolean last = database.isLastCountedClaimant(containerId, playerId).get(5, TimeUnit.SECONDS);
            ProbeMod.LOG.info("[P1] isLastCountedClaimant = {}", last);

            // (3) backupMigrateAndClean: runs VACUUM INTO and the integrity checks -- the most
            // packaging-sensitive method, since it also re-reads migration resources.
            Path backup = database.backupMigrateAndClean(root.resolve("backups")).get(15, TimeUnit.SECONDS);
            boolean backupExists = Files.exists(backup);
            long backupSize = backupExists ? Files.size(backup) : -1L;
            ProbeMod.LOG.info("[P1] backupMigrateAndClean OK - file={} exists={} bytes={}",
                    backup.getFileName(), backupExists, backupSize);

            database.flush().get(5, TimeUnit.SECONDS);

            ContainerRecord after = database.findContainer(containerId).get(5, TimeUnit.SECONDS);
            boolean pass = repeat.kind() == ClaimAllocation.Kind.EXISTING
                    && restored.equals(List.of((byte) 44, (byte) 55))
                    && after != null
                    && after.claimCount() == 1
                    && backupExists
                    && backupSize > 0;
            ProbeMod.LOG.info(
                    "[P1] repeatKind={} restoredContents={} claimCount={} backupBytes={}",
                    repeat.kind(), restored, after == null ? null : after.claimCount(), backupSize);
            ProbeMod.LOG.info("[P1] {} - production data layer usable from the mod jar",
                    pass ? "SUCCESS" : "FAILURE");
        }
    }
}
