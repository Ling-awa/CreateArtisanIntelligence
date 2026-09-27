package io.github.Ling.create_ai;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Where a tool's target is, for every kind of block a tool acts on.
 *
 * <p>Kept in one place because the same question is asked about three different blocks — a depot, a
 * processing table (which is a depot) and a basin — and the answer has to be computed identically on
 * both sides: the client decides whether to start a hold from the same ray the server will use when
 * the hold completes.
 */
public final class ToolTargets {

    private ToolTargets() {
    }

    /** The block entity of the given kind at a position, or null if something else is there. */
    @Nullable
    public static <T extends BlockEntity> T at(Level level, BlockPos pos, Class<T> type) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return type.isInstance(blockEntity) ? type.cast(blockEntity) : null;
    }

    /**
     * The block entity of the given kind the player's eyes are on.
     *
     * <p>{@code ClipContext.Fluid.NONE} so a tool aimed through water at a depot still finds it.
     */
    @Nullable
    public static <T extends BlockEntity> T lookingAt(Player player, Class<T> type) {
        BlockHitResult hit = Item.getPlayerPOVHitResult(player.level(), player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK)
            return null;
        return at(player.level(), hit.getBlockPos(), type);
    }
}
