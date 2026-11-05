package vip.naya.finiteloot.command;

import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /fl} command tree.
 *
 * <p>Paper's version was a flat string switch inside a {@code BasicCommand}. Brigadier is the native
 * mechanism here, so the tree is declared rather than parsed: argument types, suggestions and usage text all
 * come from the framework instead of hand-rolled {@code String[]} handling.
 *
 * <p>The command list is deliberately identical, with one addition: {@code adopt}. Paper cleared a
 * container's identity on release and had no way back; here the release leaves an "ignored" flag that blocks
 * re-takeover forever, so an undo is mandatory rather than nice to have.
 *
 * <p>{@code reset player} takes an <em>online</em> player. Paper resolved offline players by name through
 * {@code Bukkit.getOfflinePlayer}, which the game has no synchronous equivalent for -- the profile cache is
 * asynchronous. Online-only is the honest limitation, and an offline reset is what {@code reset container}
 * plus {@code remove} cover in the meantime.
 */
public final class FiniteLootCommands {

    private FiniteLootCommands() {
    }

    /** Registered from {@code RegisterCommandsEvent}; the tree itself needs no runtime state. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            RuntimeLookup runtime) {
        dispatcher.register(tree("fl", runtime));
        dispatcher.register(tree("finiteloot", runtime));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(String name, RuntimeLookup runtime) {
        return Commands.literal(name)
                .requires(AdminGate::allowed)
                .then(Commands.literal("reload").executes(context -> {
                    runtime.get().reload();
                    context.getSource().sendSuccess(
                            () -> runtime.get().messages().prefixed("reload-ok"), false);
                    return 1;
                }))
                .then(Commands.literal("inspect")
                        .executes(context -> arm(context, runtime, PendingAction.simple(PendingAction.Type.INSPECT))))
                .then(Commands.literal("set")
                        .then(Commands.argument("max", IntegerArgumentType.integer(1))
                                .executes(context -> arm(context, runtime, new PendingAction(
                                        PendingAction.Type.SET,
                                        IntegerArgumentType.getInteger(context, "max"),
                                        null)))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("container")
                                .executes(context -> arm(context, runtime,
                                        PendingAction.simple(PendingAction.Type.RESET_CONTAINER))))
                        .then(Commands.literal("player")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                            return arm(context, runtime, new PendingAction(
                                                    PendingAction.Type.RESET_PLAYER, null,
                                                    target.getUUID()));
                                        }))))
                .then(Commands.literal("remove")
                        .executes(context -> arm(context, runtime, PendingAction.simple(PendingAction.Type.REMOVE))))
                // Not in Paper: the undo for a released container. See the class comment.
                .then(Commands.literal("adopt")
                        .executes(context -> arm(context, runtime, PendingAction.simple(PendingAction.Type.ADOPT))))
                .then(Commands.literal("migrate").executes(context -> {
                    runtime.get().admin().migrate(context.getSource());
                    return 1;
                }));
    }

    private static int arm(CommandContext<CommandSourceStack> context, RuntimeLookup runtime,
            PendingAction action) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            // Arm-then-click needs a clicker. Paper sent "players-only" here too.
            source.sendFailure(runtime.get().messages().component("players-only"));
            return 0;
        }
        runtime.get().admin().arm(player, action);
        return 1;
    }

    /**
     * Indirection so the command tree can be registered before the server has finished starting.
     *
     * <p>Commands are registered while the server boots; the database and the session manager are built when
     * it has started. Threading a live instance through the tree would mean ordering the two, so the tree
     * holds a lookup instead and resolves it at execution time.
     */
    public interface RuntimeLookup {
        vip.naya.finiteloot.FiniteLootRuntime get();
    }
}
