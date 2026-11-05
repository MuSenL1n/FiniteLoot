package vip.naya.finiteloot.config;

import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Ported from the Paper plugin's {@code Messages}: same keys, same prefix-concatenation behaviour,
 * same "Missing message: key" fallback.
 *
 * <p>What changed is only the plumbing. Paper took a {@code JavaPlugin} and a {@code CommandSender} and
 * rendered through Adventure; here the raw templates come from a {@link LanguageFile} that was already
 * loaded without touching the game, and rendering goes through {@link MessageRenderer} into Minecraft's
 * own {@code Component}. The two systems are unrelated types despite both being called {@code Component},
 * which is exactly why this adapter exists as its own class.
 */
public final class Messages {

    private final LanguageFile language;

    public Messages(LanguageFile language) {
        this.language = language;
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> replacements) {
        String raw = language.raw(key);
        if (raw == null) {
            // Paper's fallback string, kept verbatim so a missing key looks the same as before.
            raw = "<red>Missing message: " + key + "</red>";
        }
        return MessageRenderer.render(raw, replacements);
    }

    /** The prefix plus a message, matching Paper's {@code send}. */
    public Component prefixed(String key) {
        return prefixed(key, Map.of());
    }

    public Component prefixed(String key, Map<String, String> replacements) {
        return component("prefix").copy().append(component(key, replacements));
    }

    public void send(ServerPlayer player, String key) {
        player.sendSystemMessage(prefixed(key));
    }

    public void send(ServerPlayer player, String key, Map<String, String> replacements) {
        player.sendSystemMessage(prefixed(key, replacements));
    }
}
