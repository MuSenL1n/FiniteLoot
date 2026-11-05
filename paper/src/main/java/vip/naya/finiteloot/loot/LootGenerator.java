package vip.naya.finiteloot.loot;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.data.ContainerRecord;

public final class LootGenerator {
    public ItemStack[] generate(ContainerRecord record, ContainerTarget target, Player player) {
        if (record.templateContents() != null) {
            return ItemStacks.deserialize(record.templateContents());
        }
        LootTable table = target.lootTable();
        if (table == null && record.lootTable() != null) {
            NamespacedKey key = NamespacedKey.fromString(record.lootTable());
            table = key == null ? null : Bukkit.getLootTable(key);
        }
        if (table == null) {
            throw new IllegalStateException("Loot table is no longer available for " + record.id());
        }
        AttributeInstance luckAttribute = player.getAttribute(Attribute.LUCK);
        float luck = luckAttribute == null ? 0.0F : (float) luckAttribute.getValue();
        LootContext context = new LootContext.Builder(target.primary().getLocation())
                .lootedEntity(player)
                .luck(luck)
                .build();
        Collection<ItemStack> generated = table.populateLoot(ThreadLocalRandom.current(), context);
        int size = target.sourceInventory().getSize();
        ItemStack[] contents = new ItemStack[size];
        int index = 0;
        for (ItemStack item : generated) {
            if (index >= size) {
                break;
            }
            contents[index++] = item;
        }
        shuffle(contents);
        return contents;
    }

    private void shuffle(ItemStack[] contents) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = contents.length - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            ItemStack temporary = contents[index];
            contents[index] = contents[swap];
            contents[swap] = temporary;
        }
    }
}
