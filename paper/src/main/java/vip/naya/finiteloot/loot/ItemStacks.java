package vip.naya.finiteloot.loot;

import java.util.Arrays;
import java.util.Collection;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ItemStacks {
    private ItemStacks() {
    }

    public static byte[] serialize(ItemStack[] contents) {
        return ItemStack.serializeItemsAsBytes(Arrays.asList(contents));
    }

    public static ItemStack[] deserialize(byte[] bytes) {
        return ItemStack.deserializeItemsFromBytes(bytes);
    }

    public static boolean isEmpty(Collection<ItemStack> contents) {
        return contents.stream().allMatch(item -> item == null || item.getType() == Material.AIR || item.getAmount() <= 0);
    }

    public static boolean isEmpty(ItemStack[] contents) {
        return isEmpty(Arrays.asList(contents));
    }
}
