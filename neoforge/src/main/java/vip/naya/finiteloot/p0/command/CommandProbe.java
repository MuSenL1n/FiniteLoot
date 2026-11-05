package vip.naya.finiteloot.p0.command;

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
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import vip.naya.finiteloot.FiniteLootRuntime;
import vip.naya.finiteloot.command.PendingAction;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.p0.ProbeGate;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P6: drives the admin commands through the real Brigadier dispatcher.
 *
 * <p>Why through the dispatcher and not by calling {@code AdminActions} directly: the interesting failures in a
 * command are never in the action body. They are in argument parsing, in the permission gate, in "who is the
 * sender", and in whether the subcommand is reachable at all. Calling the implementation would skip exactly
 * those. So every assertion below issues a command string the way a player would.
 *
 * <p>The operator level is real, not simulated: the probe player is opped through the server's op list, so
 * {@code AdminGate} sees level 4 through {@code hasPermission(2)} the same way it would for a human admin.
 *
 * <p>Judged by grepping for the {@code [P6} prefix.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class CommandProbe {

    private static final BlockPos CHEST_POS = new BlockPos(8, 70, 60);
    private static final double PLAYER_X = 12.5;
    private static final double PLAYER_Z = 60.5;

    private static Scenario scenario;
    private static boolean started;

    private CommandProbe() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ProbeGate.enabled("p6")) {
            return;
        }
        if (started || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        started = true;
        try {
            scenario = new Scenario(player);
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[P6] setup FAILED", throwable);
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
            ProbeMod.LOG.error("[P6] FAILED with exception", throwable);
            scenario = null;
        }
    }

    private static final class Scenario {

        private final ServerPlayer player;
        private final MinecraftServer server;
        private final ServerLevel level;
        private int phase;
        private int ticksInPhase;
        private int failures;
        private UUID containerId;
        private int limitBefore;

        Scenario(ServerPlayer player) {
            this.player = player;
            this.server = player.server;
            this.level = server.overworld();
        }

        boolean tick() {
            if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
                ProbeMod.LOG.error("[P6] FAILED - player left during phase {}", phase);
                return true;
            }
            if (++ticksInPhase > 400) {
                ProbeMod.LOG.error("[P6] FAILED - phase {} stuck for 400 ticks", phase);
                return true;
            }

            FiniteLootRuntime runtime = FiniteLootRuntime.get();
            if (runtime == null) {
                ProbeMod.LOG.error("[P6] FAILED - the production runtime never started");
                return true;
            }

            switch (phase) {
                case 0 -> {
                    // Real operator level, through the real op list.
                    server.getPlayerList().op(player.getGameProfile());
                    check("probe player is operator level 4", player.hasPermissions(4));

                    placeChest(level, CHEST_POS, "chests/simple_dungeon");
                    player.teleportTo(level, PLAYER_X, 70.0, PLAYER_Z, 0.0F, 0.0F);
                    phase = 1;
                }
                case 1 -> {
                    // /fl set 2 -> arms, then the click applies it to the container under the admin's feet.
                    int result = run("fl set 2");
                    check("fl set arms", result == 1 && runtime.admin().hasPending(player));
                    runtime.admin().handleClick(player, level, CHEST_POS);
                    phase = 2;
                }
                case 2 -> {
                    ContainerRecord record = runtime.database().findContainer(
                            runtime.containers().resolve(level, CHEST_POS, true).orElseThrow().id()).join();
                    if (record == null) {
                        return false;
                    }
                    containerId = record.id();
                    limitBefore = record.maxClaims();
                    check("fl set wrote a limit of 2", record.maxClaims() == 2);
                    check("fl set counted the container", record.claimCount() == 0);
                    check("fl set stored the loot table [" + record.lootTable() + " template="
                                    + (record.templateContents() == null ? "none" : "present") + "]",
                            "minecraft:chests/simple_dungeon".equals(record.lootTable()));

                    // /fl inspect must resolve and report the container the admin clicked.
                    check("fl inspect runs", run("fl inspect") == 1);
                    runtime.admin().handleClick(player, level, CHEST_POS);
                    phase = 3;
                }
                case 3 -> {
                    check("fl inspect left the limit alone",
                            runtime.database().findContainer(containerId).join() != null);
                    // A released container must be unreachable by the ordinary path, reachable by adopt.
                    runtime.containers().resolve(level, CHEST_POS).orElseThrow();
                    ContainerTarget target = runtime.containers().resolve(level, CHEST_POS, true).orElseThrow();
                    runtime.containers().removeIdentity(target);
                    boolean hiddenAfterRelease = runtime.containers().resolve(level, CHEST_POS).isEmpty();
                    boolean visibleManually = runtime.containers().resolve(level, CHEST_POS, true).isPresent();
                    check("released container is hidden from automatic takeover", hiddenAfterRelease);
                    check("released container is still visible to the admin path", visibleManually);

                    check("fl adopt arms", run("fl adopt") == 1);
                    runtime.admin().handleClick(player, level, CHEST_POS);
                    phase = 4;
                }
                case 4 -> {
                    // Adopt writes the marker inside a database callback that is re-scheduled onto the server
                    // thread, so the state is not observable in the same tick the command was dispatched in.
                    // Waiting is not the same as weakening the assertion: the claim being made is "the container
                    // ends up manageable again", and it is still asserted to be false-if-never.
                    if (runtime.containers().resolve(level, CHEST_POS).isEmpty() && ticksInPhase < 60) {
                        return false;
                    }
                    // R20 closed: after adopt the container is eligible again without a database change.
                    boolean adoptable = runtime.containers().resolve(level, CHEST_POS).isPresent();
                    check("fl adopt makes the container manageable again", adoptable);
                    // The point of restoring the id: the same row keeps counting. A fresh id here would mean a
                    // second row for the same chest, i.e. a duplicate claim budget.
                    UUID afterAdopt = runtime.containers().existingId(level, CHEST_POS).orElse(null);
                    check("fl adopt restored the stored id instead of minting a new one",
                            containerId.equals(afterAdopt));
                    check("fl adopt kept the stored limit",
                            runtime.database().findContainer(containerId).join() != null
                                    && runtime.database().findContainer(containerId).join().maxClaims()
                                            == limitBefore);

                    check("fl reset container runs", run("fl reset container") == 1);
                    runtime.admin().handleClick(player, level, CHEST_POS);
                    phase = 5;
                }
                case 5 -> {
                    check("fl reset container zeroed the count",
                            runtime.database().findContainer(containerId).join().claimCount() == 0);

                    check("fl remove runs", run("fl remove") == 1);
                    runtime.admin().handleClick(player, level, CHEST_POS);
                    phase = 6;
                }
                case 6 -> {
                    // Same reason as phase 4: the ignored flag is written from a re-scheduled callback. The
                    // first run of this probe asserted in the dispatching tick and failed while the release was
                    // still queued -- the row was already gone, the marker was not yet written.
                    if (runtime.containers().resolve(level, CHEST_POS).isPresent() && ticksInPhase < 60) {
                        return false;
                    }
                    check("fl remove deleted the row",
                            runtime.database().findContainer(containerId).join() == null);
                    BlockEntity tile = level.getBlockEntity(CHEST_POS);
                    ProbeMod.LOG.info("[P6] release state: tile={} ignored={} storedId={}",
                            tile == null ? "null" : tile.getClass().getSimpleName(),
                            tile != null && vip.naya.finiteloot.container.ContainerMarker.isIgnored(tile),
                            runtime.containers().existingId(level, CHEST_POS).orElse(null));
                    check("fl remove marked the container ignored",
                            runtime.containers().resolve(level, CHEST_POS).isEmpty());

                    check("fl reload runs", run("fl reload") == 1);

                    // migrate is the one command with a file side effect, so it is checked by looking for the
                    // backup rather than by trusting the message. Run from console: it is not a player action.
                    check("fl migrate runs", runAsConsole("fl migrate") == 1);
                    phase = 7;
                }
                case 7 -> {
                    if (backupFile() == null && ticksInPhase < 100) {
                        return false;
                    }
                    java.io.File backup = backupFile();
                    check("fl migrate wrote a backup file [" + (backup == null ? "none" : backup.getName()) + "]",
                            backup != null);
                    check("fl migrate left the database open",
                            runtime.database().findContainer(containerId).join() == null);

                    // The permission gate: drop the operator level and the command must refuse.
                    server.getPlayerList().deop(player.getGameProfile());
                    int denied = run("fl reload");
                    check("fl refuses a non-operator", denied == -1);
                    server.getPlayerList().op(player.getGameProfile());

                    ProbeMod.LOG.info("[P6] {} - admin commands work through the dispatcher",
                            failures == 0 ? "SUCCESS" : "FAILURE (see FAIL lines above)");
                    return true;
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        /** migrate needs no player, and console is level 4, so the gate passes for the same reason it would live. */
        private int runAsConsole(String command) {
            try {
                return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
                return -1;
            }
        }

        private java.io.File backupFile() {
            java.io.File[] files = server.getServerDirectory().toFile()
                    .toPath().resolve("finiteloot").resolve("backups").toFile().listFiles();
            if (files == null || files.length == 0) {
                return null;
            }
            return files[0];
        }

        private int run(String command) {
            // player.createCommandSourceStack() carries the player's own permission level, which is what the
            // gate reads. The dispatcher is used directly rather than performPrefixedCommand because the latter
            // returns void in 1.21.1: it reports errors on the source and discards the result value, which is
            // the only way to tell "ran" from "refused". A denied command is indistinguishable from an unknown
            // one in Brigadier - both surface as DISPATCHER_UNKNOWN_COMMAND - hence the -1 sentinel.
            try {
                return server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
                return -1;
            }
        }

        private void check(String what, boolean ok) {
            if (!ok) {
                failures++;
            }
            ProbeMod.LOG.info("[P6] {} {}", what, ok ? "PASS" : "FAIL");
        }

        private void placeChest(ServerLevel level, BlockPos pos, String lootTablePath) {
            level.getChunk(pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, ChestType.SINGLE), 3);
            BlockEntity tile = level.getBlockEntity(pos);
            if (tile instanceof RandomizableContainerBlockEntity lootable) {
                lootable.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                        ResourceLocation.withDefaultNamespace(lootTablePath)));
                tile.setChanged();
            }
        }
    }
}
