package vip.naya.finiteloot.gui;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Chest;
import org.bukkit.block.Lidded;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.TileState;
import vip.naya.finiteloot.container.ContainerTarget;

public record ContainerPresentation(List<Location> locations, Sound openSound, Sound closeSound) {
    public ContainerPresentation {
        locations = locations.stream().map(Location::clone).toList();
    }

    public static ContainerPresentation from(ContainerTarget target) {
        TileState primary = target.primary();
        Sound open;
        Sound close;
        if (primary instanceof Barrel) {
            open = Sound.BLOCK_BARREL_OPEN;
            close = Sound.BLOCK_BARREL_CLOSE;
        } else if (primary instanceof ShulkerBox) {
            open = Sound.BLOCK_SHULKER_BOX_OPEN;
            close = Sound.BLOCK_SHULKER_BOX_CLOSE;
        } else if (primary.getType().name().contains("COPPER_CHEST")) {
            open = Sound.BLOCK_COPPER_CHEST_OPEN;
            close = Sound.BLOCK_COPPER_CHEST_CLOSE;
        } else if (primary instanceof Chest) {
            open = Sound.BLOCK_CHEST_OPEN;
            close = Sound.BLOCK_CHEST_CLOSE;
        } else {
            open = null;
            close = null;
        }
        return new ContainerPresentation(
                target.tiles().stream().map(TileState::getLocation).toList(), open, close);
    }

    public void animate(boolean opening) {
        for (Location location : locations) {
            World world = location.getWorld();
            if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            if (location.getBlock().getState() instanceof Lidded lidded) {
                if (opening) {
                    lidded.open();
                } else {
                    lidded.close();
                }
            }
        }
    }

    public void playSound(boolean opening) {
        if (locations.isEmpty() || openSound == null || closeSound == null) {
            return;
        }
        Location location = locations.getFirst();
        World world = location.getWorld();
        if (world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            world.playSound(location.clone().add(0.5, 0.5, 0.5),
                    opening ? openSound : closeSound, SoundCategory.BLOCKS, 1.0F, 1.0F);
        }
    }
}
