package vip.naya.finiteloot.config;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Turns {@link MiniText}'s parsed runs into a real Minecraft {@link Component}.
 *
 * <p>This is the only place in the config layer that touches the game. Keeping it separate is what
 * lets the parser itself -- the part with the actual edge cases (nesting, unknown tags, substituted
 * values containing angle brackets) -- be unit tested without a Minecraft runtime.
 */
public final class MessageRenderer {

    private MessageRenderer() {
    }

    public static Component render(List<MiniText.Run> runs) {
        MutableComponent root = Component.empty();
        for (MiniText.Run run : runs) {
            if (run.text().isEmpty()) {
                continue;
            }
            MutableComponent part = Component.literal(run.text());
            ChatFormatting formatting = formatting(run.color());
            if (formatting != null) {
                part = part.withStyle(formatting);
            }
            root.append(part);
        }
        return root;
    }

    public static Component render(String template, java.util.Map<String, String> replacements) {
        return render(MiniText.parse(template, replacements));
    }

    private static ChatFormatting formatting(MiniText.Color color) {
        if (color == null || color == MiniText.Color.RESET) {
            // RESET means "inherit": leaving the style untouched is exactly that, because each run is
            // its own sibling component under a root that carries no colour.
            return null;
        }
        return switch (color) {
            case BLACK -> ChatFormatting.BLACK;
            case DARK_BLUE -> ChatFormatting.DARK_BLUE;
            case DARK_GREEN -> ChatFormatting.DARK_GREEN;
            case DARK_AQUA -> ChatFormatting.DARK_AQUA;
            case DARK_RED -> ChatFormatting.DARK_RED;
            case DARK_PURPLE -> ChatFormatting.DARK_PURPLE;
            case GOLD -> ChatFormatting.GOLD;
            case GRAY -> ChatFormatting.GRAY;
            case DARK_GRAY -> ChatFormatting.DARK_GRAY;
            case BLUE -> ChatFormatting.BLUE;
            case GREEN -> ChatFormatting.GREEN;
            case AQUA -> ChatFormatting.AQUA;
            case RED -> ChatFormatting.RED;
            case LIGHT_PURPLE -> ChatFormatting.LIGHT_PURPLE;
            case YELLOW -> ChatFormatting.YELLOW;
            case WHITE -> ChatFormatting.WHITE;
            case RESET -> null;
        };
    }
}
