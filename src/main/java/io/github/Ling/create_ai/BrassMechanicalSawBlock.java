package io.github.Ling.create_ai;

import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The brass powered saw: Create's mechanical saw, with a mode selector and two knobs turned.
 *
 * <p>The block itself is Create's, at the code level rather than copied. Extending {@link SawBlock}
 * means the collision and outline shape, the {@code facing} / {@code axis_along_first_coordinate} /
 * {@code flipped} states, the wrench behaviour, the damage dealt to entities touching a spinning
 * blade, the item pickup when a dropped item lands on the saw, the hand interaction that empties the
 * processing inventory, path-finding, the placement helper and the rotation axis are all inherited
 * unchanged — and its blockstate points straight at {@code create:block/mechanical_saw/*}, so model
 * and textures are literally Create's saw.
 *
 * <p>This class adds exactly one thing: {@link #MODE}, the state the mode selector writes. It exists
 * so the mode survives assembly into a contraption — a contraption captures blockstates, not block
 * entities, so a mode that lived only in the block entity would be lost the moment the saw moved.
 *
 * <p>The behaviour deviations live in {@link BrassMechanicalSawBlockEntity} (processing speed and stress)
 * and {@link BrassMechanicalSawMovementBehaviour} (breaking speed and precision cutting).
 */
public class BrassMechanicalSawBlock extends SawBlock {

    /** Index into {@link BrassMechanicalSawMode}. {@code 0} is the default, fast tree felling. */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, BrassMechanicalSawMode.values().length - 1);

    public BrassMechanicalSawBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(MODE, BrassMechanicalSawMode.FAST_TREE_FELLING.ordinal()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(MODE));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link SawBlockEntity#getBlockEntityClass()} returns {@link SawBlockEntity} for every subclass, which
     * is what keeps the inherited {@code onBlockEntityUseItemOn} lookups working; only the registered
     * type has to be ours, so that this block gets our block entity rather than Create's.
     */
    @Override
    public BlockEntityType<? extends SawBlockEntity> getBlockEntityType() {
        return Create_ai.BRASS_MECHANICAL_SAW_BE.get();
    }
}
