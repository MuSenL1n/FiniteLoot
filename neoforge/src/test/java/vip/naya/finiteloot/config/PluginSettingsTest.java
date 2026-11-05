package vip.naya.finiteloot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Guards the promise that an operator's existing {@code config.yml} keeps its meaning after the port.
 *
 * <p>Each test below is anchored to a specific behaviour of the Paper original rather than to
 * whatever the new code happens to do, because "ported" only counts if the observable behaviour is the
 * same.
 */
class PluginSettingsTest {

    /** The real shipped config.yml, parsed the way the mod parses it. */
    private static final String SHIPPED = """
            default-max-claims: 3
            show-remaining-claims: true
            play-container-animation: true
            play-container-sounds: true
            trigger-piglin-anger: true
            per-player-limit: 1
            exhausted-action: DENY
            prevent-item-insertion: false
            completed-container-becomes-normal: true
            final-claim-action: VANILLA_CONTAINER
            clear-personal-inventories-on-final-claim: true
            show-final-claim-message: true
            prevent-hopper-extraction: true
            prevent-breaking: true
            allow-breaking-exhausted-containers: false
            prevent-explosions: true
            count-creative-players: false
            admin-bypass-counts: false
            excluded-worlds: []
            language: zh_CN
            loot-table-overrides:
              minecraft:chests/end_city_treasure: 2
              minecraft:chests/ancient_city: 2
              minecraft:chests/simple_dungeon: 5
              minecraft:chests/abandoned_mineshaft: 4
            """;

    private static PluginSettings shipped() {
        return PluginSettings.from(YamlLoader.loadString(SHIPPED));
    }

    @Test
    void shippedDefaultsParseToTheDocumentedValues() {
        PluginSettings settings = shipped();
        assertEquals(3, settings.defaultMaxClaims());
        assertTrue(settings.showRemainingClaims());
        assertTrue(settings.playContainerAnimation());
        assertTrue(settings.playContainerSounds());
        assertTrue(settings.triggerPiglinAnger());
        assertFalse(settings.preventItemInsertion());
        assertTrue(settings.completedContainerBecomesNormal());
        assertEquals(FinalClaimAction.VANILLA_CONTAINER, settings.finalClaimAction());
        assertTrue(settings.clearPersonalInventoriesOnFinalClaim());
        assertTrue(settings.showFinalClaimMessage());
        assertTrue(settings.preventHopperExtraction());
        assertTrue(settings.preventBreaking());
        assertFalse(settings.allowBreakingExhaustedContainers());
        assertTrue(settings.preventExplosions());
        assertFalse(settings.countCreativePlayers());
        assertFalse(settings.adminBypassCounts());
        assertEquals(Set.of(), settings.excludedWorlds());
        assertEquals("zh_CN", settings.language());
    }

    @Test
    void lootTableOverridesApplyAndFallBackToTheDefault() {
        PluginSettings settings = shipped();
        assertEquals(5, settings.maxClaimsFor("minecraft:chests/simple_dungeon"));
        assertEquals(2, settings.maxClaimsFor("minecraft:chests/end_city_treasure"));
        assertEquals(3, settings.maxClaimsFor("minecraft:chests/village_blacksmith"),
                "an unlisted table must use default-max-claims");
        assertEquals(3, settings.maxClaimsFor(null), "a container with no loot table must use the default");
    }

    @Test
    void overrideKeysAreNormalisedLikeNamespacedKeysWere() {
        PluginSettings settings = PluginSettings.from(YamlLoader.loadString("""
                default-max-claims: 3
                loot-table-overrides:
                  Minecraft:Chests/Simple_Dungeon: 5
                  not a namespaced key: 2
                """));
        assertEquals(5, settings.maxClaimsFor("minecraft:chests/simple_dungeon"),
                "casing must not stop an override from matching");
        assertEquals(2, settings.maxClaimsFor("not a namespaced key"),
                "an unparseable key is kept verbatim, as Paper did");
    }

