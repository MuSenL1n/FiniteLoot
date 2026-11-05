package vip.naya.finiteloot.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The subset of MiniMessage that FiniteLoot's language files actually use, reimplemented natively.
 *
 * <h2>Why not just depend on MiniMessage</h2>
 * The Paper plugin rendered messages with Adventure's MiniMessage. NeoForge does not ship Adventure,
 * and the production server was checked directly: there is not one {@code net/kyori/adventure} class
 * on the whole classpath. Bundling Adventure would mean pulling in its API, the MiniMessage parser,
 * and a serializer, and then <em>still</em> writing a converter from Adventure's {@code Component} to
 * Minecraft's -- because the two are unrelated types despite sharing a name. That is a lot of weight
 * to reproduce five colours.
 *
 * <p>So the tag vocabulary was measured first rather than guessed. Across both language files the
 * only tags used are {@code gold}, {@code gray}, {@code green}, {@code red}, {@code yellow}, their
 * closing forms, and angle-bracket placeholders such as {@code <remaining>}. That is the entire
 * surface, and supporting the rest of MiniMessage would be inventing requirements.
 *
 * <h2>Two deliberate behaviour notes</h2>
 * <ol>
 *   <li><b>Unknown tags render literally instead of raising.</b> {@code zh_CN.yml} writes its usage
 *       line as {@code /finiteloot set <次数>} -- a placeholder that is never substituted. MiniMessage
 *       left it alone because non-ASCII tag names do not match its tag grammar; here anything that is
 *       neither a colour nor a known placeholder is emitted as-is, which gives the same visible
 *       result for that line and cannot throw mid-render.
 *   <li><b>Substituted values are never re-parsed.</b> Paper escaped {@code <} and {@code >} in
 *       replacement values and then re-parsed the whole string. This implementation appends the
 *       replacement as literal text into the run list, so a player name such as {@code <red>x} is
 *       structurally incapable of being interpreted as markup. Same visible output for honest input,
 *       no escaping step to get wrong for hostile input.
 * </ol>
 *
 * <p>Deliberately free of Minecraft imports: the parsing (the part with real edge cases) is unit
 * tested without the game, and {@link MessageRenderer} is the thin adapter to
 * {@code net.minecraft.network.chat.Component}.
 */
public final class MiniText {

    /** The sixteen legacy colours plus reset, which is all the language files reference. */
    public enum Color {
        BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE,
        GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE,
        RESET
    }

    /** A contiguous span of text sharing one colour; {@code color == null} means "inherit". */
    public record Run(Color color, String text) {
    }

    private MiniText() {
    }

    public static List<Run> parse(String template) {
        return parse(template, Map.of());
    }

    public static List<Run> parse(String template, Map<String, String> replacements) {
        List<Run> runs = new ArrayList<>();
        if (template == null || template.isEmpty()) {
            return runs;
        }

        Deque<Color> stack = new ArrayDeque<>();
        StringBuilder pending = new StringBuilder();
        int index = 0;

        while (index < template.length()) {
            int open = template.indexOf('<', index);
            if (open < 0) {
                pending.append(template, index, template.length());
                break;
            }
            int close = template.indexOf('>', open + 1);
            if (close < 0) {
                // An unterminated '<' is just text; do not drop the tail.
                pending.append(template, index, template.length());
                break;
            }
            pending.append(template, index, open);
            String tag = template.substring(open + 1, close);

            if (!tag.isEmpty() && tag.charAt(0) == '/') {
                Color closing = colorOf(tag.substring(1));
                if (closing == null) {
                    pending.append(template, open, close + 1);
                } else {
                    flush(runs, stack, pending);
                    popMatching(stack, closing);
                }
            } else {
                Color opening = colorOf(tag);
                if (opening != null) {
                    flush(runs, stack, pending);
                    if (opening == Color.RESET) {
                        stack.clear();
                    } else {
                        stack.push(opening);
                    }
                } else {
                    String replacement = replacements.get(tag);
                    if (replacement != null) {
                        // Literal insert: never re-scanned for tags.
                        pending.append(replacement);
                    } else {
                        pending.append(template, open, close + 1);
                    }
                }
            }
            index = close + 1;
        }

        flush(runs, stack, pending);
        return runs;
    }

    private static void flush(List<Run> runs, Deque<Color> stack, StringBuilder pending) {
        if (pending.isEmpty()) {
            return;
        }
        runs.add(new Run(stack.peek(), pending.toString()));
        pending.setLength(0);
    }

    private static void popMatching(Deque<Color> stack, Color color) {
        if (stack.isEmpty()) {
            return;
        }
        if (stack.peek() == color) {
            stack.pop();
            return;
        }
        // Malformed nesting: remove the nearest matching entry rather than ignoring the close.
        stack.remove(color);
    }

    private static Color colorOf(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "black" -> Color.BLACK;
            case "dark_blue" -> Color.DARK_BLUE;
            case "dark_green" -> Color.DARK_GREEN;
            case "dark_aqua" -> Color.DARK_AQUA;
            case "dark_red" -> Color.DARK_RED;
            case "dark_purple" -> Color.DARK_PURPLE;
            case "gold" -> Color.GOLD;
            case "gray", "grey" -> Color.GRAY;
            case "dark_gray", "dark_grey" -> Color.DARK_GRAY;
            case "blue" -> Color.BLUE;
            case "green" -> Color.GREEN;
            case "aqua" -> Color.AQUA;
            case "red" -> Color.RED;
            case "light_purple" -> Color.LIGHT_PURPLE;
            case "yellow" -> Color.YELLOW;
            case "white" -> Color.WHITE;
            case "reset" -> Color.RESET;
            default -> null;
        };
    }
}
