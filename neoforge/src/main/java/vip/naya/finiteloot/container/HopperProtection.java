package vip.naya.finiteloot.container;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import vip.naya.finiteloot.FiniteLootRuntime;

import static vip.naya.finiteloot.p0.ProbeMod.MOD_ID;

/**
 * Stops automation from emptying a managed container.
 *
 * <p>This is P0-4 turned into production code. The probe established two things that the implementation depends
 * on, and both are load-bearing:
 *
 * <ol>
 *   <li>A provider registered here at {@link EventPriority#HIGHEST} is consulted <em>before</em> NeoForge's own
 *       vanilla provider, so it can intercept a hopper's extraction without a mixin.
 *   <li>Registering per block entity type ({@code registerBlockEntity}) reaches modded containers, while the
 *       {@code Blocks.CHEST} overload only reaches chests. Walking the registry claims every type without naming
 *       a single block, which is what "every loot container in the pack" requires.
 * </ol>
 *
 * <p><b>One deliberate difference from the probe.</b> The probe wrapped every container unconditionally, so it
 * could count invocations. Production returns {@code null} -- "not mine, ask the next provider" -- unless the
 * container is actually being protected. That matters on a modded pack: another mod may have its own item
 * handler for its own container (a drawer, a crate, a machine buffer), and answering for it would replace that
 * mod's behaviour with a plain inventory wrapper. Protection has to be invisible until it fires.
 *
 * <p>The denial wrapper still exposes the container's real slots and only refuses the two operations that
 * matter: taking items out (the exploit) and putting them in (insertion is refused for players anyway, so a
 * hopper must not get a second route).
 */
@EventBusSubscriber(modid = MOD_ID)
public final class HopperProtection {

    private HopperProtection() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        for (var type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (type.getValidBlocks().isEmpty()) {
                // Nothing can be placed as this type, so no capability lookup can ever reach the provider.
                continue;
            }
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type,
                    (blockEntity, side) -> handlerFor(blockEntity));
        }
    }

    /**
     * The handler to expose for a block entity, or {@code null} to leave it to whoever registered it.
     *
     * <p>Called on every capability lookup in the world, including every hopper tick, so it does the cheapest
     * possible disqualification first: config off, then not-a-container, then not-managed.
     */
    private static IItemHandler handlerFor(BlockEntity blockEntity) {
        FiniteLootRuntime runtime = FiniteLootRuntime.get();
        if (runtime == null || !runtime.settings().get().preventHopperExtraction()) {
            return null;
        }
        if (!(blockEntity instanceof Container container)) {
            return null;
        }
        Level level = blockEntity.getLevel();
        if (level == null || !runtime.containers().isManagedOrEligible(level, blockEntity.getBlockPos())) {
            return null;
        }
        return new ProtectedHandler(new InvWrapper(container));
    }

    /**
     * Exposes the real inventory but refuses extraction and insertion.
     *
     * <p>Delegating everything else matters: a hopper asks for the slot count and then extracts from slot 0, so
     * a handler that reported zero slots would also "work" -- but it would also tell every other mod that this
     * chest is empty, which is a lie that shows up as empty-looking containers in inventory scanners.
     */
    private static final class ProtectedHandler implements IItemHandler {

        private final IItemHandler delegate;

        ProtectedHandler(IItemHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getSlots() {
            return delegate.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}
