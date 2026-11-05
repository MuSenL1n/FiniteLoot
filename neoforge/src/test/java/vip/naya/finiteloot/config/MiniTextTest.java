package vip.naya.finiteloot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import vip.naya.finiteloot.config.MiniText.Color;
import vip.naya.finiteloot.config.MiniText.Run;

/**
 * Tests the reimplemented MiniMessage subset against the literal strings in the shipped language files.
 *
 * <p>The point is not to test MiniMessage in general -- it is to pin the behaviour of the specific
 * templates this project actually ships, so a change to the parser cannot silently change what players
 * see.
 */
class MiniTextTest {

    private static String plain(List<Run> runs) {
        StringBuilder text = new StringBuilder();
        for (Run run : runs) {
            text.append(run.text());
        }
        return text.toString();
    }

    private static Color colorOfOnlyRun(List<Run> runs) {
        assertEquals(1, runs.size(), "expected exactly one run but got " + runs);
        return runs.get(0).color();
    }

    @Test
    void rendersTheRealPrefixLineWithNestedColours() {
        // verbatim from lang/zh_CN.yml
        List<Run> runs = MiniText.parse("<gray>[<gold>限量战利品</gold>]</gray> ");
        assertEquals("[限量战利品] ", plain(runs));
        // The trailing space sits *outside* </gray>, so it carries no colour -- exactly as MiniMessage
        // would treat it. Collapsing it into the grey run would have been the plausible-looking bug.
        assertEquals(
                List.of(
                        new Run(Color.GRAY, "["),
                        new Run(Color.GOLD, "限量战利品"),
                        new Run(Color.GRAY, "]"),
                        new Run(null, " ")),
                runs);
    }

    @Test
    void substitutesPlaceholders() {
        List<Run> runs = MiniText.parse(
                "<gray>该奖励箱还可供 <gold><remaining></gold>/<max> 名玩家领取。</gray>",
                Map.of("remaining", "2", "max", "5"));
        assertEquals("该奖励箱还可供 2/5 名玩家领取。", plain(runs));
        assertEquals(
                List.of(new Run(Color.GRAY, "该奖励箱还可供 "), new Run(Color.GOLD, "2"), new Run(Color.GRAY, "/5 名玩家领取。")),
                runs);
    }

    @Test
    void substitutedValueIsNeverParsedAsMarkup() {
        // A player name is attacker-controlled. Paper escaped < and > then re-parsed; here the value is
        // inserted as literal text, so it cannot become a tag no matter what it contains.
        List<Run> runs = MiniText.parse("<green>已设置容器 <id>，全服上限为 <max>。</green>",
                Map.of("id", "<red>pwned</red>", "max", "3"));
        assertEquals("已设置容器 <red>pwned</red>，全服上限为 3。", plain(runs));
        for (Run run : runs) {
            assertEquals(Color.GREEN, run.color(), "the injected </red> must not have changed any colour");
        }
    }

    @Test
    void unknownChinesePlaceholderRendersLiterally() {
        // lang/zh_CN.yml usage: the "<次数>"/"<玩家>" placeholders are never substituted. MiniMessage
        // left them alone because they are not valid tag names; we must match that visible result.
        List<Run> runs = MiniText.parse("<yellow>/finiteloot set <次数></yellow>");
        assertEquals("/finiteloot set <次数>", plain(runs));
        assertEquals(Color.YELLOW, colorOfOnlyRun(runs));
    }

    @Test
    void unknownAsciiTagRendersLiterally() {
        // lang/en_US.yml usage uses <count>/<player>, also never substituted.
        List<Run> runs = MiniText.parse("<yellow>/finiteloot set <count></yellow>");
        assertEquals("/finiteloot set <count>", plain(runs));
    }

    @Test
    void multiLineBlockScalarKeepsLineBreaks() {
        // lang/*.yml "inspect" is a YAML block scalar; the parser must not collapse its newlines.
        List<Run> runs = MiniText.parse(
                "<gold>ID:</gold> <id>\n<gold>LootTable:</gold> <table>",
                Map.of("id", "abc", "table", "minecraft:chests/simple_dungeon"));
        assertEquals("ID: abc\nLootTable: minecraft:chests/simple_dungeon", plain(runs));
        assertEquals(4, runs.size(), runs.toString());
    }

    @Test
    void resetClearsAccumulatedColour() {
        List<Run> runs = MiniText.parse("<red>hot<reset>plain");
        assertEquals(List.of(new Run(Color.RED, "hot"), new Run(null, "plain")), runs);
    }

    @Test
    void strayClosingTagDoesNotDropText() {
        List<Run> runs = MiniText.parse("a</gold>b");
        assertEquals("ab", plain(runs));
    }

    @Test
    void unterminatedAngleBracketIsKeptAsText() {
        assertEquals("a <b", plain(MiniText.parse("a <b")));
        assertEquals("<", plain(MiniText.parse("<")));
    }

    @Test
    void emptyTemplateProducesNoRuns() {
        assertTrue(MiniText.parse("").isEmpty());
        assertTrue(MiniText.parse(null).isEmpty());
    }

    @Test
    void adjacentIdenticalColoursAreNotMerged() {
        // Not a correctness requirement, but pinning it stops a future "optimisation" from changing
        // run boundaries that the renderer depends on.
        List<Run> runs = MiniText.parse("<red>a</red><red>b</red>");
        assertEquals(List.of(new Run(Color.RED, "a"), new Run(Color.RED, "b")), runs);
    }

    @Test
    void everyColourNameInTheLanguageFilesIsRecognised() {
        for (String name : List.of("gold", "gray", "green", "red", "yellow")) {
            List<Run> runs = MiniText.parse("<" + name + ">x");
            assertTrue(runs.size() == 1 && runs.get(0).color() != null,
                    "'" + name + "' must parse as a colour, got " + runs);
        }
    }

    @Test
    void fullWidthChinesePunctuationSurvives() {
        List<Run> runs = MiniText.parse("<green>迁移、备份和数据校验完成：<backup></green>", Map.of("backup", "data-1.db"));
        assertEquals("迁移、备份和数据校验完成：data-1.db", plain(runs));
    }
}
