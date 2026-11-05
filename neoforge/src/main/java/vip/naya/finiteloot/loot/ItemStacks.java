package vip.naya.finiteloot.loot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Turns an inventory into the {@code BLOB} the database stores, and back.
 *
 * <p>Paper used {@code ItemStack.serializeItemsAsBytes}, whose byte layout is a Bukkit implementation
 * detail. The mod cannot reuse that format and does not need to -- there is no existing database to stay
 * compatible with. What matters is that the format is stable within this project, because a container's
 * personal inventory lives on disk for as long as the container does.
 *
 * <p>The chosen format is a compressed NBT compound holding one list entry per slot:
 *
 * <pre>
 * { items: [ {id:"minecraft:diamond",count:3}, {}, ... ] }
 * </pre>
 *
 * <p><b>Slot count is preserved.</b> Empty slots are written as empty compounds rather than omitted, so a
 * reloaded inventory has exactly the size it had when it was saved. Dropping trailing empties would make
 * a 27-slot chest come back as, say, 3 slots, and the GUI would then show the wrong number of slots.
 *
 * <p><b>Null and empty are the same thing here.</b> Paper's arrays could hold {@code null} or
 * {@code Material.AIR}; both mean "nothing in this slot", and {@link #isEmpty} treats them identically so
 * the "did this player get anything" test cannot depend on which one happened to be stored.
 */
public final class ItemStacks {

    private static final String ITEMS_KEY = "items";

    private ItemStacks() {
    }

    public static byte[] serialize(ItemStack[] contents, HolderLookup.Provider registries) throws IOException {
        ListTag list = new ListTag();
        for (ItemStack stack : contents) {
            ItemStack value = stack == null ? ItemStack.EMPTY : stack;
            Tag encoded = ItemStack.OPTIONAL_CODEC
                    .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value)
                    .getOrThrow(error -> new IOException("Could not encode an item stack: " + error));
            list.add(encoded);
        }
        CompoundTag root = new CompoundTag();
        root.put(ITEMS_KEY, list);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    public static ItemStack[] deserialize(byte[] bytes, HolderLookup.Provider registries) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new ItemStack[0];
        }
        CompoundTag root;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        ListTag list = root.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
        ItemStack[] contents = new ItemStack[list.size()];
        for (int slot = 0; slot < list.size(); slot++) {
            int index = slot;
            contents[slot] = ItemStack.OPTIONAL_CODEC
                    .parse(registries.createSerializationContext(NbtOps.INSTANCE), list.get(slot))
                    .getOrThrow(error -> new IOException(
                            "Could not decode the item stack in slot " + index + ": " + error));
        }
        return contents;
    }

    public static boolean isEmpty(ItemStack[] contents) {
        if (contents == null) {
            return true;
        }
        for (ItemStack stack : contents) {
            if (stack != null && !stack.isEmpty() && stack.getCount() > 0) {
                return false;
            }
        }
        return true;
    }
}
