package vip.naya.finiteloot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.slf4j.Logger;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import vip.naya.finiteloot.claim.ClaimController;
import vip.naya.finiteloot.command.AdminActions;
import vip.naya.finiteloot.command.FiniteLootCommands;
import vip.naya.finiteloot.config.ConfigManager;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.gui.RewardSessions;
import vip.naya.finiteloot.loot.LootAdvancementService;
import vip.naya.finiteloot.loot.LootGenerator;

import static vip.naya.finiteloot.p0.ProbeMod.LOG;
import static vip.naya.finiteloot.p0.ProbeMod.MOD_ID;

/**
 * The production object graph, built once per server start.
 *
 * <p>Until P6 every piece existed only inside probes: the probes constructed a database, a container manager
 * and a claim controller by hand and drove them directly. That was the right trade while each layer was being
 * proven, but it means nothing was actually wired together -- a player joining the server would have found no
 * listener, no command and no database. This class is that missing wiring.
 *
 * <p>Lifecycle and ordering, which is the whole reason this is its own class:
 *
 * <ol>
 *   <li>{@link RegisterCommandsEvent} fires while the server boots, <em>before</em> this runtime exists. The
 *       command tree therefore holds a lookup rather than an instance.
 *   <li>{@link ServerStartedEvent} builds the graph: config, database, services, then the admin actions.
 *   <li>{@link ServerStoppingEvent} flushes open personal-loot windows and closes the database, in that order
 *       -- a save that lands after {@code close()} would be rejected by the executor (observed in P5).
 * </ol>
 *
 * <p>Paths mirror Paper's {@code getDataFolder()} layout so an operator moving between the two builds finds the
 * same files in the same places: {@code config/finiteloot/config.yml}, {@code config/finiteloot/lang/*.yml},
 * {@code finiteloot/claims.db}.
 */
@EventBusSubscriber(modid = MOD_ID)
public final class FiniteLootRuntime {

    private static volatile FiniteLootRuntime instance;

    private final MinecraftServer server;
    private final Logger logger;
    private final ConfigManager config;
    private final Database database;
    private final ContainerManager containers;
    private final RewardSessions sessions;
    private final ClaimController claims;
    private final AdminActions admin;

    private FiniteLootRuntime(MinecraftServer server, Path root) throws IOException, java.sql.SQLException {
        this.server = server;
        this.logger = LOG;
        this.config = new ConfigManager(root.resolve("config").resolve("finiteloot"));
        config.load();

        Path dataDirectory = root.resolve("finiteloot");
        java.nio.file.Files.createDirectories(dataDirectory);
        this.database = new Database(dataDirectory.resolve("claims.db"));
        database.initialize();

        this.containers = new ContainerManager(LOG, config.settings()::isWorldExcluded);
        this.sessions = new RewardSessions(server, database, config::settings, LOG);
        // One instance, shared: the exhausted set is in-memory state that the claim path writes and the
        // administrative path clears. Two instances would mean "/fl reset" clearing a copy nobody reads.
        ExhaustedContainers exhausted = new ExhaustedContainers();
        this.claims = new ClaimController(
                server, database, containers, exhausted, sessions,
                new LootGenerator(), new LootAdvancementService(),
                config::settings, this::messages, LOG);
        this.admin = new AdminActions(server, database, containers, exhausted, sessions,
                config::settings, this::messages, dataDirectory, LOG);
    }

    /** The live runtime, or {@code null} before the server has started. */
    public static FiniteLootRuntime get() {
        return instance;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (instance != null) {
            return;
        }
        try {
            instance = new FiniteLootRuntime(event.getServer(), Path.of("."));
            LOG.info("[RUNTIME] FiniteLoot ready: config at {}, database at {}",
                    instance.config.configFile(), instance.server.getServerDirectory());
        } catch (Exception exception) {
            // A broken config must not take the server down with it; without a runtime the mod is inert, which
            // is visible (no commands) and harmless.
            LOG.error("[RUNTIME] FiniteLoot could not start; the mod will stay inactive", exception);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FiniteLootRuntime runtime = instance;
        instance = null;
        if (runtime == null) {
            return;
        }
        try {
            // Order matters: flush waits on writes, close shuts the executor down. Reversing them produced
            // "RejectedExecutionException ... Terminated" in P5.
            runtime.sessions.flushAll().join();
        } catch (RuntimeException exception) {
            runtime.logger.warn("[RUNTIME] Could not flush open reward windows", exception);
        }
        runtime.database.close();
        runtime.logger.info("[RUNTIME] FiniteLoot stopped");
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        FiniteLootCommands.register(event.getDispatcher(), FiniteLootRuntime::get);
    }

    public Messages messages() {
        return new Messages(config.language());
    }

    public void reload() {
        try {
            config.load();
        } catch (IOException | RuntimeException exception) {
            logger.error("[RUNTIME] Reload failed", exception);
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    public MinecraftServer server() {
        return server;
    }

    public ConfigManager config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public ContainerManager containers() {
        return containers;
    }

    public RewardSessions sessions() {
        return sessions;
    }

    public ClaimController claims() {
        return claims;
    }

    public AdminActions admin() {
        return admin;
    }

    /** The pieces the interaction layer (P7) will need, exposed as suppliers to avoid holding stale state. */
    public Supplier<vip.naya.finiteloot.config.PluginSettings> settings() {
        return config::settings;
    }
}
