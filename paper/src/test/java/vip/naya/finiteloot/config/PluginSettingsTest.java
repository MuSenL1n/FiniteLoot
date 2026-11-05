package vip.naya.finiteloot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {
    @Test
    void appliesLootTableOverride() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("default-max-claims", 3);
        configuration.set("per-player-limit", 1);
        configuration.set("exhausted-action", "DENY");
        configuration.set("loot-table-overrides.minecraft:chests/ancient_city", 2);
        PluginSettings settings = PluginSettings.from(configuration);
        assertEquals(2, settings.maxClaimsFor("minecraft:chests/ancient_city"));
        assertEquals(3, settings.maxClaimsFor("minecraft:chests/shipwreck_treasure"));
        assertTrue(settings.showRemainingClaims());
        assertTrue(settings.playContainerAnimation());
        assertTrue(settings.playContainerSounds());
        assertTrue(settings.triggerPiglinAnger());
        assertFalse(settings.preventItemInsertion());
        assertTrue(settings.completedContainerBecomesNormal());
        assertEquals(FinalClaimAction.VANILLA_CONTAINER, settings.finalClaimAction());
        assertTrue(settings.clearPersonalInventoriesOnFinalClaim());
        assertTrue(settings.showFinalClaimMessage());
        assertFalse(settings.allowBreakingExhaustedContainers());
    }

    @Test
    void piglinAngerCanBeDisabled() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("per-player-limit", 1);
        configuration.set("exhausted-action", "DENY");
        configuration.set("trigger-piglin-anger", false);
        assertFalse(PluginSettings.from(configuration).triggerPiglinAnger());
    }

    @Test
    void exhaustedContainersCanBecomeBreakable() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("per-player-limit", 1);
        configuration.set("exhausted-action", "DENY");
        configuration.set("allow-breaking-exhausted-containers", true);
        assertTrue(PluginSettings.from(configuration).allowBreakingExhaustedContainers());
    }

    @Test
    void remainingClaimMessageCanBeDisabled() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("per-player-limit", 1);
        configuration.set("exhausted-action", "DENY");
        configuration.set("show-remaining-claims", false);
        assertFalse(PluginSettings.from(configuration).showRemainingClaims());
    }

    @Test
    void itemInsertionCanBePreventedExplicitly() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("per-player-limit", 1);
        configuration.set("exhausted-action", "DENY");
        configuration.set("prevent-item-insertion", true);
        assertTrue(PluginSettings.from(configuration).preventItemInsertion());
    }

    @Test
    void completedContainerCanRemainFiniteLootManaged() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("per-player-limit", 1);
        configuration.set("exhausted-action", "DENY");
        configuration.set("completed-container-becomes-normal", false);
        assertFalse(PluginSettings.from(configuration).completedContainerBecomesNormal());
    }

    @Test
    void finalClaimCanRemainPersonal() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("per-player-limit", 1);
        configuration.set("exhausted-action", "DENY");
        configuration.set("final-claim-action", "personal");
        configuration.set("clear-personal-inventories-on-final-claim", false);
        configuration.set("show-final-claim-message", false);
        PluginSettings settings = PluginSettings.from(configuration);
        assertEquals(FinalClaimAction.PERSONAL, settings.finalClaimAction());
        assertFalse(settings.clearPersonalInventoriesOnFinalClaim());
        assertFalse(settings.showFinalClaimMessage());
    }
}
