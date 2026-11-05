package vip.naya.finiteloot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Validates the resources that actually ship inside the mod jar, not fixtures.
 *
 * <p>This is the test that would have caught a YAML syntax error, a missing translation, or a colour
 * typo before it reached a server. It reads the same files {@link ConfigManager} copies out on first
 * run, straight off the classpath, so it cannot drift from what players get.
 */
class ShippedResourcesTest {

    private static String read(String resource) throws Exception {
        try (InputStream stream = ShippedResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "bundled resource is missing from the jar: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void shippedConfigYmlParsesAndKeepsItsDocumentedDefaults() throws Exception {
        PluginSettings settings = PluginSettings.from(YamlLoader.loadString(read("config.yml"), "config.yml"));

        assertEquals(3, settings.defaultMaxClaims());
        // Parsing would have thrown above if the shipped file violated per-player-limit: 1.
        assertEquals(FinalClaimAction.VANILLA_CONTAINER, settings.finalClaimAction());
        assertEquals("zh_CN", settings.language());
        assertTrue(settings.preventHopperExtraction());
        assertTrue(settings.preventExplosions());
        assertTrue(settings.preventBreaking());
        // The four documented overrides must survive a round trip through the real file.
        assertEquals(2, settings.maxClaimsFor("minecraft:chests/end_city_treasure"));
        assertEquals(2, settings.maxClaimsFor("minecraft:chests/ancient_city"));
        assertEquals(5, settings.maxClaimsFor("minecraft:chests/simple_dungeon"));
        assertEquals(4, settings.maxClaimsFor("minecraft:chests/abandoned_mineshaft"));
    }

    @Test
    void bothLanguagesDefineTheSameKeys() throws Exception {
        Set<String> english = new TreeSet<>(LanguageFile.flatten(
                YamlLoader.loadString(read("lang/en_US.yml"), "en_US")).keySet());
        Set<String> chinese = new TreeSet<>(LanguageFile.flatten(
                YamlLoader.loadString(read("lang/zh_CN.yml"), "zh_CN")).keySet());

        assertEquals(english, chinese,
                "a key present in one language and missing in the other would render as a red "
                        + "'Missing message' for some players only");
    }

    @Test
    void everyMessageRendersWithoutSwallowingText() throws Exception {
        for (String code : new String[] {"en_US", "zh_CN"}) {
            Map<String, String> templates = LanguageFile.flatten(
                    YamlLoader.loadString(read("lang/" + code + ".yml"), code));
            assertTrue(templates.size() > 20, code + " should define the full message set");

            for (Map.Entry<String, String> entry : templates.entrySet()) {
                // Substitute every placeholder the template mentions so the render is realistic.
                Map<String, String> replacements = new java.util.HashMap<>();
                for (String tag : placeholdersOf(entry.getValue())) {
                    replacements.put(tag, "X");
                }
                var runs = MiniText.parse(entry.getValue(), replacements);
                StringBuilder text = new StringBuilder();
                runs.forEach(run -> text.append(run.text()));

                assertTrue(text.length() > 0, code + "/" + entry.getKey() + " rendered to nothing");
                assertTrue(!text.toString().contains("<gold>") && !text.toString().contains("<red>"),
                        code + "/" + entry.getKey() + " left markup in the output: " + text);
            }
        }
    }

    @Test
    void prefixIsDefinedInBothLanguages() throws Exception {
        for (String code : new String[] {"en_US", "zh_CN"}) {
            Map<String, String> templates = LanguageFile.flatten(
                    YamlLoader.loadString(read("lang/" + code + ".yml"), code));
            assertTrue(templates.containsKey("prefix"), code + " must define 'prefix'");
            assertTrue(templates.containsKey("exhausted"), code + " must define 'exhausted'");
        }
    }

    private static Set<String> placeholdersOf(String template) {
        Set<String> found = new TreeSet<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<([^<>/]+)>").matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            // Only names that look like the ASCII placeholders the code substitutes.
            if (name.matches("[a-z_]+")) {
                found.add(name);
            }
        }
        return found;
    }
}
