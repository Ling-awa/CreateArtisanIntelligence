package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.Create_ai;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.content.kinetics.saw.SawBlock;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The saw's placement helper: using a saw on another saw lays the new one down alongside it, in the same
 * plane and at the same facing — so a row of saws can be run along a line without turning to face each
 * direction every time.
 *
 * <p>Create registers this for {@code create:mechanical_saw} through its own Registrate block builder
 * ({@code SawBlock.PlacementHelper}), and that registration names its saw specifically, so a subclass does
 * not inherit it. This is the same helper, for our block.
 *
 * <p>It accepts either saw as the held item and either as the clicked block, so the two machines extend
 * each other's rows interchangeably instead of each only helping itself.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class BrassMechanicalSawPlacementHelper implements IPlacementHelper {

    /**
     * The id {@code PlacementHelpers} hands back. Kept in a field rather than fetched lazily: the helper
     * is registered once, from the static initializer below, and looked up by this id afterwards. Holding
     * it also means the instance is never collected.
     */
    public static final int ID = PlacementHelpers.register(new BrassMechanicalSawPlacementHelper());

    private BrassMechanicalSawPlacementHelper() {
    }

    /** Called once, from common setup. The static initializer above does the actual registration. */
    public static void register() {
    }

    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return stack -> stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof SawBlock;
    }

    @Override
    public Predicate<BlockState> getStatePredicate() {
        return state -> state.getBlock() instanceof SawBlock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Create's own offset logic for its saw, character for character: look for a free neighbouring
     * position around the clicked saw while ignoring the axis the blade turns on — that axis is where the
     * shaft runs, so a new saw cannot go there — and place it with the clicked saw's facing, axis and flip
     * copied over.
     *
     * <p>{@code SawBlock} covers {@link BrassMechanicalSawBlock} by inheritance, so one predicate serves
     * both machines.
     */
    @Override
    public PlacementOffset getOffset(Player player, Level world, BlockState state, BlockPos pos,
                                     BlockHitResult ray) {
        List<Direction> directions = IPlacementHelper.orderedByDistanceExceptAxis(pos, ray.getLocation(),
            state.getValue(SawBlock.FACING)
                .getAxis(),
            dir -> world.getBlockState(pos.relative(dir))
                .canBeReplaced());

        if (directions.isEmpty())
            return PlacementOffset.fail();

        return PlacementOffset.success(pos.relative(directions.getFirst()),
            s -> s.setValue(SawBlock.FACING, state.getValue(SawBlock.FACING))
                .setValue(SawBlock.AXIS_ALONG_FIRST_COORDINATE, state.getValue(SawBlock.AXIS_ALONG_FIRST_COORDINATE))
                .setValue(SawBlock.FLIPPED, state.getValue(SawBlock.FLIPPED)));
    }
}
