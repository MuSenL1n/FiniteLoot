package vip.naya.finiteloot.loot;

import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

public final class LootAdvancementService {
    private static final NamespacedKey WAR_PIGS = NamespacedKey.minecraft("nether/loot_bastion");
    private static final Map<String, AdvancementCriterion> CRITERIA_BY_LOOT_TABLE = Map.of(
            "minecraft:chests/bastion_bridge",
            new AdvancementCriterion(WAR_PIGS, "loot_bastion_bridge"),
            "minecraft:chests/bastion_hoglin_stable",
            new AdvancementCriterion(WAR_PIGS, "loot_bastion_hoglin_stable"),
            "minecraft:chests/bastion_other",
            new AdvancementCriterion(WAR_PIGS, "loot_bastion_other"),
            "minecraft:chests/bastion_treasure",
            new AdvancementCriterion(WAR_PIGS, "loot_bastion_treasure"));

    public void award(Player player, String lootTable) {
        AdvancementCriterion target = criterionFor(lootTable);
        if (target == null) {
            return;
        }
        Advancement advancement = Bukkit.getAdvancement(target.advancement());
        if (advancement == null || !advancement.getCriteria().contains(target.criterion())) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (!progress.isDone()) {
            progress.awardCriteria(target.criterion());
        }
    }

    static AdvancementCriterion criterionFor(String lootTable) {
        return lootTable == null ? null : CRITERIA_BY_LOOT_TABLE.get(lootTable);
    }

    record AdvancementCriterion(NamespacedKey advancement, String criterion) {
    }
}
