package vip.naya.finiteloot.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ported from the Paper plugin's {@code PluginSettings}.
 *
 * <p>Every field, every default, and every validation rule is preserved on purpose: the stated goal
 * for this port is "broadly identical, port-only changes", so an operator's existing {@code config.yml}
 * has to keep meaning exactly what it meant before. The only real change is the input type -- Bukkit's
 * {@code FileConfiguration} became {@link ConfigNode} -- because that class does not exist outside
 * Bukkit. The class stays free of Minecraft imports so it remains unit-testable.
 */
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

    /** Matches a bare {@code namespace:path} pair, the shape {@code NamespacedKey} accepted. */
    private static final java.util.regex.Pattern NAMESPACED_KEY =
            java.util.regex.Pattern.compile("[a-z0-9._-]+:[a-z0-9._/-]+", java.util.regex.Pattern.CASE_INSENSITIVE);

    public static PluginSettings from(ConfigNode config) {
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
            finalClaimAction = FinalClaimAction.valueOf(finalClaimActionName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported final-claim-action: " + finalClaimActionName
                            + " (use VANILLA_CONTAINER or PERSONAL)", exception);
        }

        Set<String> excluded = new HashSet<>();
        for (String world : config.getStringList("excluded-worlds")) {
            excluded.add(world.toLowerCase(Locale.ROOT));
        }

        Map<String, Integer> overrides = new HashMap<>();
        ConfigNode section = config.getSection("loot-table-overrides");
        if (section != null) {
            for (String key : section.keys()) {
                // Paper did `NamespacedKey.fromString(key) == null ? key : key.toLowerCase()`: only
                // syntactically valid namespaced keys were normalised. Reproduce that exactly, so a
                // key written with odd casing behaves the same here as it did there.
                String normalized = NAMESPACED_KEY.matcher(key).matches() ? key.toLowerCase(Locale.ROOT) : key;
                overrides.put(normalized, Math.max(1, section.getInt(key, 1)));
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
        return excludedWorlds.contains(name.toLowerCase(Locale.ROOT));
    }
}
