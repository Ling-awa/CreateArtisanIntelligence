package io.github.Ling.create_ai;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;

import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The brass powered saw's mode selector: Create's own scroll-option behaviour on a value box of the
 * same shape Create puts on its own multi-mode machines, so picking a mode is the identical
 * long-press-then-drag gesture.
 *
 * <p>Modelled on the mechanical roller's mode box ({@code RollerBlockEntity.RollerValueBox}) and the
 * mechanical arm's ({@code ArmBlockEntity.SelectionModeValueBox}): a plain {@link ValueBoxTransform}
 * whose {@code rotate} lays the box flat on the face it belongs to and turns it with the block, and
 * whose {@code testHit} uses {@code scale / 3}. Nothing here draws a GUI or reads input —
 * {@code ScrollOptionBehaviour} supplies the board, {@link BrassMechanicalSawMode} supplies the two
 * entries and their icons, and Create's own value-settings input handler notices the long press.
 *
 * <p>The one thing this adds is the callback: the selected mode is written into the blockstate, so the
 * contraption actor and a player's engineer's goggles can read the mode without touching the block
 * entity.
 */
public class BrassMechanicalSawModeBehaviour extends ScrollOptionBehaviour<BrassMechanicalSawMode> {

    /** The heading shown at the top of the mode board. */
    private static final String MODE_LABEL_KEY = "create_ai.brass_mechanical_saw.mode";

    public BrassMechanicalSawModeBehaviour(SmartBlockEntity be) {
        // The board's title is its own string, not the first mode's name. Passing the mode name here is
        // what made the selector read "Fast Tree Felling" instead of "Cutting Mode": this label is the
        // board heading only, while the rows inside come from BrassMechanicalSawMode.
        super(BrassMechanicalSawMode.class, Component.translatable(MODE_LABEL_KEY), be,
            new BrassMechanicalSawModeSlot());
        withCallback(this::onModeChanged);
    }

    /**
     * Writes the new mode into the blockstate and pushes it to clients.
     *
     * <p>{@code setValue} has already flagged the block entity as changed and sent its own data by the
     * time this runs, but a blockstate change is a separate sync: without this the client would keep the
     * old property until the chunk is resent, and a contraption assembling in that window would capture
     * the wrong mode.
     */
    private void onModeChanged(int value) {
        if (getWorld() == null || getWorld().isClientSide)
            return;
        BlockState state = blockEntity.getBlockState();
        if (!state.hasProperty(BrassMechanicalSawBlock.MODE))
            return;
        int mode = BrassMechanicalSawMode.byIndex(value)
            .ordinal();
        if (state.getValue(BrassMechanicalSawBlock.MODE) == mode)
            return;
        getWorld().setBlock(getPos(), state.setValue(BrassMechanicalSawBlock.MODE, mode), 3);
    }

    /**
     * Where the mode box sits, which face depends on how the saw is lying.
     *
     * <p><b>Horizontal blade</b> (facing a compass direction): the top face, in the middle. That is the
     * orientation a saw fells trees in, and the face a player naturally looks at.
     *
     * <p><b>Vertical blade</b> (facing up or down): the two side faces that have no shaft in them. A
     * vertical saw's rotation axis runs along X or Z, and {@code hasShaftTowards} is
     * {@code face.getAxis() == rotationAxis}, so the shaft is in those two faces and the other two
     * horizontal directions are free — those get the box. The top and bottom faces are never used: facing
     * up, the top face carries Create's recipe filter, and facing down there is nothing to point at.
     *
     * <p>{@link Sided} is what makes a single box serve two faces: Create's value box renderer calls
     * {@code fromSide} with whatever face the crosshair is on before drawing, and the input handler does
     * the same before deciding a press belongs to this box.
     */
    private static class BrassMechanicalSawModeSlot extends ValueBoxTransform.Sided {

        @Override
        protected boolean isSideActive(BlockState state, Direction side) {
            Direction facing = state.getValue(BrassMechanicalSawBlock.FACING);
            if (facing.getAxis() != Axis.Y) {
                // Horizontal saw: only the top face, so the box does not float on its sides.
                return side == Direction.UP;
            }
            // Vertical saw: the horizontal faces the shaft does not occupy.
            if (side.getAxis() == Axis.Y)
                return false;
            return side.getAxis() != ((IRotate) state.getBlock()).getRotationAxis(state);
        }

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 15.5);
        }

        @Override
        public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
            Direction facing = state.getValue(BrassMechanicalSawBlock.FACING);
            if (facing.getAxis() == Axis.Y) {
                // Vertical saw: the side location, turned to face the side being pointed at.
                return VecHelper.rotateCentered(getSouthLocation(), AngleHelper.horizontalAngle(getSide()),
                    Axis.Y);
            }
            // Horizontal saw: middle of the top face, nudged towards the blade the way the roller and the
            // arm nudge theirs, so the box clears the casing rather than sitting inside it.
            return VecHelper.rotateCentered(VecHelper.voxelSpace(8, 15.5f, 8),
                AngleHelper.horizontalAngle(facing) + 180, Axis.Y);
        }

        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
            Direction facing = state.getValue(BrassMechanicalSawBlock.FACING);
            if (facing.getAxis() == Axis.Y) {
                // Vertical saw: Sided.rotate already turns the box to lie flat on getSide(), which is
                // exactly what a side box needs.
                super.rotate(level, pos, state, ms);
                return;
            }
            TransformStack.of(ms)
                .rotateYDegrees(AngleHelper.horizontalAngle(facing) + 180)
                .rotateXDegrees(90);
        }
    }
}
