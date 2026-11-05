package vip.naya.finiteloot.claim;

/**
 * The second decision, made once the loot has actually been generated.
 *
 * <p>It is a separate step on purpose. The first claim has to know whether the generated loot was empty
 * before it can decide between "open the personal inventory" and "this container is finished", and that
 * answer only exists after the loot table has been rolled.
 */
public enum GeneratedClaimAction {

    /** Open the loot that was just generated. */
    OPEN_PERSONAL,

    /** The generated loot was empty and the container becomes a normal chest from now on. */
    OPEN_COMPLETED_NORMAL,

    /** The generated loot was empty and the player is merely told the container is finished. */
    SHOW_COMPLETED,

    /** Hand the generated loot over to the real container and stop managing it. */
    TRANSITION_TO_VANILLA
}
