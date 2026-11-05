package vip.naya.finiteloot;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import vip.naya.finiteloot.admin.AdminActions;
import vip.naya.finiteloot.claim.ClaimController;
import vip.naya.finiteloot.command.FiniteLootCommand;
import vip.naya.finiteloot.compat.PiglinAngerService;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.gui.RewardSessionManager;
import vip.naya.finiteloot.listener.WorldListener;
import vip.naya.finiteloot.loot.LootAdvancementService;
import vip.naya.finiteloot.loot.LootGenerator;

public final class FiniteLootPlugin extends JavaPlugin {
    private volatile PluginSettings settings;
    private volatile Messages messages;
    private Database database;
    private RewardSessionManager sessions;
    private PiglinAngerService piglinAnger;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            reloadPluginConfiguration();
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Invalid configuration; FiniteLoot cannot start", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        database = new Database(getDataFolder().toPath().resolve("data.db"));
        try {
            database.initialize();
        } catch (SQLException | IOException exception) {
            getLogger().log(Level.SEVERE, "Cannot initialize plugins/FiniteLoot/data.db", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        registerPermissions();
        ContainerManager containers = new ContainerManager(this);
        ExhaustedContainers exhausted = new ExhaustedContainers();
        piglinAnger = new PiglinAngerService(this, this::settings);
        sessions = new RewardSessionManager(this, database, this::settings);
        AdminActions adminActions = new AdminActions(
                this, database, containers, exhausted, sessions, this::messages);
        ClaimController claimController = new ClaimController(
                this, database, containers, exhausted, sessions, new LootGenerator(),
                new LootAdvancementService(), piglinAnger, this::settings, this::messages);
        WorldListener worldListener = new WorldListener(containers, claimController, adminActions, this::settings);
        Bukkit.getPluginManager().registerEvents(sessions, this);
        Bukkit.getPluginManager().registerEvents(piglinAnger, this);
        Bukkit.getPluginManager().registerEvents(worldListener, this);

        FiniteLootCommand command = new FiniteLootCommand(this, adminActions);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("finiteloot", command);
            event.registrar().register("fl", command);
        });

        Bukkit.getScheduler().runTask(this, this::warnAboutConflicts);
        scanExhaustedContainers(exhausted);
        getLogger().info("FiniteLoot enabled with database " + getDataFolder().toPath().resolve("data.db"));
    }

    @Override
    public void onDisable() {
        if (piglinAnger != null) {
            piglinAnger.clearAll();
        }
        if (sessions != null) {
            try {
                sessions.flushAll().get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Timed out while flushing personal inventories", exception);
            }
        }
        if (database != null) {
            try {
                database.close();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Failed to close FiniteLoot database safely", exception);
            }
        }
    }

    public void reloadPluginConfiguration() {
        reloadConfig();
        PluginSettings loaded = PluginSettings.from(getConfig());
        Messages loadedMessages = new Messages(this, loaded.language());
        settings = loaded;
        messages = loadedMessages;
    }

    public PluginSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    private void registerPermissions() {
        addPermission("finiteloot.reload", PermissionDefault.OP);
        addPermission("finiteloot.inspect", PermissionDefault.OP);
        addPermission("finiteloot.set", PermissionDefault.OP);
        addPermission("finiteloot.reset", PermissionDefault.OP);
        addPermission("finiteloot.remove", PermissionDefault.OP);
        addPermission("finiteloot.bypass", PermissionDefault.OP);
        Permission admin = addPermission("finiteloot.admin", PermissionDefault.OP);
        for (String child : List.of(
                "finiteloot.reload", "finiteloot.inspect", "finiteloot.set",
                "finiteloot.reset", "finiteloot.remove")) {
            admin.getChildren().put(child, true);
        }
        admin.recalculatePermissibles();
        Permission wildcard = addPermission("finiteloot.*", PermissionDefault.OP);
        wildcard.getChildren().put("finiteloot.admin", true);
        wildcard.getChildren().put("finiteloot.bypass", true);
        wildcard.recalculatePermissibles();
    }

    private Permission addPermission(String name, PermissionDefault defaultValue) {
        Permission existing = Bukkit.getPluginManager().getPermission(name);
        if (existing != null) {
            return existing;
        }
        Permission permission = new Permission(name, defaultValue);
        Bukkit.getPluginManager().addPermission(permission);
        return permission;
    }

    private void scanExhaustedContainers(ExhaustedContainers exhausted) {
        database.listExhaustedContainerIds().whenComplete((ids, throwable) -> {
            if (throwable != null) {
                getLogger().log(Level.WARNING, "Failed to scan for fully-claimed containers", throwable);
                return;
            }
            if (ids.isEmpty()) {
                return;
            }
            exhausted.markAllExhausted(ids);
            if (settings.allowBreakingExhaustedContainers()) {
                getLogger().info("Found " + ids.size() + " fully-claimed containers in the database;"
                        + " each reverts to a normal breakable container when next clicked or broken.");
            } else {
                getLogger().info("Found " + ids.size() + " fully-claimed containers in the database that"
                        + " remain protected; set allow-breaking-exhausted-containers: true to release them.");
            }
        });
    }

    private void warnAboutConflicts() {
        List<String> conflicts = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            String name = plugin.getName().toLowerCase(java.util.Locale.ROOT);
            if (!plugin.equals(this) && (name.contains("lootr") || name.contains("lootin"))) {
                conflicts.add(plugin.getName());
            }
        }
        if (!conflicts.isEmpty()) {
            messages.send(Bukkit.getConsoleSender(), "conflict", java.util.Map.of(
                    "plugins", String.join(", ", conflicts)));
        }
    }
}
