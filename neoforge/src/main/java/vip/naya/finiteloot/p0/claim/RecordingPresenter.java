package vip.naya.finiteloot.p0.claim;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import vip.naya.finiteloot.claim.RewardPresenter;
import vip.naya.finiteloot.container.ContainerTarget;

/**
 * A {@link RewardPresenter} that records what would have been shown, instead of opening a GUI.
 *
 * <p>P4 has no GUI (that is P5), but the claim flow still has to be exercised end to end. Recording the
 * presentation calls is what makes the flow observable: whether the player was shown their personal loot
 * or the real container is the entire visible outcome of the state machine.
 */
final class RecordingPresenter implements RewardPresenter {

    /** One recorded presentation. */
    record Presentation(String kind, UUID containerId, int slotCount, int itemCount, String fingerprint) {
        @Override
        public String toString() {
            return kind + " container=" + containerId + " slots=" + slotCount
                    + " items=" + itemCount + " fingerprint=" + fingerprint;
        }
    }

    private final List<Presentation> events = new CopyOnWriteArrayList<>();

    @Override
    public void openPersonal(ServerPlayer player, UUID containerId, ItemStack[] contents, ContainerTarget target) {
        events.add(new Presentation("personal", containerId, contents.length,
                countItems(contents), fingerprint(contents)));
    }

    @Override
    public void openNormal(ServerPlayer player, ContainerTarget target) {
        ItemStack[] contents = new ItemStack[target.sourceInventory().getContainerSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = target.sourceInventory().getItem(slot);
        }
        events.add(new Presentation("normal", target.id(), contents.length,
                countItems(contents), fingerprint(contents)));
    }

    @Override
    public void closeContainer(UUID containerId) {
        events.add(new Presentation("close", containerId, 0, 0, "-"));
    }

    @Override
    public CompletableFuture<Void> closeContainerAndSave(UUID containerId) {
        events.add(new Presentation("closeAndSave", containerId, 0, 0, "-"));
        return CompletableFuture.completedFuture(null);
    }

    List<Presentation> events() {
        return events;
    }

    Presentation last() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    Presentation lastOfKind(String kind) {
        for (int index = events.size() - 1; index >= 0; index--) {
            if (events.get(index).kind().equals(kind)) {
                return events.get(index);
            }
        }
        return null;
    }

    int countOfKind(String kind) {
        int total = 0;
        for (Presentation event : events) {
            if (event.kind().equals(kind)) {
                total++;
            }
        }
        return total;
    }

    /** Item identity and count, so a round trip through the database can be compared. */
    static String fingerprint(ItemStack[] contents) {
        StringBuilder text = new StringBuilder();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            text.append(stack.getItem().toString())
                    .append('x').append(stack.getCount()).append(';');
        }
        return text.toString();
    }

    static int countItems(ItemStack[] contents) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (stack != null && !stack.isEmpty()) {
                total++;
            }
        }
        return total;
    }
}
