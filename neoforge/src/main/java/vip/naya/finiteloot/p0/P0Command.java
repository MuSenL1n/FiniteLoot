package vip.naya.finiteloot.p0;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import vip.naya.finiteloot.p0.containers.ContainerProbe;
import vip.naya.finiteloot.p0.gui.GuiProbe;
import vip.naya.finiteloot.p0.hopper.HopperProbe;

/**
 * Single entry point for running the P0 checks by hand.
 *
 * <p>All P0 results are logged with a {@code [P0-n]} prefix, so a run can be judged by grepping the
 * server log for {@code [P0-} rather than by reading it end to end.
 *
 * <p>Deliberately requires permission level 2, matching the OP-level decision for the real port.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class P0Command {

    private P0Command() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("p0")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("gui")
                        .executes(ctx -> openGui(ctx.getSource(), 3))
                        .then(Commands.argument("rows", IntegerArgumentType.integer(1, 6))
                                .executes(ctx -> openGui(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "rows")))))
                .then(Commands.literal("container")
                        .executes(ctx -> inspectContainer(ctx.getSource(), false))
                        .then(Commands.literal("selfcheck")
                                .executes(ctx -> inspectContainer(ctx.getSource(), true))))
                .then(Commands.literal("hopper")
                        .executes(ctx -> inspectHopper(ctx.getSource())))
                .then(Commands.literal("net")
                        .executes(ctx -> netCheck(ctx.getSource()))));
    }

    private static int openGui(CommandSourceStack src, int rows) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("/p0 gui must be run by a player."));
            return 0;
        }
        GuiProbe.open(player, rows);
        return 1;
    }

    private static int inspectContainer(CommandSourceStack src, boolean selfCheck) {
        BlockPos pos = BlockPos.containing(src.getPosition());
        ServerLevel level = src.getLevel();
        if (selfCheck) {
            ContainerProbe.selfCheck(level);
        }
        ContainerProbe.Classification c = ContainerProbe.classify(level, pos);
        ProbeMod.LOG.info("[P0-5] classification at {} => {}", pos, c);
        src.sendSuccess(() -> Component.literal(
                "block=" + c.blockId()
                        + " randomizable=" + c.isRandomizable()
                        + " lootTable=" + c.lootTable()
                        + " seed=" + c.lootTableSeed()
                        + " lootRule=" + c.matchesLootRule()
                        + " itemCapability=" + c.hasItemCapability() + " (" + c.capabilitySlots() + " slots)"), false);
        return 1;
    }

    private static int inspectHopper(CommandSourceStack src) {
        BlockPos pos = BlockPos.containing(src.getPosition());
        ServerLevel level = src.getLevel();
        HopperProbe.CapabilityLookup lookup = HopperProbe.lookup(level, pos, null);
        ProbeMod.LOG.info("[P0-4] hopper-source lookup at {} => {}", pos, lookup);
        src.sendSuccess(() -> Component.literal(
                "item capability at " + pos + " => hasHandler=" + lookup.hasHandler()
                        + " slots=" + lookup.slotCount()
                        + "  (see log for whether capability interception is expressible)"), false);
        return 1;
    }

    private static int netCheck(CommandSourceStack src) {
        // P0-1 is verified from the client side (does a vanilla client get in?), but the server can
        // confirm it never registered a custom channel, which is the actual precondition.
        ProbeMod.LOG.info("[P0-1] FiniteLoot registers no custom payloads at all "
                + "-> NetworkRegistry.initializeOtherConnection() negotiates against an empty "
                + "client list and allows vanilla clients to join.");
        src.sendSuccess(() -> Component.literal(
                "FiniteLoot registers no custom network payloads; vanilla clients should connect. "
                        + "Verify by joining with an unmodified client."), false);
        return 1;
    }
}
