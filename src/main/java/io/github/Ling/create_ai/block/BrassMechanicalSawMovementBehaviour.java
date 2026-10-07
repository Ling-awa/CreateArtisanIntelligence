package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.client.BrassMechanicalSawActorVisual;
import io.github.Ling.create_ai.client.BrassMechanicalSawRenderer;
import io.github.Ling.create_ai.Create_ai;


import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.kinetics.saw.SawMovementBehaviour;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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

    /** How much harder a brass saw bites than a mechanical one, for both damage paths. */
    private static final double DAMAGE_MULTIPLIER = 2.0;

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
        if (isPrecision(context))
            return super.getBlockBreakingSpeed(context);
        // Create's figure, doubled and re-clamped to Create's own ceiling: doubling must not lift the
        // limit Create places on how fast any saw may chew through a block.
        return Mth.clamp(super.getBlockBreakingSpeed(context) * 2f, 0, BREAK_SPEED_CEILING);
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

    // --- deviation 2: twice the damage to entities ------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Create's damage figure, doubled. The blade hurts whatever it sweeps through by
     * {@code clamp(6 * |relativeMotion|^0.4 + 1, 2, 10)}, so the doubling goes on the figure that feeds
     * the clamp, not on the result: clamping first and doubling after would be undone by the ceiling of
     * 10 on anything moving fast enough to reach it. Here the doubled value simply reaches that ceiling
     * sooner — a brass saw cuts a walking player down at a speed a mechanical saw needs far more motion
     * for, and the hardest possible hit stays Create's hardest possible hit.
     */
    @Override
    public void damageEntities(MovementContext context, BlockPos pos, Level world) {
        if (context.contraption.entity instanceof OrientedContraptionEntity oce && oce.nonDamageTicks > 0)
            return;
        DamageSource damageSource = getDamageSource(world);
        if (damageSource == null && !throwsEntities(world))
            return;
        Entities: for (Entity entity : world.getEntitiesOfClass(Entity.class, new AABB(pos))) {
            if (entity instanceof ItemEntity)
                continue;
            if (entity instanceof AbstractContraptionEntity)
                continue;
            if (entity.isPassengerOfSameVehicle(context.contraption.entity))
                continue;
            if (entity instanceof AbstractMinecart)
                for (Entity passenger : entity.getIndirectPassengers())
                    if (passenger instanceof AbstractContraptionEntity
                        && ((AbstractContraptionEntity) passenger).getContraption() == context.contraption)
                        continue Entities;

            if (damageSource != null && !world.isClientSide) {
                double base = 6 * Math.pow(context.relativeMotion.length(), 0.4) + 1;
                entity.hurt(damageSource, (float) Mth.clamp(base * DAMAGE_MULTIPLIER, 2, 10));
            }
            if (throwsEntities(world) && (world.isClientSide == (entity instanceof Player)))
                throwEntity(context, entity);
        }
    }

    /** Registers the two behaviours on our block. Called once, from common setup. */
    public static void register() {
        MovementBehaviour.REGISTRY.register(Create_ai.BRASS_MECHANICAL_SAW.get(), new BrassMechanicalSawMovementBehaviour());
    }
}
