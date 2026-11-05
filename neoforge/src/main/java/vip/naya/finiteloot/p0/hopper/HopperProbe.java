package vip.naya.finiteloot.p0.hopper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P0-4: the highest-risk item in the migration plan (MIGRATION-PLAN R1).
 *
 * <p>On Paper, {@code InventoryMoveItemEvent} intercepts hopper extraction in one line. NeoForge
 * rewrites hopper logic (see the {@code HopperBlockEntity} patch) so a vanilla {@code Container}
 * target is served through the plain vanilla path, which fires <em>no</em> NeoForge event.
 *
 * <p>This probe answers the only two questions that decide the real port's approach, using public
 * API only:
 *
 * <ol>
 *   <li><b>Is a capability-based interception expressible?</b> If {@link RegisterCapabilitiesEvent}
 *       fires, option (b) in the plan is available and we might not need a Mixin at all.
 *   <li><b>Is the item capability actually populated for container blocks?</b> If a vanilla chest
 *       exposes {@code Capabilities.ItemHandler.BLOCK}, then hiding a managed container from
 *       automation is a matter of overriding that provider rather than patching hopper code.
 * </ol>
 *
 * <p>The probe never mutates world state, so it is safe to run against a live save.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class HopperProbe {
    private static boolean capabilityRegistrationSeen;
    private static boolean capabilityQuerySucceeded;
    private static boolean capabilityQueryReturnedHandler;
    private static long serverTicks;
    private static int ourProviderInvocations;
    private static boolean reportedWinningProvider;
    private static int genericProviderInvocations;
    private static int genericProviderWins;
    private static int genericTypesClaimed;

    /**
     * Positions whose extraction is refused. Per-position rather than one global flag because two
     * rigs run concurrently: the chest rig must be allowed to move items in its phase 1 while the
     * barrel rig is already being denied.
     */
    private static final java.util.Set<BlockPos> denyPositions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Refuse extraction from this position. */
    public static void denyAt(BlockPos pos) {
        denyPositions.add(pos.immutable());
    }

    /** Stop refusing extraction from this position. */
    public static void allowAt(BlockPos pos) {
        denyPositions.remove(pos);
    }

    private HopperProbe() {
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        // Gated like every other probe. Registering the wrapper is not free: it intercepts every hopper
        // extraction in the world and logs one line per attempt, which is right for the P0-4 experiment
        // and wrong for anything else. P7 replaces this with the real protection, gated on config rather
        // than on a probe flag.
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p0")) {
            return;
        }
        capabilityRegistrationSeen = true;
        ProbeMod.LOG.info(
                "[P0-4] RegisterCapabilitiesEvent fired (HIGHEST priority) -> capability-based "
                        + "interception IS expressible.");

        // DECISIVE TEST: NeoForge registers its own ItemHandler provider for Blocks.CHEST in
        // CapabilityHooks#registerVanillaProviders. BlockCapability#getCapability walks the provider
        // list in registration order and returns the FIRST non-null result. So registering a second
        // provider only works if ours is consulted before NeoForge's -- i.e. only if this event
        // listener runs first. If the result is ever a MarkedItemHandler, ordering favours us and
        // hopper interception needs no Mixin; if it never is, our provider is dead code.
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, blockEntity, side) -> {
            ourProviderInvocations++;
            IItemHandler inner = blockEntity instanceof net.minecraft.world.Container container
                    ? new net.neoforged.neoforge.items.wrapper.InvWrapper(container)
                    : null;
            if (inner == null) {
                return null; // fall through to NeoForge's provider
            }
            if (ourProviderInvocations <= 3) {
                ProbeMod.LOG.info("[P0-4] OUR provider RAN for pos={} side={} -> wrapping", pos, side);
            }
            return new MarkedItemHandler(inner, denyPositions.contains(pos));
        }, net.minecraft.world.level.block.Blocks.CHEST);

        registerGenericTakeover(event);
    }

    /**
     * P0-4b: can <em>every</em> loot container be taken over generically, without naming any block?
     *
     * <p>The user's requirement is "every loot-bearing container in the pack", so a hard-coded block
     * list is off the table. The site where modded containers are registered is
     * {@code RegisterCapabilitiesEvent#registerBlockEntity}, which expands to
     * {@code blockEntityType.getValidBlocks()} -- a different code path from the {@code Blocks.CHEST}
     * registration NeoForge uses for chests. That is why the chest result does not transfer on its own.
     *
     * <p>This walks the whole block-entity registry and claims a provider for every type, deciding at
     * query time (via {@code instanceof Container}) whether the block entity really is a container.
     * That needs no class introspection and no mod-specific knowledge, so it is the generic rule the
     * real port needs. Registering for non-container types is harmless: the provider returns null for
     * them and the lookup falls through.
     */
    private static void registerGenericTakeover(RegisterCapabilitiesEvent event) {
        int claimed = 0;
        for (var type : net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (type.getValidBlocks().isEmpty()) {
                continue; // no block maps to it, so no lookup could ever reach it
            }
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type,
                    (blockEntity, side) -> {
                        genericProviderInvocations++;
                        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
                            return null; // not a container -> let the owning mod's provider answer
                        }
                        if (genericProviderWins <= 3) {
                            genericProviderWins++;
                            ProbeMod.LOG.info(
                                    "[P0-4b] GENERIC provider took over {} at {} (side={})",
                                    blockEntity.getClass().getSimpleName(), blockEntity.getBlockPos(), side);
                        }
                        return new MarkedItemHandler(
                                new net.neoforged.neoforge.items.wrapper.InvWrapper(container),
                                denyPositions.contains(blockEntity.getBlockPos()));
                    });
            claimed++;
        }
        genericTypesClaimed = claimed;
        ProbeMod.LOG.info("[P0-4b] generic takeover registered for {} block entity types "
                + "(no block names hard-coded)", claimed);
    }

    /** Block entity types claimed by the generic takeover, for reporting. */
    public static int genericTypesClaimed() {
        return genericTypesClaimed;
    }

    /** How many times the generic provider was consulted. */
    public static int genericInvocations() {
        return genericProviderInvocations;
    }

    /** How many times the generic provider actually took over a container. */
    public static int genericWins() {
        return genericProviderWins;
    }

    /**
     * Queries the item capability for blocks that hopper logic would treat as sources.
     *
     * <p>This is exactly the lookup the real port needs: it tells us whether a candidate container is
     * automation-accessible, and therefore whether hopper protection must apply.
     *
     * <p>On NeoForge 1.21.1 the capability constant is {@code Capabilities.ItemHandler.BLOCK} and the
     * lookup is {@code level.getCapability(cap, pos, side)}. (The 26.x name
     * {@code Capabilities.Item.BLOCK} does not exist here -- see MIGRATION-PLAN R2.)
     */
    public static CapabilityLookup lookup(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        capabilityQuerySucceeded = true;
        if (handler != null) {
            capabilityQueryReturnedHandler = true;
        }
        boolean ours = MarkedItemHandler.isOurs(handler);
        if (!reportedWinningProvider) {
            reportedWinningProvider = true;
            ProbeMod.LOG.info(
                    "[P0-4] provider ordering result: handlerIsNull={} handlerIsOurs={} ourProviderInvocations={}",
                    handler == null, ours, ourProviderInvocations);
            if (!ours && handler != null) {
                ProbeMod.LOG.info(
                        "[P0-4] VERDICT: NeoForge's provider won -> a second provider for the same block "
                                + "is DEAD CODE. Capability override alone cannot intercept hopper extraction; "
                                + "MIGRATION-PLAN option (b) is DEAD. Use option (a) Mixin.");
            } else if (ours) {
                ProbeMod.LOG.info(
                        "[P0-4] VERDICT: OUR provider won -> capability override IS viable (option b).");
            }
        }
        return new CapabilityLookup(handler != null, handler == null ? 0 : handler.getSlots(), ours);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!vip.naya.finiteloot.p0.ProbeGate.enabled("p0")) {
            return;
        }
        serverTicks++;
        if (serverTicks % 1200L != 0L) {
            return; // every ~60s
        }
        MinecraftServer server = event.getServer();
        ProbeMod.LOG.info(
                "[P0-4] heartbeat ticks={} loadedLevels={} capabilityRegistrationSeen={} capabilityQueried={} handlerSeen={}",
                serverTicks,
                server.getAllLevels().spliterator().estimateSize(),
                capabilityRegistrationSeen,
                capabilityQuerySucceeded,
                capabilityQueryReturnedHandler);
    }

    /** Result of a P0-4 capability probe. */
    public record CapabilityLookup(boolean hasHandler, int slotCount, boolean ourProviderWon) {
    }

    /** How many times our competing provider ran; consumed by {@link HopperWorldTest}. */
    public static int ourProviderInvocations() {
        return ourProviderInvocations;
    }
}
