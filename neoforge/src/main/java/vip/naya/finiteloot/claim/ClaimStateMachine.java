package vip.naya.finiteloot.claim;

import vip.naya.finiteloot.config.FinalClaimAction;
import vip.naya.finiteloot.data.ClaimAllocation;

/**
 * The claim state machine, with every platform dependency removed.
 *
 * <p>In the Paper original this logic was interleaved with Bukkit calls inside
 * {@code ClaimController#handleAllocation}: the switch over the allocation kind, the exhaustion test, the
 * "is this the final counted claimant" test and the personal-completion test were all mixed in with
 * {@code player.openInventory(...)} and {@code Bukkit.getScheduler().runTask(...)}. That made the part
 * most likely to be wrong the part hardest to test -- it needed a live server and a player to exercise.
 *
 * <p>Here the decisions are pure functions over a small value object, so the awkward cases (last counted
 * claimant, empty loot on the final slot, exhausted container with breaking allowed, creative players who
 * do not count) can be enumerated exhaustively in unit tests.
 *
 * <p>Every rule below is transcribed from the original; the comments name the original behaviour rather
 * than restating the code, because the reason a rule exists is the part that gets lost in a port.
 */
public final class ClaimStateMachine {

    private ClaimStateMachine() {
    }

    /**
     * Everything the state machine is allowed to look at.
     *
     * @param kind which kind of allocation the database returned for this player
     * @param counted whether this player occupies a claim slot (creative players and bypassing admins may not)
     * @param claimCount counted claimants so far, <em>as reported by the database after the allocation</em>
     * @param maxClaims the limit resolved for this container's loot table
     * @param hasContentsBlob whether a stored personal inventory was actually present for this player
     * @param contentsEmpty whether that stored inventory contains no items
     * @param finalClaimAction what the operator wants to happen when the last slot is taken
     * @param completedContainerBecomesNormal whether a finished container turns into a normal chest
     * @param preventItemInsertion whether players may put items back into a personal inventory
     * @param allowBreakingExhaustedContainers whether an exhausted container may be taken out of management
     */
    public record ClaimContext(
            ClaimAllocation.Kind kind,
            boolean counted,
            int claimCount,
            int maxClaims,
            boolean hasContentsBlob,
            boolean contentsEmpty,
            FinalClaimAction finalClaimAction,
            boolean completedContainerBecomesNormal,
            boolean preventItemInsertion,
            boolean allowBreakingExhaustedContainers) {

        /** Paper's {@code isPersonalCompletionEnabled}: the container stays open as a normal chest. */
        public boolean personalCompletionEnabled() {
            return finalClaimAction == FinalClaimAction.PERSONAL && completedContainerBecomesNormal;
        }

        /**
         * Paper's {@code isFinalVanillaClaim}.
         *
         * <p>{@code counted} is load-bearing: a creative player who does not count must never trigger the
         * transition, or they could consume the last slot without any counted player getting the loot.
         */
        public boolean finalVanillaClaim() {
            return counted
                    && finalClaimAction == FinalClaimAction.VANILLA_CONTAINER
                    && claimCount >= maxClaims;
        }

        /** True once the container has no slots left, whether or not this player counted. */
        public boolean exhausted() {
            return claimCount >= maxClaims;
        }

        /** What the "remaining claims" message should say; never negative. */
        public int remainingClaims() {
            return Math.max(0, maxClaims - claimCount);
        }
    }

    /**
     * The decision taken before any loot is generated.
     *
     * <p>Corresponds to {@code ClaimController#handleAllocation}.
     */
    public static ClaimAction decide(ClaimContext context) {
        return switch (context.kind()) {
            case EXISTING -> {
                // No blob for a player the database says has an allocation means the row and the
                // inventory have diverged. Showing an empty chest would look like a working claim and
                // silently lose the loot, so this is reported as a database error instead.
                if (!context.hasContentsBlob()) {
                    yield ClaimAction.SHOW_DATABASE_ERROR;
                }
                if (context.finalVanillaClaim()) {
                    yield ClaimAction.RECOVER_FINAL_VANILLA;
                }
                if (context.personalCompletionEnabled() && context.contentsEmpty()) {
                    yield ClaimAction.COMPLETE_AND_OPEN_NORMAL;
                }
                yield ClaimAction.OPEN_PERSONAL;
            }
            case COMPLETED -> {
                if (context.finalVanillaClaim()) {
                    yield ClaimAction.RECOVER_FINAL_VANILLA;
                }
                if (context.personalCompletionEnabled()) {
                    yield ClaimAction.OPEN_NORMAL;
                }
                yield ClaimAction.SHOW_COMPLETED;
            }
            case EXHAUSTED -> context.allowBreakingExhaustedContainers()
                    ? ClaimAction.RELEASE_AND_OPEN_NORMAL
                    : ClaimAction.SHOW_EXHAUSTED;
            case PENDING -> ClaimAction.SHOW_LOADING;
            case NOT_FOUND -> ClaimAction.SHOW_DATABASE_ERROR;
            case NEW -> context.finalVanillaClaim()
                    ? ClaimAction.PERSIST_NEW_FINAL_VANILLA
                    : ClaimAction.PERSIST_NEW;
        };
    }

    /**
     * The decision taken after loot has been generated for a first claim.
     *
     * <p>Corresponds to the {@code completed} computation inside
     * {@code ClaimController#persistNewInventory} and to the unconditional transition in
     * {@code persistFinalVanillaInventory}.
     *
     * @param generatedEmpty whether the freshly generated loot contained no items
     * @param finalVanillaClaim whether this claim was the final vanilla claim
     */
    public static GeneratedClaimAction afterGeneration(
            ClaimContext context, boolean generatedEmpty, boolean finalVanillaClaim) {
        if (finalVanillaClaim) {
            // The final vanilla claim always hands over to the real container, even when the loot turned
            // out to be empty: the container is being retired, not completed.
            return GeneratedClaimAction.TRANSITION_TO_VANILLA;
        }
        // An empty roll only finishes the container if the operator has asked for that outcome. With
        // prevent-item-insertion on, an empty personal inventory can never be refilled, so leaving it
        // open would strand the player in a chest they cannot use.
        boolean completed = generatedEmpty
                && (context.preventItemInsertion() || context.personalCompletionEnabled());
        if (!completed) {
            return GeneratedClaimAction.OPEN_PERSONAL;
        }
        return context.personalCompletionEnabled()
                ? GeneratedClaimAction.OPEN_COMPLETED_NORMAL
                : GeneratedClaimAction.SHOW_COMPLETED;
    }

    /**
     * Paper's {@code shouldReopenCompleted}: whether a player who already finished may look again.
     *
     * <p>Reopening would let a player put items back into a container that is supposed to be done, and with
     * insertion prevented that inventory could never be emptied again.
     */
    public static boolean shouldReopenCompleted(
            FinalClaimAction finalClaimAction,
            boolean completedContainerBecomesNormal,
            boolean preventItemInsertion) {
        return !preventItemInsertion
                && (finalClaimAction == FinalClaimAction.VANILLA_CONTAINER
                        || !completedContainerBecomesNormal);
    }
}
