package vip.naya.finiteloot.p0.containers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ContainerMarker;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P3: does the container layer actually work, and does a block-entity data attachment really survive a
 * server restart?
 *
 * <p>The whole of P3 rests on one assumption: that writing the container id as a data attachment on a
 * {@code BlockEntity} persists it into the block entity's NBT, exactly as Paper's
 * {@code PersistentDataContainer} did. NeoForge's source says it should ({@code BlockEntity} extends
 * {@code AttachmentHolder} and the constructor patches {@code saveAdditional}/{@code loadAdditional}),
 * but "the source says it should" is not evidence, and a broken marker would silently re-identify every
 * container in the world as new -- the kind of bug that wipes players' claim state on every restart.
 *
 * <p>So this probe runs in two boots, coordinated by a seed file:
 *
 * <ol>
 *   <li><b>First boot:</b> place a single chest and a double chest, tag them, and record the expected id.
 *   <li><b>Second boot:</b> read them back and compare. The world was saved and reloaded in between, so a
 *       match proves the marker round-tripped through NBT on disk.
 * </ol>
 *
 * <p>Judged by grepping for the {@code [P3-} prefix.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class ContainerLayerProbe {

    private static final BlockPos SINGLE_POS = new BlockPos(40, 200, 40);
    private static final BlockPos DOUBLE_WEST = new BlockPos(60, 200, 40);
    private static final BlockPos DOUBLE_EAST = new BlockPos(61, 200, 40);
    private static final BlockPos PLAIN_POS = new BlockPos(80, 200, 40);
    private static final BlockPos RELEASE_POS = new BlockPos(100, 200, 40);
    private static final Path STATE_DIR = Path.of("config", "finiteloot-p3");
    private static final Path SEED_FILE = STATE_DIR.resolve("seed.txt");

    private ContainerLayerProbe() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p3")) {
            return;
        }
        try {
            run(event.getServer());
        } catch (Throwable t) {
            ProbeMod.LOG.error("[P3] FAILED with exception", t);
        }
    }

    private static void run(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        ContainerManager manager = new ContainerManager(ProbeMod.LOG, name -> false);

        // Force the chunks in, so the probe does not depend on whether anything else has visited them.
        level.getChunk(SINGLE_POS);
        level.getChunk(DOUBLE_WEST);
        level.getChunk(PLAIN_POS);

        if (Files.exists(SEED_FILE)) {
            verify(level, manager);
        } else {
            seed(level, manager);
        }
    }

    // ---------------------------------------------------------------- first boot

    private static void seed(ServerLevel level, ContainerManager manager) throws Exception {
        ProbeMod.LOG.info("[P3-1] first boot: seeding rig");

        // A single chest that carries a loot table, i.e. something FiniteLoot should take over.
        placeChest(level, SINGLE_POS, Direction.NORTH, ChestType.SINGLE);
        setLootTable(level, SINGLE_POS, "chests/simple_dungeon");

        Optional<ContainerTarget> resolved = manager.resolve(level, SINGLE_POS);
        boolean identified = resolved.isPresent() && resolved.get().newlyIdentified();
        UUID singleId = resolved.map(ContainerTarget::id).orElse(null);
        ProbeMod.LOG.info("[P3-1] single chest: resolved={} newlyIdentified={} id={}",
                resolved.isPresent(), identified, singleId);

        // Resolving a second time must return the same id without claiming to be new -- this is the
        // behaviour that stops a re-open from being treated as a fresh discovery.
        Optional<ContainerTarget> again = manager.resolve(level, SINGLE_POS);
        boolean stable = again.isPresent() && !again.get().newlyIdentified()
                && again.get().id().equals(singleId);
        ProbeMod.LOG.info("[P3-1] single chest re-resolve: stable={} sameId={}",
                stable, again.map(t -> t.id().equals(singleId)).orElse(false));

        // A plain chest with no loot table and no marker must NOT be eligible, or every chest in the
        // world would be taken over.
        placeChest(level, PLAIN_POS, Direction.NORTH, ChestType.SINGLE);
        boolean plainEligible = manager.isManagedOrEligible(level, PLAIN_POS);
        Optional<ContainerTarget> plainResolved = manager.resolve(level, PLAIN_POS);
        boolean plainIgnored = plainResolved.isEmpty();
        ProbeMod.LOG.info("[P3-1] plain chest (no loot table): eligible={} resolveEmpty={}",
                plainEligible, plainIgnored);

        // A double chest: two block entities, one inventory. The loot table is set on ONE half only, as
        // vanilla worldgen can, to prove the pairing logic finds the table from the other half.
        placeChest(level, DOUBLE_WEST, Direction.NORTH, ChestType.LEFT);
        placeChest(level, DOUBLE_EAST, Direction.NORTH, ChestType.RIGHT);
        setLootTable(level, DOUBLE_EAST, "chests/ancient_city");

        Optional<ContainerTarget> pair = manager.resolve(level, DOUBLE_WEST);
        boolean paired = pair.isPresent() && pair.get().tiles().size() == 2;
        boolean compound = pair.map(target -> target.sourceInventory() instanceof CompoundContainer).orElse(false);
        int pairSlots = pair.map(target -> target.sourceInventory().getContainerSize()).orElse(0);
        boolean tableFromOtherHalf = pair.map(target -> target.lootTable() != null
                && target.lootTable().location().getPath().equals("chests/ancient_city")).orElse(false);
        UUID doubleId = pair.map(ContainerTarget::id).orElse(null);
        ProbeMod.LOG.info("[P3-1] double chest: tiles={} compoundInventory={} slots={} "
                        + "lootTableFromOtherHalf={} id={}",
                pair.map(t -> t.tiles().size()).orElse(0), compound, pairSlots, tableFromOtherHalf, doubleId);

        // Both halves must resolve to the SAME container, otherwise one chest could be claimed twice.
        Optional<ContainerTarget> fromEast = manager.resolve(level, DOUBLE_EAST);
        boolean sameFromEitherHalf = fromEast.isPresent() && doubleId != null
                && fromEast.get().id().equals(doubleId)
                && fromEast.get().tiles().size() == 2;
        ProbeMod.LOG.info("[P3-1] double chest resolved from the other half: sameId={} tiles={}",
                fromEast.map(t -> t.id().equals(doubleId)).orElse(false),
                fromEast.map(t -> t.tiles().size()).orElse(0));

        // The ignore path: an admin-removed container must not be re-taken, and must STAY released
        // across a restart. Note the ordering below is deliberate -- see the comment on the re-release.
        BlockPos releasePos = RELEASE_POS;
        placeChest(level, releasePos, Direction.NORTH, ChestType.SINGLE);
        setLootTable(level, releasePos, "chests/abandoned_mineshaft");
        level.getChunk(releasePos);

        Optional<ContainerTarget> manualTakeover = manager.resolve(level, releasePos, true);
        boolean manualWorks = manualTakeover.isPresent();
        manualTakeover.ifPresent(manager::releaseToNormal);

        boolean autoTakeoverBlocked = manager.resolve(level, releasePos).isEmpty();
        BlockEntity releasedTile = level.getBlockEntity(releasePos);
        boolean ignoredSet = releasedTile != null && ContainerMarker.isIgnored(releasedTile);
        boolean idCleared = releasedTile != null && ContainerMarker.readId(releasedTile, ProbeMod.LOG) == null;
        boolean lootTableCleared = releasedTile instanceof RandomizableContainerBlockEntity lootable
                && lootable.getLootTable() == null;
        ProbeMod.LOG.info("[P3-1] released container: manualWorks={} autoTakeoverBlocked={} "
                        + "ignoredFlag={} idCleared={} lootTableCleared={}",
                manualWorks, autoTakeoverBlocked, ignoredSet, idCleared, lootTableCleared);

        // Re-take it manually, then release it again. This second release is not redundant: an admin
        // manual takeover legitimately clears the ignored flag (that is what makes it a *manual*
        // takeover), so leaving the rig right after the manual step would persist the container as
        // re-managed and quietly prove nothing about whether `ignored` survives a restart.
        Optional<ContainerTarget> reManual = manager.resolve(level, releasePos, true);
        boolean manualStillWorksAfterRelease = reManual.isPresent();
        reManual.ifPresent(manager::releaseToNormal);
        ProbeMod.LOG.info("[P3-1] released container: manualStillWorksAfterRelease={} (left in released state)",
                manualStillWorksAfterRelease);

        // Persist the expectation for the second boot.
        Files.createDirectories(STATE_DIR);
        Files.writeString(SEED_FILE,
                "single=" + singleId + "\n"
                        + "double=" + doubleId + "\n"
                        + "singlePos=" + SINGLE_POS.getX() + "," + SINGLE_POS.getY() + "," + SINGLE_POS.getZ() + "\n"
                        + "doublePos=" + DOUBLE_WEST.getX() + "," + DOUBLE_WEST.getY() + "," + DOUBLE_WEST.getZ() + "\n",
                StandardCharsets.UTF_8);
        ProbeMod.LOG.info("[P3-1] seed recorded, restart the server to test persistence");

        // Flush the chunks to disk here rather than relying on a graceful shutdown. The second boot is
        // meant to test whether the *attachment* survives being written to NBT and read back; if the
        // chunk itself were never saved, that test would fail for an unrelated reason and tell us
        // nothing about the marker.
        level.getChunkSource().save(true);
        ProbeMod.LOG.info("[P3-1] chunks flushed to disk");

        boolean pass = identified && stable && singleId != null && !plainEligible && plainIgnored
                && paired && compound && pairSlots == 54 && tableFromOtherHalf
                && sameFromEitherHalf && manualWorks && autoTakeoverBlocked && ignoredSet
                && idCleared && lootTableCleared && manualStillWorksAfterRelease;
        ProbeMod.LOG.info("[P3-1] {} - container layer identifies, pairs and tags containers",
                pass ? "SUCCESS" : "FAILURE");
    }

    // ---------------------------------------------------------------- second boot

    private static void verify(ServerLevel level, ContainerManager manager) throws Exception {
        ProbeMod.LOG.info("[P3-2] second boot: checking that the markers survived the restart");

        List<String> seed = Files.readAllLines(SEED_FILE, StandardCharsets.UTF_8);
        UUID expectedSingle = uuidFrom(seed, "single=");
        UUID expectedDouble = uuidFrom(seed, "double=");
        ProbeMod.LOG.info("[P3-2] expected single={} double={}", expectedSingle, expectedDouble);

        BlockEntity singleTile = level.getBlockEntity(SINGLE_POS);
        boolean singleBlockPresent = singleTile != null;
        boolean singleHasMarker = singleTile != null && ContainerMarker.hasAnyMarker(singleTile);
        UUID actualSingle = singleTile == null ? null
                : ContainerMarker.readId(singleTile, ProbeMod.LOG);
        boolean singlePersisted = expectedSingle != null && expectedSingle.equals(actualSingle);
        ProbeMod.LOG.info("[P3-2] single chest: blockEntity={} hasMarker={} id={} persisted={}",
                singleBlockPresent, singleHasMarker, actualSingle, singlePersisted);

        // Re-resolving after the restart must NOT report a fresh identification: if it does, the marker
        // was lost and the container would have been handed a new id with fresh claim slots.
        Optional<ContainerTarget> reResolved = manager.resolve(level, SINGLE_POS);
        boolean notNewlyIdentified = reResolved.isPresent() && !reResolved.get().newlyIdentified();
        boolean sameIdAfterRestart = reResolved.isPresent()
                && reResolved.get().id().equals(expectedSingle);
        ProbeMod.LOG.info("[P3-2] single chest re-resolve after restart: notNewlyIdentified={} sameId={}",
                notNewlyIdentified, sameIdAfterRestart);

        Optional<ContainerTarget> pair = manager.resolve(level, DOUBLE_WEST);
        boolean bothHalvesPersisted = pair.isPresent() && pair.get().tiles().size() == 2;
        boolean doublePersisted = pair.isPresent() && pair.get().id().equals(expectedDouble);
        boolean compoundAfterRestart = pair.map(t -> t.sourceInventory() instanceof CompoundContainer)
                .orElse(false);
        ProbeMod.LOG.info("[P3-2] double chest: tiles={} compoundInventory={} id={} persisted={}",
                pair.map(t -> t.tiles().size()).orElse(0), compoundAfterRestart,
                pair.map(ContainerTarget::id).orElse(null), doublePersisted);

        // The loot table must also still be there -- clearing it is how a container becomes "normal",
        // so losing it would make every finished container look claimable again.
        boolean lootTableIntact = false;
        BlockEntity eastTile = level.getBlockEntity(DOUBLE_EAST);
        if (eastTile instanceof RandomizableContainerBlockEntity lootable) {
            lootTableIntact = lootable.getLootTable() != null
                    && lootable.getLootTable().location().getPath().equals("chests/ancient_city");
        }
        ProbeMod.LOG.info("[P3-2] loot table on the tagged half intact after restart: {}", lootTableIntact);

        boolean pass = singleBlockPresent && singleHasMarker && singlePersisted
                && notNewlyIdentified && sameIdAfterRestart
                && bothHalvesPersisted && doublePersisted && compoundAfterRestart && lootTableIntact;

        // The released container is the other half of the persistence story, and the more dangerous one:
        // if `ignored` did not survive, every container an admin removed would silently come back under
        // management after the next restart, with fresh claim slots.
        level.getChunk(RELEASE_POS);
        BlockEntity releasedTile = level.getBlockEntity(RELEASE_POS);
        boolean releaseBlockPresent = releasedTile != null;
        boolean ignoredPersisted = releasedTile != null && ContainerMarker.isIgnored(releasedTile);
        boolean noIdAfterRestart = releasedTile != null
                && ContainerMarker.readId(releasedTile, ProbeMod.LOG) == null;
        boolean stillBlockedAfterRestart = manager.resolve(level, RELEASE_POS).isEmpty();
        ProbeMod.LOG.info("[P3-2] released container: blockEntity={} ignoredPersisted={} idAbsent={} "
                        + "autoTakeoverStillBlocked={}",
                releaseBlockPresent, ignoredPersisted, noIdAfterRestart, stillBlockedAfterRestart);

        pass = pass && releaseBlockPresent && ignoredPersisted && noIdAfterRestart && stillBlockedAfterRestart;
        ProbeMod.LOG.info("[P3-2] {} - container markers survive a server restart", pass ? "SUCCESS" : "FAILURE");
    }

    // ---------------------------------------------------------------- helpers

    private static UUID uuidFrom(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                try {
                    return UUID.fromString(line.substring(prefix.length()).trim());
                } catch (IllegalArgumentException exception) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void placeChest(ServerLevel level, BlockPos pos, Direction facing, ChestType type) {
        BlockState state = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, facing)
                .setValue(ChestBlock.TYPE, type);
        level.getChunk(pos);
        level.setBlock(pos, state, 3);
    }

    private static void setLootTable(ServerLevel level, BlockPos pos, String path) {
        BlockEntity tile = level.getBlockEntity(pos);
        if (!(tile instanceof RandomizableContainerBlockEntity lootable)) {
            ProbeMod.LOG.warn("[P3] no randomisable block entity at {}", pos);
            return;
        }
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.withDefaultNamespace(path));
        // Loot tables are not in the level's registry access in 1.21.1: they live in the reloadable
        // server registries, because they are reloaded from datapacks. getLootTable returns
        // LootTable.EMPTY for an unknown key, so that doubles as the validity check.
        boolean known = level.getServer().reloadableRegistries().getLootTable(key) != LootTable.EMPTY;
        if (!known) {
            ProbeMod.LOG.warn("[P3] loot table {} is not loaded on this server", key.location());
        }
        lootable.setLootTable(key);
        tile.setChanged();
    }
}
