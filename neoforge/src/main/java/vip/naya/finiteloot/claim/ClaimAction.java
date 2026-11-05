package vip.naya.finiteloot.claim;

/**
 * What the claim flow should do next.
 *
 * <p>These are decisions, not actions: the adapter that consumes them knows how to talk to the database,
 * the loot generator and the GUI. Keeping the vocabulary this narrow is what lets the state machine be
 * tested without a server.
 *
 * <p>Named after Paper's behaviour rather than after the code path that produces it, so a test can read
 * like a specification: "a counted player taking the last slot of a vanilla-final container recovers the
 * final inventory".
 */
public enum ClaimAction {

    /** Open the player's stored personal inventory. */
    OPEN_PERSONAL,

    /** Prepare the real container (clear its loot table if it still has one) and open it as a normal chest. */
    OPEN_NORMAL,

    /** This player is the last counted claimant; replay the stored inventory into the real container. */
    RECOVER_FINAL_VANILLA,

    /**
     * First claim: generate loot from the container's loot table, then finalise.
     *
     * <p>What happens after generation is a second decision ({@link GeneratedClaimAction}), because
     * whether the generated loot turned out to be empty cannot be known yet.
     */
    PERSIST_NEW,

    /** First claim, and this player is taking the last slot of a vanilla-final container. */
    PERSIST_NEW_FINAL_VANILLA,

    /** The stored personal inventory is empty and the container should now behave like a normal chest. */
    COMPLETE_AND_OPEN_NORMAL,

    /** The container was already exhausted and the operator allows breaking it out of management. */
    RELEASE_AND_OPEN_NORMAL,

    /** Everything has been claimed; tell the player, leave the container in place. */
    SHOW_COMPLETED,

    /** Every slot is taken and the operator does not allow breaking out. */
    SHOW_EXHAUSTED,

    /** Another claim for this container is still being written. */
    SHOW_LOADING,

    /** The database could not be read or written; say so rather than showing an empty chest. */
    SHOW_DATABASE_ERROR
}
