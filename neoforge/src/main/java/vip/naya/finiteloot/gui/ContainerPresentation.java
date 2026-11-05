package vip.naya.finiteloot.gui;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import vip.naya.finiteloot.container.ContainerTarget;

/**
 * Port of Paper's {@code ContainerPresentation}: the animation and the sound that a real container makes when
 * it is opened, so that a personal loot window still feels like the chest it replaced.
 *
 * <p>The two differences from Paper are both about the platform, not the intent:
 *
 * <ul>
 *   <li>Paper called {@code Lidded#open()}/{@code close()}, which is a Bukkit convenience that itself sends the
 *       block event and keeps a per-block counter. Here the block event is sent directly
 *       ({@code level.blockEvent(pos, block, 1, count)}), which is what the chest's own
 *       {@code triggerEvent} answers. The counter is not kept: opening and closing always send 1 and 0, so two
 *       players watching the same chest will not stack the lid angle. Cosmetic, and the alternative -- tracking
 *       viewers per block -- buys nothing a player can see.
 *   <li>Paper had a branch for copper chests. They do not exist in 1.21.1, so there is nothing to branch on.
 * </ul>
 *
 * <p>The positions are captured when the window opens. By the time it closes the block may be gone or its chunk
 * unloaded, so every use re-checks the chunk before touching it.
 */
public record ContainerPresentation(List<BlockPos> positions, SoundEvent openSound, SoundEvent closeSound) {

    /** No animation, no sound: used for containers that have neither, and for tests. */
    public static final ContainerPresentation NONE = new ContainerPresentation(List.of(), null, null);

    public ContainerPresentation {
        positions = List.copyOf(positions);
    }

    public static ContainerPresentation of(ContainerTarget target) {
        return from(target.tiles());
    }

    /**
     * What this container should look and sound like.
     *
     * <p>Decided from the block entity type rather than from the block, because that is what carries both the
     * sound and the {@code triggerEvent} behaviour.
     */
    public static ContainerPresentation from(List<BlockEntity> tiles) {
        if (tiles.isEmpty()) {
            return NONE;
        }
        BlockEntity primary = tiles.getFirst();
        SoundEvent open;
        SoundEvent close;
        if (primary instanceof BarrelBlockEntity) {
            open = SoundEvents.BARREL_OPEN;
            close = SoundEvents.BARREL_CLOSE;
        } else if (primary instanceof ShulkerBoxBlockEntity) {
            open = SoundEvents.SHULKER_BOX_OPEN;
            close = SoundEvents.SHULKER_BOX_CLOSE;
        } else if (primary instanceof net.minecraft.world.level.block.entity.ChestBlockEntity) {
            open = SoundEvents.CHEST_OPEN;
            close = SoundEvents.CHEST_CLOSE;
        } else {
            open = null;
            close = null;
        }
        return new ContainerPresentation(
                tiles.stream().map(BlockEntity::getBlockPos).toList(), open, close);
    }

    /** Sends the chest's lid block event, if this container has one. */
    public void animate(ServerLevel level, boolean opening) {
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            // Block event 1 is "set the number of open viewers" for both chests and barrels.
            level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, opening ? 1 : 0);
        }
    }

    public void playSound(ServerLevel level, boolean opening) {
        SoundEvent sound = opening ? openSound : closeSound;
        if (sound == null || positions.isEmpty()) {
            return;
        }
        BlockPos pos = positions.getFirst();
        if (!level.isLoaded(pos)) {
            return;
        }
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
    }
}

