package vip.naya.finiteloot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Keeps the shipped {@code config.yml} and {@link PluginSettings} in step.
 *
 * <p>Two failure modes this catches, both silent in production:
 *
 * <ul>
 *   <li>a key shipped in {@code config.yml} that nothing reads -- the operator toggles it and nothing
 *       happens, which is worse than the key not existing;
 *   <li>a setting added to {@link PluginSettings} without a matching line in the shipped file -- every
 *       server silently runs on the code default and the file stops being documentation.
 * </ul>
 *
 * <p>Two keys are deliberately not record components: {@code per-player-limit} and
 * {@code exhausted-action} are validated while parsing and then discarded, because nothing downstream
 * needs them (the value must be 1 and DENY respectively). They are listed explicitly rather than
 * excluded by a wildcard, so adding a third such key has to be a conscious edit here.
 */
class SettingsCoverageTest {

    /** Keys that are validated during parsing and intentionally never stored. */
    private static final Set<String> VALIDATED_ONLY =
            Set.of("per-player-limit", "exhausted-action");

    private static Set<String> shippedKeys() throws Exception {
        try (InputStream stream = SettingsCoverageTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml is missing from the jar");
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new TreeSet<>(YamlLoader.loadString(yaml, "config.yml").keys());
        }
    }

    private static Set<String> settingsKeys() {
        Set<String> keys = new TreeSet<>();
        for (RecordComponent component : PluginSettings.class.getRecordComponents()) {
            keys.add(kebabCase(component.getName()));
        }
        return keys;
    }

    private static String kebabCase(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    @Test
    void everyShippedKeyIsReadByTheCode() throws Exception {
        Set<String> unread = new TreeSet<>(shippedKeys());
        unread.removeAll(settingsKeys());
        unread.removeAll(VALIDATED_ONLY);

        assertTrue(unread.isEmpty(),
                "these keys ship in config.yml but nothing reads them, so toggling them does nothing: "
                        + unread);
    }

    @Test
    void everySettingHasALineInTheShippedConfig() throws Exception {
        Set<String> undocumented = new TreeSet<>(settingsKeys());
        undocumented.removeAll(shippedKeys());

        assertTrue(undocumented.isEmpty(),
                "these settings exist in code but not in the shipped config.yml, so operators cannot see "
                        + "or change them: " + undocumented);
    }

    @Test
    void nestedSectionsAreNestedInTheShippedFileToo() throws Exception {
        // loot-table-overrides is a section, not a scalar. Asserting its shape here means a flattened
        // file (which SnakeYAML would still parse) cannot slip through unnoticed.
        try (InputStream stream = SettingsCoverageTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(stream);
            ConfigNode config = YamlLoader.loadString(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), "config.yml");
            assertEquals(Set.of("minecraft:chests/end_city_treasure",
                            "minecraft:chests/ancient_city",
                            "minecraft:chests/simple_dungeon",
                            "minecraft:chests/abandoned_mineshaft"),
                    new TreeSet<>(config.getSection("loot-table-overrides").keys()));
        }
    }
}
