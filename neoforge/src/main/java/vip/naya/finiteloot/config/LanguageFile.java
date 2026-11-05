package vip.naya.finiteloot.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One language file's raw message templates, plus the built-in defaults as a fallback layer.
 *
 * <p>Paper's {@code Messages} loaded the on-disk file and then called {@code setDefaults(...)} with the
 * bundled copy, so a key added in a later version still appears for users who kept an old file. That
 * two-layer lookup is preserved: {@link #raw(String)} checks the user's file first, then the bundled
 * default.
 *
 * <p>Kept free of Minecraft types so it can be unit tested; {@link Messages} adds the rendering.
 */
public final class LanguageFile {

    private final Map<String, String> values;
    private final Map<String, String> defaults;

    public LanguageFile(Map<String, String> values, Map<String, String> defaults) {
        this.values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.defaults = defaults == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
    }

    public static LanguageFile of(Map<String, String> values) {
        return new LanguageFile(values, Map.of());
    }

    /** User value, then bundled default, then {@code null} so the caller can decide what to show. */
    public String raw(String key) {
        String value = values.get(key);
        if (value != null) {
            return value;
        }
        return defaults.get(key);
    }

    public Map<String, String> values() {
        return values;
    }

    public Map<String, String> defaults() {
        return defaults;
    }

    public boolean has(String key) {
        return raw(key) != null;
    }

    /** Flattens a parsed config tree into {@code key -> template} (nested keys joined with '#'). */
    public static Map<String, String> flatten(ConfigNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        collect("", node, result);
        return result;
    }

    private static void collect(String prefix, ConfigNode node, Map<String, String> out) {
        for (String key : node.keys()) {
            ConfigNode child = node.getSection(key);
            if (child != null) {
                collect(prefix.isEmpty() ? key : prefix + "#" + key, child, out);
            } else {
                String value = node.getString(key, null);
                if (value != null) {
                    out.put(prefix.isEmpty() ? key : prefix + "#" + key, value);
                }
            }
        }
    }
}
