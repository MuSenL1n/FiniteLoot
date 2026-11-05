package vip.naya.finiteloot.command;

import net.minecraft.commands.CommandSourceStack;

/**
 * Who may run {@code /fl}.
 *
 * <p>Paper asked for permission nodes ({@code finiteloot.admin}, {@code finiteloot.set}, ...). Those need a
 * permission plugin; a mod cannot assume one. Decision D6 was to start from the vanilla mechanism and add
 * node support only if it turns out to be needed, so this is one rule: <b>operator level 2</b>, which is the
 * level vanilla itself uses for "can use cheat-like commands".
 *
 * <p>Consequences worth being explicit about:
 *
 * <ul>
 *   <li>The per-subcommand split Paper had ({@code inspect} vs {@code set} vs {@code remove}) is gone. Anyone
 *       who can reload the config can also remove a container. On a server where operators are trusted -- the
 *       only kind of server that runs a loot plugin -- that is the same trust boundary, but it is a reduction.
 *   <li>Console is level 4 and therefore always allowed.
 *   <li>An {@code admin-uuids} allow-list would let a non-op administer without being opped, which D6 left
 *       open. It is not implemented because it needs a new key in {@code config.yml}, and that file is kept
 *       byte-identical to Paper's so the two builds can be diffed against the same reference.
 * </ul>
 */
public final class AdminGate {

    /** The vanilla operator level that grants command access. */
    public static final int REQUIRED_LEVEL = 2;

    private AdminGate() {
    }

    public static boolean allowed(CommandSourceStack source) {
        return source.hasPermission(REQUIRED_LEVEL);
    }
}
