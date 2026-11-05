package vip.naya.finiteloot.p0.claim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import vip.naya.finiteloot.claim.ClaimController;
import vip.naya.finiteloot.config.ConfigManager;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.data.ClaimAllocation;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.loot.LootAdvancementService;
import vip.naya.finiteloot.loot.LootGenerator;
import vip.naya.finiteloot.p0.ProbeGate;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P4: drives the real claim flow against a real database, container and loot table.
 *
 * <p>Why a tick-driven scenario instead of straight-line code: {@link ClaimController} completes its work
 * on the database's own executor and hops back onto the server thread. Blocking the server thread to wait
 * for that would deadlock -- the very thread that has to run the callback is the one waiting. So the
 * scenario advances one phase per server tick and only inspects state once a phase's expected presentation
 * has actually been recorded.
 *
 * <p>Two containers are exercised, because the interesting branch depends on the claim limit:
 *
 * <ul>
 *   <li><b>Container A</b> -- limit 1, so the player's first claim is also the final vanilla claim. This is
 *       the hardest branch: loot is generated, written to the database, then handed to the real container
 *       and the container is retired.
 *   <li><b>Container B</b> -- limit 2, so the first claim is an ordinary personal claim. This exercises the
 *       generated-loot path, the round trip through the database on reopen, and the "not the last counted
 *       claimant, so keep it managed" path.
 * </ul>
 *
 * <p>The limits come from a real {@code config.yml}, so the config-to-claim wiring is covered too rather
 * than passing numbers directly.
 *
 * <p>Judged by grepping for the {@code [P4} prefix.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class ClaimFlowProbe {

    private static final BlockPos CONTAINER_A = new BlockPos(140, 200, 40);
    private static final BlockPos CONTAINER_B = new BlockPos(160, 200, 40);
    private static final Path CONFIG_DIR = Path.of("config", "finiteloot-p4");
    private static final Path DATA_DIR = Path.of("finiteloot-p4");

    private static Scenario scenario;
    private static boolean started;

    private ClaimFlowProbe() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ProbeGate.enabled("p4")) {
            return;
        }
        if (started || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        started = true;
        try {
            scenario = new Scenario(player);
            ProbeMod.LOG.info("[P4] scenario armed for {}", player.getGameProfile().getName());
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[P4] setup FAILED", throwable);
            scenario = null;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (scenario == null) {
            return;
        }
        try {
            if (scenario.tick()) {
                scenario = null;
            }
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[P4] FAILED with exception", throwable);
            scenario.close();
            scenario = null;
        }
    }

    private static final class Scenario {

        private final ServerPlayer player;
        private final MinecraftServer server;
        private final RecordingPresenter presenter = new RecordingPresenter();
        private final ExhaustedContainers exhausted = new ExhaustedContainers();
        private final Database database;
        private final ContainerManager containers;
        private final ConfigManager config;
        private final ClaimController controller;

        private int phase;
        private int waitTicks;
        private UUID containerAId;
        private UUID containerBId;
        private String firstBContents;
        private int recordedEvents;

        Scenario(ServerPlayer player) throws Exception {
            this.player = player;
            this.server = player.server;

            // A real config file, so the override table feeds the claim limit through the same path
            // production uses.
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_DIR.resolve("config.yml"), """
                    default-max-claims: 3
                    show-remaining-claims: true
                    show-final-claim-message: true
                    clear-personal-inventories-on-final-claim: true
                    completed-container-becomes-normal: true
                    exhausted-action: DENY
                    per-player-limit: 1
                    final-claim-action: VANILLA_CONTAINER
                    loot-table-overrides:
                      minecraft:chests/simple_dungeon: 1
                      minecraft:chests/abandoned_mineshaft: 2
                    """, StandardCharsets.UTF_8);
            this.config = new ConfigManager(CONFIG_DIR);
            config.load();

            Files.createDirectories(DATA_DIR);
            this.database = new Database(DATA_DIR.resolve("claims.db"));
            database.initialize();

            this.containers = new ContainerManager(ProbeMod.LOG, config.settings()::isWorldExcluded);
            this.controller = new ClaimController(
                    server, database, containers, exhausted, presenter,
                    new LootGenerator(), new LootAdvancementService(),
                    config::settings, () -> new Messages(config.language()), ProbeMod.LOG);
        }

        /** @return true when the scenario is finished */
        boolean tick() throws Exception {
            ServerLevel level = server.overworld();
            switch (phase) {
                case 0 -> {
                    PluginSettings settings = config.settings();
                    ProbeMod.LOG.info("[P4] limits from config: simple_dungeon={} abandoned_mineshaft={} default={}",
                            settings.maxClaimsFor("minecraft:chests/simple_dungeon"),
                            settings.maxClaimsFor("minecraft:chests/abandoned_mineshaft"),
                            settings.defaultMaxClaims());

                    placeChest(level, CONTAINER_A, "chests/simple_dungeon");
                    placeChest(level, CONTAINER_B, "chests/abandoned_mineshaft");
                    containerAId = containers.resolve(level, CONTAINER_A).orElseThrow().id();
                    containerBId = containers.resolve(level, CONTAINER_B).orElseThrow().id();
                    ProbeMod.LOG.info("[P4] container A id={} (limit 1) container B id={} (limit 2)",
                            containerAId, containerBId);

                    Optional<ContainerTarget> targetA = containers.resolve(level, CONTAINER_A);
                    recordedEvents = presenter.events().size();
                    controller.open(player, targetA.orElseThrow());
                    phase = 1;
                }
                case 1 -> {
                    // Container A is limit 1, so the first claim is the final vanilla claim: the player
                    // should end up looking at the real container, never at a personal inventory.
                    if (presenter.events().size() == recordedEvents) {
                        return false;
                    }
                    RecordingPresenter.Presentation normal = presenter.lastOfKind("normal");
                    boolean sawNormal = normal != null && normal.containerId().equals(containerAId);
                    boolean neverPersonal = presenter.countOfKind("personal") == 0;
                    ProbeMod.LOG.info("[P4] A final-claim path: openNormal={} neverOpenedPersonal={} "
                                    + "lootWrittenIntoContainer={} ({})",
                            sawNormal, neverPersonal,
                            normal != null && normal.itemCount() > 0,
                            normal);
                    wait(3);
                    phase = 2;
                }
                case 2 -> {
                    if (!settled()) {
                        return false;
                    }
                    ContainerRecord row = database.findContainer(containerAId).join();
                    boolean cleared = row == null;
                    int inContainer = countItemsInContainer(level, CONTAINER_A);

                    // The two guarantees that stop the final claim from duplicating loot: the loot table
                    // must be gone (or vanilla would roll a fresh set on the next open) and the container
                    // must no longer be eligible for takeover (or the marker would start a second cycle).
                    boolean lootTableCleared = ((RandomizableContainerBlockEntity)
                            level.getBlockEntity(CONTAINER_A)).getLootTable() == null;
                    boolean noLongerManaged = containers.resolve(level, CONTAINER_A).isEmpty();

                    ProbeMod.LOG.info("[P4] A after transition: databaseRowCleared={} itemsLeftInRealContainer={}"
                                    + " lootTableCleared={} noLongerManaged={}",
                            cleared, inContainer, lootTableCleared, noLongerManaged);
                    boolean passA = cleared && inContainer > 0 && lootTableCleared && noLongerManaged;
                    ProbeMod.LOG.info("[P4] A {}", passA ? "PASS" : "FAIL");

                    Optional<ContainerTarget> targetB = containers.resolve(level, CONTAINER_B);
                    recordedEvents = presenter.events().size();
                    controller.open(player, targetB.orElseThrow());
                    phase = 3;
                }
                case 3 -> {
                    if (presenter.events().size() == recordedEvents) {
                        return false;
                    }
                    RecordingPresenter.Presentation personal = presenter.lastOfKind("personal");
                    boolean sawPersonal = personal != null && personal.containerId().equals(containerBId);
                    firstBContents = personal == null ? null : personal.fingerprint();
                    ProbeMod.LOG.info("[P4] B first claim: openedPersonal={} items={} fingerprint={}",
                            sawPersonal, personal == null ? -1 : personal.itemCount(), firstBContents);
                    boolean nonEmpty = personal != null && personal.itemCount() > 0;
                    ProbeMod.LOG.info("[P4] B first claim {}", sawPersonal && nonEmpty ? "PASS" : "FAIL");
                    wait(3);
                    phase = 4;
                }
                case 4 -> {
                    // Reopening must replay the stored inventory byte for byte; this is the round trip
                    // through the database that a restart would exercise.
                    if (!settled()) {
                        return false;
                    }
                    Optional<ContainerTarget> targetB = containers.resolve(level, CONTAINER_B);
                    recordedEvents = presenter.events().size();
                    controller.open(player, targetB.orElseThrow());
                    phase = 5;
                }
                case 5 -> {
                    if (presenter.events().size() == recordedEvents) {
                        return false;
                    }
                    RecordingPresenter.Presentation personal = presenter.lastOfKind("personal");
                    boolean sameContents = personal != null
                            && personal.fingerprint().equals(firstBContents);
                    ProbeMod.LOG.info("[P4] B reopen: openedPersonal={} identicalContents={}",
                            personal != null, sameContents);
                    ProbeMod.LOG.info("[P4] B reopen {}", sameContents ? "PASS" : "FAIL");
                    wait(2);
                    phase = 6;
                }
                case 6 -> {
                    // Fill container B with a second counted claimant, then reopen as the original player.
                    // They are no longer the last counted claimant, so the container must stay managed and
                    // they must be shown their own inventory rather than the real container.
                    if (!settled()) {
                        return false;
                    }
                    UUID secondPlayer = UUID.nameUUIDFromBytes("finiteloot-probe-second".getBytes(StandardCharsets.UTF_8));
                    ClaimAllocation allocation = database.allocateClaim(containerBId, secondPlayer,
                            "ProbeSecond", true, false).join();
                    byte[] generated = new byte[] {1, 2, 3};
                    database.finalizeClaim(containerBId, secondPlayer, generated, false).join();
                    ContainerRecord after = database.findContainer(containerBId).join();
                    ProbeMod.LOG.info("[P4] B after second claimant: allocation={} claimCount={}/{}",
                            allocation.kind(), after == null ? -1 : after.claimCount(),
                            after == null ? -1 : after.maxClaims());

                    Optional<ContainerTarget> targetB = containers.resolve(level, CONTAINER_B);
                    recordedEvents = presenter.events().size();
                    controller.open(player, targetB.orElseThrow());
                    phase = 7;
                }
                case 7 -> {
                    if (presenter.events().size() == recordedEvents) {
                        return false;
                    }
                    RecordingPresenter.Presentation personal = presenter.lastOfKind("personal");
                    boolean stillManaged = personal != null && personal.fingerprint().equals(firstBContents);
                    boolean containerUntouched = ((RandomizableContainerBlockEntity)
                            level.getBlockEntity(CONTAINER_B)).getLootTable() != null;
                    ProbeMod.LOG.info("[P4] B not-last-claimant: openedPersonal={} containerStillManaged={}",
                            stillManaged, containerUntouched);
                    ProbeMod.LOG.info("[P4] B not-last-claimant {}", stillManaged ? "PASS" : "FAIL");

                    // excluded-worlds had an implementation and tests but nothing consumed it, so the
                    // option was silently dead. Prove both halves of the rule: automatic resolution is
                    // blocked in an excluded world, while an explicit admin action still gets through.
                    ContainerManager excluding = new ContainerManager(ProbeMod.LOG,
                            "minecraft:overworld"::equals);
                    boolean blockedAutomatic = excluding.resolve(level, CONTAINER_B).isEmpty();
                    boolean allowedManual = excluding.resolve(level, CONTAINER_B, true).isPresent();
                    ContainerManager byBarePath = new ContainerManager(ProbeMod.LOG, "overworld"::equals);
                    boolean barePathMatches = byBarePath.resolve(level, CONTAINER_B).isEmpty();
                    ProbeMod.LOG.info("[P4] excluded-worlds: blockedAutomatic={} allowedManual={}"
                                    + " barePathAlsoMatches={}",
                            blockedAutomatic, allowedManual, barePathMatches);
                    ProbeMod.LOG.info("[P4] excluded-worlds {}",
                            blockedAutomatic && allowedManual && barePathMatches ? "PASS" : "FAIL");

                    boolean pass = presenter.countOfKind("normal") >= 1
                            && presenter.countOfKind("personal") >= 3
                            && blockedAutomatic && allowedManual && barePathMatches;
                    ProbeMod.LOG.info("[P4] summary: normalPresentations={} personalPresentations={}",
                            presenter.countOfKind("normal"), presenter.countOfKind("personal"));
                    ProbeMod.LOG.info("[P4] {} - claim flow works end to end", pass ? "SUCCESS" : "FAILURE");
                    close();
                    return true;
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        private void wait(int ticks) {
            waitTicks = ticks;
        }

        private boolean settled() {
            if (waitTicks > 0) {
                waitTicks--;
                return false;
            }
            return true;
        }

        private int countItemsInContainer(ServerLevel level, BlockPos pos) {
            var tile = level.getBlockEntity(pos);
            if (!(tile instanceof RandomizableContainerBlockEntity container)) {
                return -1;
            }
            int total = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).isEmpty()) {
                    total++;
                }
            }
            return total;
        }

        private void placeChest(ServerLevel level, BlockPos pos, String lootTablePath) {
            BlockState state = Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, ChestType.SINGLE);
            level.getChunk(pos);
            // Clear to air first so the block entity -- and with it any FiniteLoot attachment -- is
            // destroyed. Without this a rerun against an existing world inherits the previous run's
            // markers: a container released in an earlier run carries an "ignored" attachment, which
            // correctly blocks takeover, so the probe would silently find nothing to claim.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, state, 3);
            BlockEntity tile = level.getBlockEntity(pos);
            if (tile instanceof RandomizableContainerBlockEntity lootable) {
                lootable.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                        ResourceLocation.withDefaultNamespace(lootTablePath)));
                tile.setChanged();
            }
        }

        void close() {
            try {
                database.close();
            } catch (RuntimeException exception) {
                ProbeMod.LOG.warn("[P4] closing the probe database failed", exception);
            }
        }
    }
}