    @Test
    void perPlayerLimitMustBeOne() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PluginSettings.from(YamlLoader.loadString("per-player-limit: 2")));
        assertTrue(failure.getMessage().contains("per-player-limit"),
                "the error must name the offending option: " + failure.getMessage());
    }

    @Test
    void exhaustedActionMustBeDeny() {
        assertThrows(IllegalArgumentException.class,
                () -> PluginSettings.from(YamlLoader.loadString("exhausted-action: ALLOW")));
        // ...and DENY in lower case is still accepted, because the original compared ignoring case.
        assertEquals(FinalClaimAction.VANILLA_CONTAINER,
                PluginSettings.from(YamlLoader.loadString("exhausted-action: deny")).finalClaimAction());
    }

    @Test
    void unsupportedFinalClaimActionNamesTheValidChoices() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PluginSettings.from(YamlLoader.loadString("final-claim-action: NONSENSE")));
        assertTrue(failure.getMessage().contains("VANILLA_CONTAINER"), failure.getMessage());
        assertTrue(failure.getMessage().contains("PERSONAL"), failure.getMessage());
    }

    @Test
    void finalClaimActionAcceptsLowerCase() {
        assertEquals(FinalClaimAction.PERSONAL,
                PluginSettings.from(YamlLoader.loadString("final-claim-action: personal")).finalClaimAction());
    }

    @Test
    void excludedWorldsAreMatchedCaseInsensitively() {
        PluginSettings settings = PluginSettings.from(YamlLoader.loadString("""
                excluded-worlds:
                  - World_Nether
                  - world_the_end
                """));
        assertTrue(settings.isWorldExcluded("world_nether"));
        assertFalse(settings.isWorldExcluded("world"));
    }

    @Test
    void maxClaimsIsClampedToAtLeastOne() {
        assertEquals(1, PluginSettings.from(YamlLoader.loadString("default-max-claims: 0")).defaultMaxClaims());
        assertEquals(1, PluginSettings.from(YamlLoader.loadString("default-max-claims: -9")).defaultMaxClaims());
        assertEquals(7, PluginSettings.from(YamlLoader.loadString("default-max-claims: 7")).defaultMaxClaims());
    }

    @Test
    void lootTableOverrideIsClampedToAtLeastOne() {
        PluginSettings settings = PluginSettings.from(YamlLoader.loadString("""
                loot-table-overrides:
                  minecraft:chests/simple_dungeon: 0
                """));
        assertEquals(1, settings.maxClaimsFor("minecraft:chests/simple_dungeon"));
    }

    @Test
    void missingOptionsFallBackToTheSameDefaultsAsBefore() {
        // An empty file must yield the same settings as a fully populated one, apart from the
        // loot-table map -- that is what "defaults" meant in the original.
        PluginSettings settings = PluginSettings.from(YamlLoader.loadString(""));
        assertEquals(3, settings.defaultMaxClaims());
        assertEquals(FinalClaimAction.VANILLA_CONTAINER, settings.finalClaimAction());
        assertEquals("zh_CN", settings.language());
        assertTrue(settings.preventHopperExtraction());
        assertFalse(settings.allowBreakingExhaustedContainers());
        assertTrue(settings.lootTableOverrides().isEmpty());
    }

    @Test
    void settingsAreImmutableOnceBuilt() {
        PluginSettings settings = shipped();
        assertThrows(UnsupportedOperationException.class,
                () -> settings.lootTableOverrides().put("minecraft:chests/x", 9));
        assertThrows(UnsupportedOperationException.class, () -> settings.excludedWorlds().add("x"));
    }

    @Test
    void absentOverrideSectionIsDistinctFromAnEmptyOne() {
        ConfigNode absent = YamlLoader.loadString("default-max-claims: 3");
        ConfigNode empty = YamlLoader.loadString("loot-table-overrides: {}");
        assertNull(absent.getSection("loot-table-overrides"));
        assertNotNull(empty.getSection("loot-table-overrides"));
        assertTrue(empty.getSection("loot-table-overrides").keys().isEmpty());
    }

    @Test
    void languageNormalisationMatchesTheOriginal() {
        assertEquals("en_US", ConfigManager.normalizeLanguage("en_us"));
        assertEquals("en_US", ConfigManager.normalizeLanguage("EN_US"));
        assertEquals("zh_CN", ConfigManager.normalizeLanguage("zh_CN"));
        assertEquals("zh_CN", ConfigManager.normalizeLanguage("fr_FR"));
        assertEquals("zh_CN", ConfigManager.normalizeLanguage(null));
    }

    @Test
    void yamlTypeCoercionIsForgivingLikeFileConfiguration() {
        ConfigNode node = YamlLoader.loadString("""
                as-string-number: "4"
                as-boolean-string: yes
                as-off: off
                as-list: [a, b]
                """);
        assertEquals(4, node.getInt("as-string-number", -1));
        assertTrue(node.getBoolean("as-boolean-string", false));
        assertFalse(node.getBoolean("as-off", true));
        assertEquals(List.of("a", "b"), node.getStringList("as-list"));
        assertEquals(-1, node.getInt("absent", -1));
        assertEquals(List.of(), node.getStringList("absent"));
    }
}
