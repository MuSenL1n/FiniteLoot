package vip.naya.finiteloot.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
        int defaultMaxClaims,
        boolean showRemainingClaims,
        boolean playContainerAnimation,
        boolean playContainerSounds,
        boolean triggerPiglinAnger,
        boolean preventItemInsertion,
        boolean completedContainerBecomesNormal,
        FinalClaimAction finalClaimAction,
        boolean clearPersonalInventoriesOnFinalClaim,
        boolean showFinalClaimMessage,
        boolean preventHopperExtraction,
        boolean preventBreaking,
        boolean allowBreakingExhaustedContainers,
        boolean preventExplosions,
        boolean countCreativePlayers,
        boolean adminBypassCounts,
        Set<String> excludedWorlds,
        Map<String, Integer> lootTableOverrides,
        String language) {

    public static PluginSettings from(FileConfiguration config) {
        int maxClaims = Math.max(1, config.getInt("default-max-claims", 3));
        int perPlayerLimit = config.getInt("per-player-limit", 1);
        if (perPlayerLimit != 1) {
            throw new IllegalArgumentException("per-player-limit must be 1; FiniteLoot guarantees one claim per player");
        }
        String exhaustedAction = config.getString("exhausted-action", "DENY");
        if (!"DENY".equalsIgnoreCase(exhaustedAction)) {
            throw new IllegalArgumentException("Only exhausted-action: DENY is supported");
        }

        String finalClaimActionName = config.getString("final-claim-action", "VANILLA_CONTAINER");
        FinalClaimAction finalClaimAction;
        try {
            finalClaimAction = FinalClaimAction.valueOf(finalClaimActionName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported final-claim-action: " + finalClaimActionName
                            + " (use VANILLA_CONTAINER or PERSONAL)", exception);
        }

        Set<String> excluded = new HashSet<>();
        for (String world : config.getStringList("excluded-worlds")) {
            excluded.add(world.toLowerCase(java.util.Locale.ROOT));
        }

        Map<String, Integer> overrides = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("loot-table-overrides");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String normalized = NamespacedKey.fromString(key) == null ? key : key.toLowerCase(java.util.Locale.ROOT);
                overrides.put(normalized, Math.max(1, section.getInt(key)));
            }
        }
        return new PluginSettings(
                maxClaims,
                config.getBoolean("show-remaining-claims", true),
                config.getBoolean("play-container-animation", true),
                config.getBoolean("play-container-sounds", true),
                config.getBoolean("trigger-piglin-anger", true),
                config.getBoolean("prevent-item-insertion", false),
                config.getBoolean("completed-container-becomes-normal", true),
                finalClaimAction,
                config.getBoolean("clear-personal-inventories-on-final-claim", true),
                config.getBoolean("show-final-claim-message", true),
                config.getBoolean("prevent-hopper-extraction", true),
                config.getBoolean("prevent-breaking", true),
                config.getBoolean("allow-breaking-exhausted-containers", false),
                config.getBoolean("prevent-explosions", true),
                config.getBoolean("count-creative-players", false),
                config.getBoolean("admin-bypass-counts", false),
                Collections.unmodifiableSet(excluded),
                Collections.unmodifiableMap(overrides),
                config.getString("language", "zh_CN"));
    }

    public int maxClaimsFor(String lootTable) {
        return lootTable == null ? defaultMaxClaims : lootTableOverrides.getOrDefault(lootTable, defaultMaxClaims);
    }

    public boolean isWorldExcluded(String name) {
        return excludedWorlds.contains(name.toLowerCase(java.util.Locale.ROOT));
    }
}
