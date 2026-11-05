package vip.naya.finiteloot.container;

import com.mojang.serialization.Codec;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * The replacement for Paper's {@code PersistentDataContainer} keys.
 *
 * <p>Paper stored two things on each block entity: which FiniteLoot container it belongs to, and
 * whether an admin had told us to leave it alone. NeoForge's data attachments are the direct
 * equivalent -- {@code BlockEntity} extends {@code AttachmentHolder}, and NeoForge patches
 * {@code saveAdditional}/{@code loadAdditional} to write these into the block entity's own NBT. So the
 * lifetime matches Paper's exactly: they travel with the block, survive a chunk unload, and die with it.
 *
 * <p>Both attachments declare a {@code shouldSerialize} predicate so the default value is never
 * written. Without it every chest in the world would gain a couple of bytes of FiniteLoot NBT the first
 * time anything touched it, which is both wasteful and a needless world-format change.
 */
public final class AttachmentTypes {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ProbeMod.MOD_ID);

    /** The container id, as its canonical UUID string. Empty means "not managed by FiniteLoot". */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> CONTAINER_ID =
            ATTACHMENT_TYPES.register("container_id", () -> AttachmentType
                    .builder(() -> "")
                    .serialize(Codec.STRING, id -> !id.isEmpty())
                    .build());

    /** Set when an admin removes FiniteLoot from a container, so it is not silently taken over again. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> IGNORED =
            ATTACHMENT_TYPES.register("ignored", () -> AttachmentType
                    .builder(() -> Boolean.FALSE)
                    .serialize(Codec.BOOL, ignored -> ignored)
                    .build());

    /**
     * The loot table this container was seen with, remembered because reading the container clears it.
     *
     * <p>A loot table is not a stable property: opening the container, and even an automated handler
     * walking its slots, rolls the table and nulls it. Two things therefore cannot rely on "does it have
     * a table right now":
     *
     * <ul>
     *   <li><b>Eligibility.</b> A hopper that read a fresh dungeon chest would roll the table, stop
     *       qualifying, and drain the chest on its next tick.
     *   <li><b>Generation.</b> A container whose table was consumed before it was resolved has nothing to
     *       generate a per-player share from, and the claim fails outright.
     * </ul>
     *
     * <p>Remembering the table's <em>id</em> rather than a boolean fixes both: the container can still
     * generate from the same table even after the in-world one has been consumed. That is also why the
     * value is the id and not a flag -- a flag would say "it had loot once" but not which loot.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> LOOT_TABLE_ID =
            ATTACHMENT_TYPES.register("loot_table_id", () -> AttachmentType
                    .builder(() -> "")
                    .serialize(Codec.STRING, id -> !id.isEmpty())
                    .build());

    private AttachmentTypes() {
    }
}
