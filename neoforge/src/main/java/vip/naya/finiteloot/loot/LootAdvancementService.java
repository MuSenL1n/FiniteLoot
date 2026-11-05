package vip.naya.finiteloot.loot;

import java.util.Map;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grants the "War Pigs" criteria that a normal bastion chest would have granted.
 *
 * <p>Why this exists at all: FiniteLoot takes the loot table away from the real container, so vanilla's
 * own "you looted a bastion chest" advancement trigger never fires. Without this, players who find their
 * bastion loot through FiniteLoot would silently fail to make advancement progress -- a small thing that
 * is very visible to anyone hunting advancements.
 *
 * <p>Only the four bastion tables are mapped, matching the original: those are the only vanilla loot
 * tables whose criteria are tied to looting a specific chest type.
 */
public final class LootAdvancementService {

    private static final ResourceLocation WAR_PIGS = ResourceLocation.withDefaultNamespace("nether/loot_bastion");

    private static final Map<String, Criterion> CRITERIA_BY_LOOT_TABLE = Map.of(
            "minecraft:chests/bastion_bridge",
            new Criterion(WAR_PIGS, "loot_bastion_bridge"),
            "minecraft:chests/bastion_hoglin_stable",
            new Criterion(WAR_PIGS, "loot_bastion_hoglin_stable"),
            "minecraft:chests/bastion_other",
            new Criterion(WAR_PIGS, "loot_bastion_other"),
            "minecraft:chests/bastion_treasure",
            new Criterion(WAR_PIGS, "loot_bastion_treasure"));

    public void award(ServerPlayer player, String lootTable) {
        Criterion target = criterionFor(lootTable);
        if (target == null) {
            return;
        }
        AdvancementHolder advancement = player.server.getAdvancements().get(target.advancement());
        if (advancement == null) {
            // A datapack can remove the advancement; that is not an error we should react to.
            return;
        }
        if (!advancement.value().criteria().containsKey(target.criterion())) {
            return;
        }
        // award() is idempotent for an already-completed criterion, so no isDone() check is needed here
        // (Paper had to check because AdvancementProgress#awardCriteria was not documented as idempotent).
        player.getAdvancements().award(advancement, target.criterion());
    }

    static Criterion criterionFor(String lootTable) {
        return lootTable == null ? null : CRITERIA_BY_LOOT_TABLE.get(lootTable);
    }

    record Criterion(ResourceLocation advancement, String criterion) {
    }
}
