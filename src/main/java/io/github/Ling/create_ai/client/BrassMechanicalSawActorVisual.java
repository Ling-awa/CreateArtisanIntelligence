package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.Create_ai;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.saw.SawVisual;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;

/**
 * The brass powered saw's spinning shaft inside a contraption, drawn by Flywheel.
 *
 * <p>Client-only, and the reason it exists: a contraption actor is not a block entity, so
 * {@code SawVisual} — which is built around one — cannot draw it. Create solves this for its own saw
 * with {@code SawActorVisual}, and this is the same thing against our block: the identical shaft
 * partial, the identical rotation axis and offset, taken from {@link SawVisual#shaft} so the two
 * machines cannot drift apart visually.
 */
public class BrassMechanicalSawActorVisual extends ActorVisual {

    private final RotatingInstance shaft;

    public BrassMechanicalSawActorVisual(VisualizationContext visualizationContext, VirtualRenderWorld simulationWorld,
                               MovementContext movementContext) {
        super(visualizationContext, simulationWorld, movementContext);

        var state = movementContext.state;
        var localPos = movementContext.localPos;
        shaft = SawVisual.shaft(instancerProvider, state);

        var axis = KineticBlockEntityVisual.rotationAxis(state);
        shaft.setRotationAxis(axis)
            .setRotationOffset(KineticBlockEntityVisual.rotationOffset(state, axis, localPos))
            .setPosition(localPos)
            .light(localBlockLight(), 0)
            .setChanged();
    }

    @Override
    protected void _delete() {
        shaft.delete();
    }
}
