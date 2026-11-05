package vip.naya.finiteloot.p0;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which verification probes run in this JVM.
 *
 * <p>Without this, every probe runs on every boot: the P0 hopper test pumps items through a chest every
 * tick (which both floods the log and, measured, dragged the server 99 ticks behind), the P3 probe re-seeds
 * its containers, and the P0 GUI probe force-opens a virtual menu for whoever joins. Any later phase then
 * has to be verified in an environment that several finished phases are actively mutating -- which is
 * exactly the kind of interference that produces a false pass.
 *
 * <p>Enable a phase explicitly:
 *
 * <pre>
 * -Dfiniteloot.probes=p4          only the claim-flow probe
 * -Dfiniteloot.probes=p3,p4       two phases
 * -Dfiniteloot.probes=all         everything, as the per-phase evidence runs did
 * </pre>
 *
 * <p>Default is none, so a plain server start behaves like the mod alone.
 */
public final class ProbeGate {

    private static final String PROPERTY = "finiteloot.probes";
    private static final Set<String> ENABLED = parse(System.getProperty(PROPERTY, ""));

    private ProbeGate() {
    }

    public static boolean enabled(String phase) {
        return ENABLED.contains("all") || ENABLED.contains(phase.toLowerCase(Locale.ROOT));
    }

    /** For a startup log line, so a log always says which probes were active. */
    public static String describe() {
        return ENABLED.isEmpty() ? "none" : String.join(",", ENABLED);
    }

    private static Set<String> parse(String raw) {
        Set<String> phases = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .forEach(phases::add);
        // Unmodifiable, but order-preserving: the startup line reading "active=p2,p4" should echo what
        // was asked for, not a hash order that changes between runs.
        return Collections.unmodifiableSet(phases);
    }
}
