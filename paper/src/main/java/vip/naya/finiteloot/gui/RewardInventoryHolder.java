package vip.naya.finiteloot.gui;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class RewardInventoryHolder implements InventoryHolder {
    private final UUID containerId;
    private final UUID playerId;
    private final ContainerPresentation presentation;
    private Inventory inventory;
    private boolean visualActive;
    private boolean closed;

    public RewardInventoryHolder(UUID containerId, UUID playerId, ContainerPresentation presentation) {
        this.containerId = containerId;
        this.playerId = playerId;
        this.presentation = presentation;
    }

    public UUID containerId() {
        return containerId;
    }

    public UUID playerId() {
        return playerId;
    }

    public ContainerPresentation presentation() {
        return presentation;
    }

    public void activateVisual() {
        visualActive = true;
    }

    public boolean deactivateVisual() {
        if (!visualActive) {
            return false;
        }
        visualActive = false;
        return true;
    }

    public boolean beginClose() {
        if (closed) {
            return false;
        }
        closed = true;
        return true;
    }

    public boolean isClosed() {
        return closed;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
