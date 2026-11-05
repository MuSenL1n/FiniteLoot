package vip.naya.finiteloot.p0.hopper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * An {@link IItemHandler} wrapper that marks itself so a capability query can reveal <em>which</em>
 * provider won.
 *
 * <p>The P0-4 question is not "can we write a capability provider" (we can) but
 * "does our provider get consulted at all". {@code BlockCapability#getCapability} iterates providers
 * in registration order and returns the first non-null result, and NeoForge registers its own
 * provider for {@code Blocks.CHEST}. So if NeoForge registers first, a second provider for the same
 * block is dead code -- and hopper interception cannot be done that way.
 *
 * <p>Delegating here rather than reimplementing means the test measures ordering, not behaviour.
 */
public final class MarkedItemHandler implements IItemHandler {
    private final IItemHandler delegate;
    private int extractAttempts;

    /**
     * When true, extraction is refused, modelling what FiniteLoot must do to a container whose claim
     * cap is exhausted: keep the items visible in the GUI but hide them from automation.
     *
     * <p>{@code extractItem} returning {@link ItemStack#EMPTY} for both {@code simulate} and real
     * calls is the contract that means "nothing can be taken" -- {@code VanillaInventoryCodeHooks}
     * checks {@code !extractItem.isEmpty()} before doing anything, so an empty result makes the hopper
     * move on and find nothing.
     */
    private final boolean denyExtraction;

    public MarkedItemHandler(IItemHandler delegate) {
        this(delegate, false);
    }

    public MarkedItemHandler(IItemHandler delegate, boolean denyExtraction) {
        this.delegate = delegate;
        this.denyExtraction = denyExtraction;
    }

    /** True when a capability query handed back our wrapper rather than NeoForge's. */
    public static boolean isOurs(IItemHandler handler) {
        return handler instanceof MarkedItemHandler;
    }

    public int extractAttempts() {
        return extractAttempts;
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
        return delegate.insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        extractAttempts++;
        if (denyExtraction) {
            if (extractAttempts <= 3) {
                ProbeMod.LOG.info("[P0-4] DENYING extract: slot={} amount={} simulate={} -> EMPTY",
                        slot, amount, simulate);
            }
            return ItemStack.EMPTY;
        }
        if (extractAttempts <= 3) {
            ProbeMod.LOG.info("[P0-4] OUR handler was asked to extract: slot={} amount={} simulate={}",
                    slot, amount, simulate);
        }
        return delegate.extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return delegate.isItemValid(slot, stack);
    }
}
