package vip.naya.finiteloot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Covers the file lifecycle: first-run emission, reload, and the two-layer language fallback.
 *
 * <p>This is the part of P2 that a unit test can genuinely verify end to end, because the whole path
 * is plain Java -- no Minecraft, no server.
 */
class ConfigManagerTest {

    private static Path freshDir() throws Exception {
        Path dir = Path.of("build", "test-config", UUID.randomUUID().toString());
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void firstRunWritesAUsableConfigAndLanguageFile() throws Exception {
        Path dir = freshDir();
        ConfigManager manager = new ConfigManager(dir);
        manager.load();

        assertTrue(Files.exists(dir.resolve("config.yml")), "config.yml must be emitted on first run");
        assertTrue(Files.exists(dir.resolve("lang/zh_CN.yml")), "the selected language file must be emitted");

        PluginSettings settings = manager.settings();
        assertNotNull(settings);
        assertEquals(3, settings.defaultMaxClaims());
        assertEquals("zh_CN", manager.languageCode());
        assertEquals(5, settings.maxClaimsFor("minecraft:chests/simple_dungeon"));
    }

    @Test
    void emittedConfigIsTheCommentedOriginal() throws Exception {
        Path dir = freshDir();
        new ConfigManager(dir).load();
        String written = Files.readString(dir.resolve("config.yml"));

        // A fresh install must show the operator the documented file, comments included -- not a
        // stripped-down dump of in-memory defaults.
        assertTrue(written.contains("default-max-claims: 3"), "written config lost its defaults");
        assertTrue(written.contains("loot-table-overrides:"), "written config lost the overrides section");
        assertTrue(written.contains("# 新发现容器允许的全服计数玩家领取次数上限。"),
                "written config lost its comments");
    }

    @Test
    void reloadPicksUpEditsWithoutRestarting() throws Exception {
        Path dir = freshDir();
        ConfigManager manager = new ConfigManager(dir);
        manager.load();
        assertEquals(3, manager.settings().defaultMaxClaims());

        Files.writeString(dir.resolve("config.yml"), """
                default-max-claims: 9
                loot-table-overrides:
                  minecraft:chests/simple_dungeon: 9
                """);
        manager.load();

        assertEquals(9, manager.settings().defaultMaxClaims());
        assertEquals(9, manager.settings().maxClaimsFor("minecraft:chests/simple_dungeon"));
    }

    @Test
    void switchingLanguageLoadsTheOtherFile() throws Exception {
        Path dir = freshDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), "language: en_US\n");
        ConfigManager manager = new ConfigManager(dir);
        manager.load();

        assertEquals("en_US", manager.languageCode());
        assertTrue(Files.exists(dir.resolve("lang/en_US.yml")));
        assertTrue(manager.language().has("prefix"));
        assertEquals("<gray>[<gold>FiniteLoot</gold>]</gray> ", manager.language().raw("prefix"),
                "the English prefix must come from the English file");
    }

    @Test
    void aKeyMissingFromTheUsersFileStillResolvesFromTheBundledDefault() throws Exception {
        // Simulates an operator who kept a language file from an older version: new keys must still work.
        Path dir = freshDir();
        Files.createDirectories(dir.resolve("lang"));
        Files.writeString(dir.resolve("config.yml"), "language: zh_CN\n");
        // A stale file that defines only the prefix.
        Files.writeString(dir.resolve("lang/zh_CN.yml"), "prefix: '<gray>[custom]</gray> '\n");

        ConfigManager manager = new ConfigManager(dir);
        manager.load();

        assertEquals("<gray>[custom]</gray> ", manager.language().raw("prefix"),
                "the user's own value must win");
        assertNotNull(manager.language().raw("exhausted"),
                "a key added later must fall back to the bundled default instead of 'Missing message'");
    }

    @Test
    void malformedYamlFailsLoudlyWithTheFileName() throws Exception {
        Path dir = freshDir();
        Files.writeString(dir.resolve("config.yml"), "default-max-claims: [unclosed\n");
        ConfigManager manager = new ConfigManager(dir);
        try {
            manager.load();
            throw new AssertionError("expected a parse failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("config.yml"),
                    "the error must name the file so the operator knows what to fix: " + expected.getMessage());
        }
    }

    @Test
    void duplicateKeysAreRejectedRatherThanSilentlyShadowed() throws Exception {
        try {
            YamlLoader.loadString("""
                    default-max-claims: 3
                    default-max-claims: 5
                    """);
            throw new AssertionError("expected duplicate keys to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("duplicate")
                            || expected.getMessage().toLowerCase().contains("found duplicate"),
                    "expected a duplicate-key message, got: " + expected.getMessage());
        }
    }

    @Test
    void nonMappingRootIsRejectedWithAClearMessage() {
        try {
            YamlLoader.loadString("- just\n- a\n- list\n");
            throw new AssertionError("expected a rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("mapping"), expected.getMessage());
        }
    }
}
