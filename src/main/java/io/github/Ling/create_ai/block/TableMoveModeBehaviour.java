package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.table.TableMoveMode;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The workbench's movement-mode button: Create's own scroll-option behaviour on the table's four sides.
 *
 * <p>Nothing here draws a button or reads input. {@code ScrollOptionBehaviour} is the thing that makes a
 * value box that a player long-presses with a wrench, drags, and confirms; {@link TableMoveMode} supplies the
 * two entries and their icons; and Create's own value-settings input handler is the one that notices the
 * press, which is why nothing in this mod has to know that the widget exists (see
 * {@code ValueSettingsInputHandler}: it walks a block entity's behaviours looking for exactly this interface).
 *
 * <p>The one thing this decides is <em>where</em> the box is: the four horizontal faces and never the top.
 * The top face is where the layout is laid out and where a wrench assembles it, so a button there would be
 * taken for a mis-click; on the sides it is out of the way of everything the table does, and a player standing
 * at any edge has a face in front of them.
 *
 * <p>{@link ValueBoxTransform.Sided} is what makes a single box serve four faces: Create's value box renderer
 * calls {@code fromSide} with whatever face the crosshair is on before drawing it, and the input handler does
 * the same before deciding that a press belongs to this box.
 */
public class TableMoveModeBehaviour extends ScrollOptionBehaviour<TableMoveMode> {

    public TableMoveModeBehaviour(SmartBlockEntity be) {
        // The board's heading is its own string rather than the first mode's name, so the panel reads
        // "Movement Mode" and the rows inside it read "Normal" and "Reversed".
        super(TableMoveMode.class, Component.translatable("create_ai.processing_table.move_mode"), be,
            new TableMoveModeSlot());

        // The button is drawn and pressed only while a wrench is held. A table's four sides are also where
        // items are put down and taken off, and a widget that claimed those clicks - which is what a value box
        // without this does, whatever the player is holding - would be worse than no widget at all.
        requiresWrench();
    }

    /** The button's home: the middle of each side face, an eighth of a block above the table's base. */
    private static class TableMoveModeSlot extends ValueBoxTransform.Sided {

        @Override
        protected boolean isSideActive(BlockState state, Direction side) {
            return side.getAxis() != Axis.Y;
        }

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 15.5);
        }
    }
}
