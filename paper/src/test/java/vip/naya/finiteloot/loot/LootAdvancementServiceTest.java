package vip.naya.finiteloot.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LootAdvancementServiceTest {
    @Test
    void mapsEveryBastionLootTableToItsWarPigsCriterion() {
        Map<String, String> expected = Map.of(
                "minecraft:chests/bastion_bridge", "loot_bastion_bridge",
                "minecraft:chests/bastion_hoglin_stable", "loot_bastion_hoglin_stable",
                "minecraft:chests/bastion_other", "loot_bastion_other",
                "minecraft:chests/bastion_treasure", "loot_bastion_treasure");

        expected.forEach((lootTable, criterion) -> {
            LootAdvancementService.AdvancementCriterion target =
                    LootAdvancementService.criterionFor(lootTable);
            assertEquals("minecraft:nether/loot_bastion", target.advancement().toString());
            assertEquals(criterion, target.criterion());
        });
    }

    @Test
    void ignoresLootTablesWithoutContainerLootAdvancements() {
        assertNull(LootAdvancementService.criterionFor("minecraft:chests/ancient_city"));
        assertNull(LootAdvancementService.criterionFor(null));
    }
}
