package vip.naya.finiteloot.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import vip.naya.finiteloot.FiniteLootPlugin;
import vip.naya.finiteloot.admin.AdminActions;
import vip.naya.finiteloot.admin.PendingAction;
import vip.naya.finiteloot.config.Messages;

public final class FiniteLootCommand implements BasicCommand {
    private static final List<String> ROOT_SUGGESTIONS = List.of(
            "reload", "inspect", "set", "reset", "remove", "migrate");
    private final FiniteLootPlugin plugin;
    private final AdminActions actions;

    public FiniteLootCommand(FiniteLootPlugin plugin, AdminActions actions) {
        this.plugin = plugin;
        this.actions = actions;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        Messages messages = plugin.messages();
        if (args.length == 0) {
            messages.send(sender, "usage");
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                if (!allowed(sender, "finiteloot.reload")) {
                    return;
                }
                try {
                    plugin.reloadPluginConfiguration();
                    plugin.messages().send(sender, "reload-ok");
                } catch (IllegalArgumentException exception) {
                    sender.sendMessage(exception.getMessage());
                }
            }
            case "inspect" -> arm(sender, "finiteloot.inspect",
                    PendingAction.simple(PendingAction.Type.INSPECT));
            case "set" -> handleSet(sender, args);
            case "reset" -> handleReset(sender, args);
            case "remove" -> arm(sender, "finiteloot.remove",
                    PendingAction.simple(PendingAction.Type.REMOVE));
            case "migrate" -> {
                if (allowed(sender, "finiteloot.admin")) {
                    actions.migrate(sender);
                }
            }
            default -> messages.send(sender, "usage");
        }
    }

    @Override
    public @NotNull Collection<String> suggest(
            @NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
            return ROOT_SUGGESTIONS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "reset".equalsIgnoreCase(args[0])) {
            return List.of("container", "player");
        }
        if (args.length == 3 && "reset".equalsIgnoreCase(args[0])
                && "player".equalsIgnoreCase(args[1])) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!allowed(sender, "finiteloot.set")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (args.length != 2) {
            plugin.messages().send(sender, "usage");
            return;
        }
        try {
            int max = Integer.parseInt(args[1]);
            if (max <= 0) {
                throw new NumberFormatException("non-positive");
            }
            actions.arm(player, new PendingAction(PendingAction.Type.SET, max, null));
        } catch (NumberFormatException exception) {
            plugin.messages().send(sender, "invalid-number");
        }
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (!allowed(sender, "finiteloot.reset")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (args.length == 2 && "container".equalsIgnoreCase(args[1])) {
            actions.arm(player, PendingAction.simple(PendingAction.Type.RESET_CONTAINER));
            return;
        }
        if (args.length == 3 && "player".equalsIgnoreCase(args[1])) {
            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                plugin.messages().send(sender, "invalid-player");
                return;
            }
            actions.arm(player, new PendingAction(PendingAction.Type.RESET_PLAYER, null, target.getUniqueId()));
            return;
        }
        plugin.messages().send(sender, "usage");
    }

    private void arm(CommandSender sender, String permission, PendingAction action) {
        if (!allowed(sender, permission)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        actions.arm(player, action);
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission("finiteloot.admin") || sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "no-permission");
        return false;
    }
}
