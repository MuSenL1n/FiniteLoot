package vip.naya.finiteloot.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class Messages {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final YamlConfiguration messages;

    public Messages(JavaPlugin plugin, String language) {
        String normalized = "en_US".equalsIgnoreCase(language) ? "en_US" : "zh_CN";
        String resource = "lang/" + normalized + ".yml";
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException exception) {
            plugin.getLogger().warning("Failed to load built-in language defaults: " + exception.getMessage());
        }
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> replacements) {
        String raw = messages.getString(key, "<red>Missing message: " + key + "</red>");
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            raw = raw.replace("<" + entry.getKey() + ">", escape(entry.getValue()));
        }
        return miniMessage.deserialize(raw);
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(component("prefix").append(component(key)));
    }

    public void send(CommandSender sender, String key, Map<String, String> replacements) {
        sender.sendMessage(component("prefix").append(component(key, replacements)));
    }

    private String escape(String value) {
        return value.replace("<", "\\<").replace(">", "\\>");
    }
}
