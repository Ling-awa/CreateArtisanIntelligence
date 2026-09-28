package io.github.Ling.create_ai;


import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.kinetics.saw.SawMovementBehaviour;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * What a brass powered saw does when it is bolted onto a contraption.
 *
 * <p>Everything structural is Create's {@link SawMovementBehaviour}, unchanged: which blocks are
 * sawable, that a tree is felled as a whole rather than one block at a time, that a stall while a
 * trunk comes down is set and cleared the same way, that entities in the blade's path are hurt with
 * Create's saw damage, that falling blocks are broken top-down, and that drops go into the
 * contraption's storage before ever hitting the ground. "Same as the mechanical saw" is not
 * approximated here — it is the inherited code.
 *
 * <p>Two things differ, and only two:
 * <ul>
 * <li><b>Breaking speed.</b> {@link #getBlockBreakingSpeed} doubles Create's result — the same
 *     {@code |animation speed| / 500} figure, doubled, then clamped back to the same ceiling of 16 so
 *     a fast contraption still cannot outrun a mechanical saw's maximum. The lower bounds are left
 *     alone, so a mounted or carriage-borne saw keeps its own floor. In precision mode nothing is
 *     doubled, because that mode trades speed for quality.</li>
 * <li><b>Precision mode.</b> Trees are cut as though by a Silk Touch tool, so each block in a trunk
 *     and canopy drops as itself — leaves come off as leaf blocks instead of saplings and sticks.</li>
 * </ul>
 */
public class BrassMechanicalSawMovementBehaviour extends SawMovementBehaviour {

    /** Create clamps its breaking speed to 16; doubling must not lift that ceiling. */
    private static final float BREAK_SPEED_CEILING = 16f;

    /**
     * Whether the mode saved with this contraption's blockstate is precision.
     *
     * <p>The blockstate, not a block entity: a contraption actor has no block entity, so the state is the
     * only copy that survives assembly. {@link BrassSawPrecisionCutting#isPrecision} holds the rule, so
     * the placed saw and the mounted one read the mode the same way.
     */
    private static boolean isPrecision(MovementContext context) {
        return BrassSawPrecisionCutting.isPrecision(context.state);
    }

    // --- deviation 1: twice the breaking speed, in fast mode only ---------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Create's figure, doubled and re-clamped to Create's own ceiling. Applied only in fast mode:
     * precision mode cuts at exactly the speed a mechanical saw would, which is the trade the mode
     * exists to offer.
     */
    @Override
    protected float getBlockBreakingSpeed(MovementContext context) {
        float speed = super.getBlockBreakingSpeed(context);
        if (isPrecision(context))
            return speed;
        return Mth.clamp(speed * 2f, 0, BREAK_SPEED_CEILING);
    }

    // --- deviation 2: precision cutting -----------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>In precision mode the single block the blade stopped on also drops as though cut by Silk
     * Touch, rather than through Create's ordinary destroy path.
     */
    @Override
    protected void destroyBlock(MovementContext context, BlockPos breakingPos) {
        if (!isPrecision(context)) {
            super.destroyBlock(context, breakingPos);
            return;
        }
        BrassSawPrecisionCutting.destroyOneWithSilkTouch(context.world, breakingPos,
            stack -> collectOrDropItem(context, stack));
    }

    /**
     * {@inheritDoc}
     *
     * <p>In precision mode the tree comes down with a silk-touched tool, so leaves drop as leaf blocks.
     * In fast mode this is Create's own behaviour, untouched.
     */
    @Override
    protected void onBlockBroken(MovementContext context, BlockPos pos, BlockState brokenState) {
        if (!isPrecision(context)) {
            super.onBlockBroken(context, pos, brokenState);
            return;
        }
        BrassSawPrecisionCutting.fellWithSilkTouch(context.world, pos, brokenState,
            (dropPos, stack) -> dropItemFromCutTree(context, dropPos, stack));
    }

    // --- rendering the actor ----------------------------------------------------------------------

    /**
     * The spinning shaft, drawn by Flywheel inside the contraption.
     *
     * <p>Client-only and marked as such: {@code SawActorVisual} reaches into renderer classes that a
     * dedicated server does not have. Create's own saw movement behaviour carries the same annotation
     * on the same methods.
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public @Nullable ActorVisual createVisual(VisualizationContext visualizationContext,
                                              VirtualRenderWorld simulationWorld, MovementContext movementContext) {
        return new BrassMechanicalSawActorVisual(visualizationContext, simulationWorld, movementContext);
    }

    /** The blade and the items riding on it, drawn from this mod's blade models. */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
                                    ContraptionMatrices matrices, MultiBufferSource buffer) {
        BrassMechanicalSawRenderer.renderInContraption(context, renderWorld, matrices, buffer);
    }

    /** Registers the two behaviours on our block. Called once, from common setup. */
    public static void register() {
        MovementBehaviour.REGISTRY.register(Create_ai.BRASS_MECHANICAL_SAW.get(), new BrassMechanicalSawMovementBehaviour());
    }
}
