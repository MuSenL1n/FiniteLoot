package vip.naya.finiteloot.p0.config;

import java.nio.file.Files;
import java.nio.file.Path;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.minecraft.network.chat.Component;
import vip.naya.finiteloot.config.ConfigManager;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.p0.ProbeGate;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P2: runs the ported config + localisation layer inside a real production server, from the mod jar.
 *
 * <p>The unit suite already pins parsing and rendering. What it cannot prove is the parts that only
 * exist once the code is packaged as a mod and running in the game:
 *
 * <ul>
 *   <li><b>SnakeYAML resolves from the jarJar-nested library.</b> The production server ships no YAML
 *       parser at all (checked: zero {@code org/yaml/snakeyaml} classes across every jar in
 *       {@code libraries/}), so if the nested copy is not picked up, {@code config.yml} cannot be read.
 *   <li><b>The bundled config and language files are readable through the mod's classloader.</b>
 *       {@code ConfigManager} copies them out via {@code getResourceAsStream}, and NeoForge's
 *       transforming classloader is not the plain app classloader this was written against.
 *   <li><b>Rendering produces real Minecraft components with real colours.</b> The unit tests verify
 *       the run list; only here is that turned into {@code net.minecraft.network.chat.Component} and
 *       serialised, which is what actually reaches a client.
 * </ul>
 *
 * <p>Judged by grepping for the {@code [P2-} prefix in the server log.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class ConfigProbe {

    private ConfigProbe() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!ProbeGate.enabled("p2")) {
            return;
        }
        try {
            run(event);
        } catch (Throwable t) {
            ProbeMod.LOG.error("[P2] FAILED with exception", t);
        }
    }

    private static void run(ServerStartedEvent event) throws Exception {
        ProbeMod.LOG.info("[P2] config probe starting");

        Path configDir = Path.of("config", "finiteloot-p2");
        ConfigManager manager = new ConfigManager(configDir);
        manager.load();

        PluginSettings settings = manager.settings();
        ProbeMod.LOG.info("[P2] config loaded from the mod jar: maxClaims={} finalClaimAction={} language={}",
                settings.defaultMaxClaims(), settings.finalClaimAction(), settings.language());
        ProbeMod.LOG.info("[P2] files written: config.yml={} lang={}.yml={}",
                Files.exists(configDir.resolve("config.yml")),
                manager.languageCode(),
                Files.exists(configDir.resolve("lang/" + manager.languageCode() + ".yml")));
        ProbeMod.LOG.info("[P2] loot-table-overrides applied: dungeon={} endCity={} unlisted={}",
                settings.maxClaimsFor("minecraft:chests/simple_dungeon"),
                settings.maxClaimsFor("minecraft:chests/end_city_treasure"),
                settings.maxClaimsFor("minecraft:chests/village_blacksmith"));

        // Render through the real Minecraft component tree and serialise it, so the evidence is the
        // exact JSON a vanilla client would receive rather than an internal representation.
        Messages messages = new Messages(manager.language());
        Component remaining = messages.component("remaining-claims",
                java.util.Map.of("remaining", "2", "max", "5"));
        Component prefix = messages.component("prefix");

        String remainingJson = serialise(remaining, event);
        String prefixJson = serialise(prefix, event);

        ProbeMod.LOG.info("[P2] rendered 'remaining-claims' plain='{}'", remaining.getString());
        ProbeMod.LOG.info("[P2] rendered 'remaining-claims' json={}", remainingJson);
        ProbeMod.LOG.info("[P2] rendered 'prefix' json={}", prefixJson);

        // The colours have to actually be in the serialised output; a plain string would pass the
        // unit tests but ship unstyled text.
        boolean hasGold = remainingJson.contains("\"color\":\"gold\"");
        boolean hasGray = remainingJson.contains("\"color\":\"gray\"");
        boolean plainTextOk = remaining.getString().contains("2") && remaining.getString().contains("5");
        boolean prefixOk = prefixJson.contains("gold") && prefixJson.contains("gray");
        boolean filesOk = Files.exists(configDir.resolve("config.yml"));
        boolean settingsOk = settings.defaultMaxClaims() == 3
                && settings.maxClaimsFor("minecraft:chests/simple_dungeon") == 5
                && settings.maxClaimsFor("minecraft:chests/village_blacksmith") == 3;

        // Assert the Chinese payload from *inside* the JVM instead of trusting how it looks in a
        // console. The console pipeline on this machine decodes the server's stdout as GBK and mangles
        // non-ASCII, so a screenshot of the log proves nothing about whether the bytes are right.
        // This check fails if the language file were read with the platform default charset instead of
        // UTF-8 -- which is the one encoding mistake that would actually reach players.
        boolean chineseIntact = remainingJson.contains("名玩家领取")
                && remainingJson.contains("该奖励箱还可供")
                && prefixJson.contains("限量战利品");

        // Same idea for the emitted files: read them back and look for the Chinese text.
        boolean emittedConfigUtf8 = Files.readString(configDir.resolve("config.yml"), java.nio.charset.StandardCharsets.UTF_8)
                .contains("新发现容器允许的全服计数玩家领取次数上限");
        boolean emittedLangUtf8 = Files.readString(
                        configDir.resolve("lang/" + manager.languageCode() + ".yml"),
                        java.nio.charset.StandardCharsets.UTF_8)
                .contains("限量战利品");

        // Leave a UTF-8 evidence file behind, so the rendered output can be inspected without going
        // through a console that cannot represent it.
        Path evidence = configDir.resolve("p2-render.json");
        Files.writeString(evidence,
                "prefix=" + prefixJson + "\nremaining-claims=" + remainingJson + "\n",
                java.nio.charset.StandardCharsets.UTF_8);

        // Exercise the missing-key path too: it must degrade to the documented red fallback, not throw.
        Component missing = messages.component("this-key-does-not-exist");
        boolean missingOk = missing.getString().contains("Missing message");

        boolean pass = hasGold && hasGray && plainTextOk && prefixOk && filesOk && settingsOk
                && chineseIntact && emittedConfigUtf8 && emittedLangUtf8 && missingOk;
        ProbeMod.LOG.info(
                "[P2] checks: snakeyaml+nestedJar=ok gold={} gray={} plainText={} prefixColors={} "
                        + "files={} settings={} chineseIntact={} emittedUtf8={}/{} missingKeyFallback={}",
                hasGold, hasGray, plainTextOk, prefixOk, filesOk, settingsOk, chineseIntact,
                emittedConfigUtf8, emittedLangUtf8, missingOk);
        ProbeMod.LOG.info("[P2] evidence written to {}", evidence.toAbsolutePath());
        ProbeMod.LOG.info("[P2] {} - config and localisation usable from the mod jar",
                pass ? "SUCCESS" : "FAILURE");
    }

    /** Serialises exactly as the network layer would, so colours are visible as data. */
    private static String serialise(Component component, ServerStartedEvent event) {
        // Component.Serializer.serialize is package-private in 1.21.1; toJson is the public door to
        // the same code, and yields the JSON a client actually receives.
        return Component.Serializer.toJson(component, event.getServer().registryAccess());
    }
}
