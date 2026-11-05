package vip.naya.finiteloot.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A read-only view over parsed YAML, standing in for Bukkit's {@code FileConfiguration}.
 *
 * <p>The port has no Bukkit, so the accessors {@link PluginSettings} used to get for free have to be
 * rebuilt. This class is deliberately platform-free (no Minecraft, no Bukkit) so the whole config
 * layer stays unit-testable -- the same discipline the data layer follows.
 *
 * <p>Type handling is intentionally forgiving in one direction only: YAML hands back whatever the
 * document says ({@code 3} may arrive as {@code Integer}, {@code 3L}, or the string {@code "3"}), so
 * numeric accessors coerce, while a <em>wrong-typed</em> value silently falls back to the supplied
 * default rather than throwing. That mirrors how Bukkit's config behaved, and it means a typo in a
 * config file degrades to a default instead of preventing the server from starting.
 */
public final class ConfigNode {

    private static final ConfigNode EMPTY = new ConfigNode(Map.of());

    private final Map<String, Object> data;

    public ConfigNode(Map<String, Object> data) {
        this.data = data == null ? Map.of() : data;
    }

    public static ConfigNode empty() {
        return EMPTY;
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(data.keySet());
    }

    public boolean contains(String key) {
        return data.containsKey(key);
    }

    public int getInt(String key, int fallback) {
        Number number = asNumber(data.get(key));
        return number == null ? fallback : number.intValue();
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = data.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            // YAML 1.1 treats yes/no/on/off as booleans; accept the same spelling here so a config
            // written for Paper keeps parsing to the same value.
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on" -> true;
                case "false", "no", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    public String getString(String key, String fallback) {
        Object value = data.get(key);
        if (value == null) {
            return fallback;
        }
        return value instanceof String text ? text : String.valueOf(value);
    }

    public List<String> getStringList(String key) {
        Object value = data.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item))
                    .toList();
        }
        return List.of();
    }

    /**
     * Returns a child mapping, or {@code null} when the key is absent or is not a mapping.
     *
     * <p>{@code null} rather than an empty node is deliberate: {@link PluginSettings} distinguishes
     * "no {@code loot-table-overrides:} block at all" from "an empty one", and collapsing those two
     * would hide a malformed section.
     */
    public ConfigNode getSection(String key) {
        Object value = data.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return new ConfigNode(copy);
        }
        return null;
    }

    private static Number asNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
