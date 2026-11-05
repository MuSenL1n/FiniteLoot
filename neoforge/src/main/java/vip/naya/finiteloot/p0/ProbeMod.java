package vip.naya.finiteloot.p0;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vip.naya.finiteloot.container.AttachmentTypes;

/**
 * P0 prototype entry point.
 *
 * <p>Nothing here is FiniteLoot production code. Each child package probes one open question from
 * the migration plan, so that the answers are reproducible rather than assumed:
 *
 * <ul>
 *   <li>{@code p0.net}  -- P0-1: can a vanilla client join while this mod is loaded?
 *   <li>{@code p0.gui}  -- P0-2: can we open a titled chest screen with a self-implemented
 *                         {@code Container}, using only a vanilla {@code MenuType}?
 *   <li>{@code p0.data} -- P0-3: does a jarJar-bundled {@code sqlite-jdbc} load its native library?
 *   <li>{@code p0.hopper} -- P0-4: can we intercept hopper extraction from a managed container?
 *   <li>{@code p0.containers} -- P0-5: can we generically identify modded loot containers?
 * </ul>
 *
 * <p>P0-1 has no code: the finding is that this mod must register <em>no</em> non-optional custom
 * payload, which is already true because we register none at all.
 */
@Mod(ProbeMod.MOD_ID)
public final class ProbeMod {
    public static final String MOD_ID = "finiteloot";
    public static final Logger LOG = LoggerFactory.getLogger("FiniteLoot-P0");

    public ProbeMod(IEventBus modEventBus) {
        // Data attachments must be registered on the mod bus before any block entity is loaded, or the
        // saved markers in an existing world cannot be read back.
        AttachmentTypes.ATTACHMENT_TYPES.register(modEventBus);
        LOG.info("[P0] FiniteLoot prototype constructed (server-side only).");
        // Always announce which probes are live. A run that silently had several phases mutating the
        // world at once is how a false pass happens, so the log should make it impossible to be unsure.
        LOG.info("[PROBES] active={} (set -Dfiniteloot.probes=p0,p1,p2,p3,p4 or all)", ProbeGate.describe());
    }
}
