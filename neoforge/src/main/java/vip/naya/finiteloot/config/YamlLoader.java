package vip.naya.finiteloot.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code config.yml} / language files with SnakeYAML.
 *
 * <p>SnakeYAML rather than a hand-rolled reader is a deliberate call. Users edit {@code config.yml} by
 * hand, and hand-written YAML gets quoted strings, block scalars ({@code |-}, used by the {@code inspect}
 * message), comments and lists. A partial parser would be a correctness trap disguised as a small
 * dependency. SnakeYAML is also the same class of parser Paper used beneath its {@code FileConfiguration},
 * so an existing config file is interpreted the way it was before -- which is the actual requirement here.
 *
 * <p>{@link SafeConstructor} is used so that a crafted config file cannot instantiate arbitrary classes;
 * YAML is a data format for this purpose, not a serialisation format.
 */
public final class YamlLoader {

    private YamlLoader() {
    }

    public static ConfigNode load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return loadString(text, file.toString());
    }

    public static ConfigNode loadString(String text) {
        return loadString(text, "<inline>");
    }

    public static ConfigNode loadString(String text, String source) {
        LoaderOptions options = new LoaderOptions();
        // A duplicated key is a config mistake that silently shadows earlier values; refuse it loudly
        // rather than letting the last one win.
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        Object root;
        try {
            root = yaml.load(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed YAML in " + source + ": " + exception.getMessage(), exception);
        }
        if (root == null) {
            return ConfigNode.empty();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    source + " must contain a mapping at the top level, found " + root.getClass().getSimpleName());
        }
        return new ConfigNode(coerce(map));
    }

    /**
     * SnakeYAML returns {@code Map<Object,Object>} with whatever key types the document used. Config
     * lookups are by String, so normalise keys (and nested maps) once, here, instead of at every read.
     */
    private static Map<String, Object> coerce(Map<?, ?> map) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), normalizeValue(value)));
        return result;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return coerce(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(YamlLoader::normalizeValue).toList();
        }
        return value;
    }
}
