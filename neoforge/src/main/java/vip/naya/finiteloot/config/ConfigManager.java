package vip.naya.finiteloot.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Owns where FiniteLoot's config and language files live, writes them out on first run, and reloads
 * them for {@code /fl reload}.
 *
 * <p>Self-managed files rather than NeoForge's {@code ModConfigSpec} is the standing decision (D5).
 * The reason is concrete: {@code loot-table-overrides} is an open-ended map whose keys are whatever
 * loot tables the operator cares about, and {@code ModConfigSpec} cannot express a dynamic-key map
 * without flattening it into parallel lists -- which would break the requirement that config option
 * names stay identical to the Paper plugin's.
 *
 * <p>No Minecraft types are referenced here on purpose, so the whole load/validate path is unit
 * testable. {@link Messages} is the only piece of this layer that needs the game.
 */
public final class ConfigManager {

    private final Path configDir;
    private volatile PluginSettings settings;
    private volatile LanguageFile language;
    private volatile String languageCode = "zh_CN";

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
    }

    public Path configDir() {
        return configDir;
    }

    public Path configFile() {
        return configDir.resolve("config.yml");
    }

    /**
     * Writes defaults for anything missing, then parses both files.
     *
     * <p>Missing files are created rather than treated as errors: a fresh install must start with a
     * working, fully commented config rather than defaults it never shows the operator.
     */
    public synchronized void load() throws IOException {
        Files.createDirectories(configDir);

        ensureDefault(configDir.resolve("config.yml"), "config.yml");

        PluginSettings parsed = PluginSettings.from(YamlLoader.load(configFile()));
        this.settings = parsed;

        this.languageCode = normalizeLanguage(parsed.language());
        Path langFile = configDir.resolve("lang").resolve(languageCode + ".yml");
        ensureDefault(langFile, "lang/" + languageCode + ".yml");

        Map<String, String> userValues = LanguageFile.flatten(YamlLoader.load(langFile));
        Map<String, String> bundledDefaults = readBundledLanguage(languageCode);
        this.language = new LanguageFile(userValues, bundledDefaults);
    }

    public PluginSettings settings() {
        return settings;
    }

    public LanguageFile language() {
        return language;
    }

    public String languageCode() {
        return languageCode;
    }

    /** Paper accepted any value and fell back to {@code zh_CN}; preserved so existing configs behave. */
    static String normalizeLanguage(String requested) {
        return "en_US".equalsIgnoreCase(requested) ? "en_US" : "zh_CN";
    }

    private void ensureDefault(Path target, String resource) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream stream = ConfigManager.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Bundled resource missing from the mod jar: " + resource);
            }
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, String> readBundledLanguage(String code) {
        try (InputStream stream = ConfigManager.class.getClassLoader()
                .getResourceAsStream("lang/" + code + ".yml")) {
            if (stream == null) {
                return Map.of();
            }
            String text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return LanguageFile.flatten(YamlLoader.loadString(text, "bundled lang/" + code + ".yml"));
        } catch (IOException exception) {
            return Map.of();
        }
    }
}
