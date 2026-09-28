package io.github.Ling.create_ai;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the brass powered saw's blade from this mod's own models.
 *
 * <p>The blade is not part of the saw's block model. Create draws it as a separate partial model,
 * selected by spin direction, on top of the casing — which is why its own saw's blockstate points at
 * models that contain a casing and nothing else. {@link SawRenderer} hard-codes
 * {@code AllPartialModels.SAW_BLADE_*}, so inheriting it would render Create's grey steel blade on our
 * brass machine and leave this mod's blade models unused.
 *
 * <p>So this class is Create's renderer with the blade swapped: {@link #renderBlade} overridden for the
 * block entity, and {@link #renderInContraption} reimplemented for a saw mounted on a contraption,
 * because Create's is {@code static} and reaches for its own partials directly. Everything else — the
 * shaft, the items riding on the blade, the recipe filter, the contraption transform — is Create's code
 * called unchanged, and the shaft deliberately stays Create's {@code SHAFT} / {@code SHAFT_HALF}
 * partials: a brass gearbox still turns an ordinary shaft.
 */
@OnlyIn(Dist.CLIENT)
public class BrassMechanicalSawRenderer extends SawRenderer {

    /**
     * The blade partials. These are owned by {@link BrassMechanicalSawBladeModels}, which is initialised
     * from client setup — see that class for why creating them here, in a renderer's static fields,
     * would be too late for Flywheel to bake them.
     */
    public static final PartialModel BLADE_HORIZONTAL_ACTIVE = BrassMechanicalSawBladeModels.BLADE_HORIZONTAL_ACTIVE;
    public static final PartialModel BLADE_HORIZONTAL_INACTIVE = BrassMechanicalSawBladeModels.BLADE_HORIZONTAL_INACTIVE;
    public static final PartialModel BLADE_HORIZONTAL_REVERSED = BrassMechanicalSawBladeModels.BLADE_HORIZONTAL_REVERSED;
    public static final PartialModel BLADE_VERTICAL_ACTIVE = BrassMechanicalSawBladeModels.BLADE_VERTICAL_ACTIVE;
    public static final PartialModel BLADE_VERTICAL_INACTIVE = BrassMechanicalSawBladeModels.BLADE_VERTICAL_INACTIVE;
    public static final PartialModel BLADE_VERTICAL_REVERSED = BrassMechanicalSawBladeModels.BLADE_VERTICAL_REVERSED;

    public BrassMechanicalSawRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same partial-model selection Create makes — active, reversed or inactive, by spin
     * direction and blade orientation — with this mod's partials in place of Create's.
     */
    @Override
    protected void renderBlade(SawBlockEntity be, PoseStack ms, MultiBufferSource buffer, int light) {
        BlockState blockState = be.getBlockState();
        PartialModel partial;
        float speed = be.getSpeed();
        boolean rotate = false;

        if (SawBlock.isHorizontal(blockState)) {
            if (speed > 0) {
                partial = BLADE_HORIZONTAL_ACTIVE;
            } else if (speed < 0) {
                partial = BLADE_HORIZONTAL_REVERSED;
            } else {
                partial = BLADE_HORIZONTAL_INACTIVE;
            }
        } else {
            if (speed > 0) {
                partial = BLADE_VERTICAL_ACTIVE;
            } else if (speed < 0) {
                partial = BLADE_VERTICAL_REVERSED;
            } else {
                partial = BLADE_VERTICAL_INACTIVE;
            }

            if (blockState.getValue(SawBlock.AXIS_ALONG_FIRST_COORDINATE))
                rotate = true;
        }

        SuperByteBuffer superBuffer = CachedBuffers.partialFacing(partial, blockState);
        if (rotate) {
            superBuffer.rotateCentered(AngleHelper.rad(90), Direction.UP);
        }
        superBuffer.color(0xFFFFFF)
            .light(light)
            .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
    }

    /**
     * Create's contraption blade drawing, with this mod's partials.
     *
     * <p>A copy rather than a call, because Create's version is {@code static} and names its partials
     * directly — there is no seam to hook. The transform chain, the facing maths and the light lookup
     * are Create's, character for character.
     */
    public static void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
                                           ContraptionMatrices matrices, MultiBufferSource buffer) {
        BlockState state = context.state;
        Direction facing = state.getValue(SawBlock.FACING);

        Vec3 facingVec = Vec3.atLowerCornerOf(state.getValue(SawBlock.FACING)
            .getNormal());
        facingVec = context.rotation.apply(facingVec);

        Direction closestToFacing = Direction.getNearest(facingVec.x, facingVec.y, facingVec.z);

        boolean horizontal = closestToFacing.getAxis()
            .isHorizontal();
        boolean backwards = VecHelper.isVecPointingTowards(context.relativeMotion, facing.getOpposite());
        boolean moving = context.getAnimationSpeed() != 0;
        boolean shouldAnimate =
            (context.contraption.stalled && horizontal) || (!context.contraption.stalled && !backwards && moving);

        SuperByteBuffer superBuffer;
        if (SawBlock.isHorizontal(state)) {
            superBuffer = shouldAnimate ? CachedBuffers.partial(BLADE_HORIZONTAL_ACTIVE, state)
                : CachedBuffers.partial(BLADE_HORIZONTAL_INACTIVE, state);
        } else {
            superBuffer = shouldAnimate ? CachedBuffers.partial(BLADE_VERTICAL_ACTIVE, state)
                : CachedBuffers.partial(BLADE_VERTICAL_INACTIVE, state);
        }

        superBuffer.transform(matrices.getModel())
            .center()
            .rotateYDegrees(AngleHelper.horizontalAngle(facing))
            .rotateXDegrees(AngleHelper.verticalAngle(facing));

        if (!SawBlock.isHorizontal(state)) {
            superBuffer.rotateZDegrees(state.getValue(SawBlock.AXIS_ALONG_FIRST_COORDINATE) ? 90 : 0);
        }

        superBuffer.uncenter()
            .light(LevelRenderer.getLightColor(renderWorld, context.localPos))
            .useLevelLight(context.world, matrices.getWorld())
            .renderInto(matrices.getViewProjection(), buffer.getBuffer(RenderType.cutoutMipped()));
    }
}
