package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.CreateAI;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The rod as it is used: standing in the basin, head down, turning — {@link #TURNS_PER_OPERATION} whole
 * turns per mixing operation, and nothing else. It does not sink, dip or bob: it is put where it stirs and
 * turns there, which is what a hand stirring a bowl does with the wrist and not with the arm.
 *
 * <p>{@link StirringRodStir} says who is stirring what this tick; this class draws it. Drawing is done at
 * the end of the block entities of the level, when the pose stack is already in world space relative to the
 * camera, which is why every position here is written as the block's own less the camera's. What is drawn
 * is the item's own model, through the item renderer, so whatever the artist puts in
 * {@code models/item/stirring_rod.json} — a sprite today, a model tomorrow — is what turns in the basin, at
 * the size and shape it is drawn at in the hand.
 *
 * <p><b>The pose.</b> The model is drawn with no display transform of its own
 * ({@link ItemDisplayContext#NONE}), and the pose is built from three turns about the rod's own middle,
 * written innermost first — which is why they are written in the reverse order here:
 *
 * <ul>
 * <li>{@link #HEAD_DOWN_DEGREES} about Z, in the sprite's own plane: the artist drew the rod lying
 *     diagonally with its head at the sprite's top right corner (see {@link #HEAD_DOWN_DEGREES}), and this
 *     is the quarter-turn-and-a-bit that stands that diagonal upright, head at the bottom;</li>
 * <li>{@link Config#stirTiltDegrees()} about X: a rod standing straight up in a basin stirs nothing, so it
 *     leans — and leaning it about its own middle is what makes the head swing in a circle once the last
 *     turn comes, a circle as wide as the sine of that angle, which is the size of a stir in a basin;</li>
 * <li>the circle itself, about Y, at {@link Config#stirTurnsPerOperation()} whole turns per mixing
 *     operation — so the rod turns with the basin's own rhythm, and a longer recipe or a lower stirring
 *     speed from the config turns it more slowly, while a basin working faster turns it faster.</li>
 * </ul>
 *
 * <p><b>Height.</b> The rod's middle sits {@link #STIR_CENTER_Y} of a block above the basin's floor, fixed:
 * that puts its head in the bowl and its handle out of the top, and it is where the rod stays for as long
 * as the stir lasts.
 *
 * <p>A flat sprite seen edge-on is a line, so twice per revolution this rod thins to nothing for a moment.
 * That is what drawing the item's own sprite buys and costs; a second copy crossed behind it would fix it,
 * at the price of a basin that looks like it is being stirred by two rods.
 *
 * <p>Client-only, and on the client bus by way of {@code value = Dist.CLIENT}: the event is fired on the
 * client alone, and this class is never named by a class the server loads.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class StirringRodStirRenderer {

    /** How far above the basin block's own bottom the rod's middle sits: head in the bowl, handle out of the top. */
    private static final double STIR_CENTER_Y = 0.7D;

    /**
     * The turn about Z that stands the artist's diagonal upright with the head at the bottom.
     *
     * <p>The sprite in {@code textures/item/stirring_rod.png} runs from the bottom left corner to the top
     * right, and it is the top right end that is the head: it is five pixels wide there, against three
     * along the shaft. That is a head along {@code (+x, +y)}, and turning the model by minus a hundred and
     * thirty-five degrees is what sends it to {@code (0, -1)} — straight down. If the sprite is ever
     * redrawn mirrored, with the head at the top left instead, this number flips to plus a hundred and
     * thirty-five.
     */
    private static final float HEAD_DOWN_DEGREES = -135f;

    /**
     * The rod, made on first use.
     *
     * <p>Deliberately not a static initializer. This class carries {@code @EventBusSubscriber}, so FML
     * loads it while the mod is still being constructed — before any of this mod's items has been bound to
     * the registry — and asking a {@code DeferredItem} for its value at that moment throws "Trying to
     * access unbound value", which fails the whole mod's loading. Everything here that touches the
     * registry is reached from a rendered frame, which is far too late for that to be a question.
     */
    @Nullable
    private static ItemStack rod;

    private StirringRodStirRenderer() {
    }

    /** The stack drawn in the basin: the item's own, made once and never modified. */
    private static ItemStack rod() {
        if (rod == null)
            rod = new ItemStack(CreateAI.STIRRING_ROD.get());
        return rod;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)
            return;

        List<StirringRodStir.Stir> stirs = StirringRodStir.current();
        if (stirs.isEmpty())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null)
            return;

        PoseStack ms = event.getPoseStack();
        Vec3 camera = event.getCamera()
            .getPosition();
        float partialTick = event.getPartialTick()
            .getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers()
            .bufferSource();

        boolean drew = false;
        for (StirringRodStir.Stir stir : stirs) {
            // The bowl itself is one block; a rod leaning out of it stays within one of it.
            if (!event.getFrustum()
                .isVisible(new AABB(stir.basin()).inflate(1.5D)))
                continue;
            draw(level, minecraft.getItemRenderer(), ms, buffer, camera, partialTick, stir);
            drew = true;
        }
        if (drew)
            buffer.endBatch(Sheets.translucentItemSheet());
    }

    private static void draw(ClientLevel level, ItemRenderer itemRenderer, PoseStack ms,
                             MultiBufferSource.BufferSource buffer, Vec3 camera, float partialTick,
                             StirringRodStir.Stir stir) {
        BlockPos basin = stir.basin();
        float elapsed = stir.elapsed() + partialTick;

        // Turns with the basin's own rhythm, and only turns: the height is where the rod stands.
        float interval = stir.delay() + 1f;
        float turn = 360f * Config.stirTurnsPerOperation() * elapsed / interval;

        ms.pushPose();
        ms.translate(basin.getX() + 0.5D - camera.x, basin.getY() + STIR_CENTER_Y - camera.y,
            basin.getZ() + 0.5D - camera.z);
        ms.mulPose(Axis.YP.rotationDegrees(turn));
        ms.mulPose(Axis.XP.rotationDegrees(Config.stirTiltDegrees()));
        ms.mulPose(Axis.ZP.rotationDegrees(HEAD_DOWN_DEGREES));
        itemRenderer.renderStatic(rod(), ItemDisplayContext.NONE, LevelRenderer.getLightColor(level, basin.above()),
            OverlayTexture.NO_OVERLAY, ms, buffer, level, 0);
        ms.popPose();
    }
}
