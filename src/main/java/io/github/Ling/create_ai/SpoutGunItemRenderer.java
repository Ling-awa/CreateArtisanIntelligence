package io.github.Ling.create_ai;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The spout gun's moving parts: the fluid in its tank, and the cog on its side.
 *
 * <p>The cog is the potato cannon's own renderer, line for line, with the gun's model in place of the
 * cannon's:
 *
 * <ul>
 * <li>the item's baked model first — that is the gun body, from {@code models/item/spout_gun.json};</li>
 * <li>then a {@link PartialModel} for the cog, a second model file that nothing but this renderer
 *     refers to ({@code models/item/spout_gun/cog.json}), whose elements sit exactly where the
 *     cannon's cog elements sit in its own model;</li>
 * <li>rotated about Z around the point halfway up the gun (the {@code .5f / 16} offset lifts the
 *     rotation onto the cog's own axis), by the angle {@link SpoutGunSpin} is holding.</li>
 * </ul>
 *
 * <p>The one deliberate difference from the cannon is where that angle comes from. The cannon adds a
 * decaying recoil term to a fixed idle turn: fine for a shot, useless for a hold, because a constant
 * added to an angle and then taken modulo 360 is no rotation at all. The gun instead holds a faster
 * <em>rate</em> for as long as the trigger is down, and the angle accumulates — see
 * {@link SpoutGunSpin} for the reasoning.
 *
 * <p>Between the two sits the tank window: the fluid the gun is carrying, drawn as a box where the
 * model's housing is, using that fluid's own still texture. It is not a texture file and could not be
 * one — which fluid is in the gun is only known at runtime, and any mod may add more — so the six faces
 * are built here from the fluid's sprite, sampling the middle of it and tinting it with the fluid's own
 * color. An empty gun draws nothing at all, so the window is simply see-through.
 *
 * <p>Client-only: it is only ever built from {@code Create_ai.ClientModEvents#registerClientExtensions},
 * which NeoForge calls while the client is starting up — early enough that the partial model above is
 * baked along with the rest.
 */
public class SpoutGunItemRenderer extends CustomRenderedItemModelRenderer {

    /** The cog, as a model of its own — the second file the artist shipped next to the gun's body. */
    protected static final PartialModel COG =
        PartialModel.of(ResourceLocation.fromNamespaceAndPath("create_ai", "item/spout_gun/cog"));

    // --- the tank window -------------------------------------------------------------------------

    /**
     * The tank's box, in the item model's own coordinates: the artist's 0..16 grid, y upwards, and the
     * very box the model's housing occupies — {@code from [-2, -1.5, -6] to [2, 2.5, -2]}. Note that there is a
     * coordinate offset. The actual coordinates are different from the model coordinates, with an offset of (8, 8, 8).
     * When defining coordinates, you need to subtract this offset.
     */
    private static final float TANK_MIN_X = -2f;
    private static final float TANK_MIN_Y = -1.5f;
    private static final float TANK_MIN_Z = -6f;
    private static final float TANK_MAX_X = 2f;
    private static final float TANK_MAX_Y = 2.5f;
    private static final float TANK_MAX_Z = -2f;

    /**
     * How far each corner is pulled towards the middle, in model units: two of these off every edge,
     * which keeps the fluid just inside the housing's own faces instead of fighting them for the same
     * pixels.
     */
    private static final float TANK_INSET = 0.1f;

    /** How much of the fluid's still texture the window shows: this many pixels, taken from the middle. */
    private static final float WINDOW_SAMPLE_PIXELS = 6f;

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
                          ItemDisplayContext transformType, PoseStack ms, MultiBufferSource buffer, int light,
                          int overlay) {
        // The fluid goes first, and the order is not cosmetic. Translucent render types write depth even
        // where a texture is fully transparent, so a housing drawn first would leave its window's pixels
        // in the depth buffer and hide the fluid behind them. Drawn the other way round, the housing's
        // opaque pixels still cover the fluid, and its transparent ones let it through.
        renderFluidWindow(stack, ms, buffer, light, overlay);
        renderer.render(model.getOriginalModel(), light);

        float angle = SpoutGunSpin.angle(AnimationTickHolder.getPartialTicks()) % 360;
        float offset = .5f / 16;

        ms.pushPose();
        ms.translate(0, offset, 0);
        ms.mulPose(Axis.ZP.rotationDegrees(angle));
        ms.translate(0, -offset, 0);
        renderer.render(COG.get(), light);
        ms.popPose();
    }

    /**
     * Draws whatever the gun is carrying, in the fluid's own colors. Nothing is drawn for an empty
     * tank — there is no "empty" texture, the window is simply transparent.
     */
    private static void renderFluidWindow(ItemStack stack, PoseStack ms, MultiBufferSource buffer, int light,
                                          int overlay) {
        FluidStack fluid = SpoutGunItem.getFluid(stack);
        if (fluid.isEmpty())
            return;

        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid.getFluidType());
        ResourceLocation still = extensions.getStillTexture(fluid);
        if (still == null)
            return;

        // Fluid textures are stitched into the block atlas — the same atlas Create's own fluid renderer
        // reads them from.
        TextureAtlasSprite sprite = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(still);
        if (sprite == null)
            return;

        // The still texture of an animated fluid is a vertical strip of frames, so the smaller of the
        // two dimensions is one frame's edge; fluid frames are square, which is what lets one fraction
        // serve for both axes here.
        int frame = Math.max(1, Math.min(sprite.contents()
            .width(), sprite.contents()
                .height()));
        float span = Math.clamp(WINDOW_SAMPLE_PIXELS, 1f, frame) / frame;
        float low = (1 - span) / 2f;
        float high = low + span;
        float u0 = sprite.getU(low);
        float u1 = sprite.getU(high);
        float v0 = sprite.getV(low);
        float v1 = sprite.getV(high);

        // A fluid's tint, made opaque: a window is not the place to show a fluid's alpha, and some
        // fluids carry a deliberately translucent tint.
        int argb = extensions.getTintColor(fluid) | 0xFF000000;

        // What Create's fluid renderer does with a fluid's own glow: lava lights up what holds it.
        int luminosity = Math.max((light >> 4) & 0xF, fluid.getFluidType()
            .getLightLevel(fluid));
        int packedLight = (light & 0xF00000) | luminosity << 4;

        float x0 = (TANK_MIN_X + TANK_INSET) / 16f;
        float y0 = (TANK_MIN_Y + TANK_INSET) / 16f;
        float z0 = (TANK_MIN_Z + TANK_INSET) / 16f;
        float x1 = (TANK_MAX_X - TANK_INSET) / 16f;
        float y1 = (TANK_MAX_Y - TANK_INSET) / 16f;
        float z1 = (TANK_MAX_Z - TANK_INSET) / 16f;

        // An entity render type over the block atlas: no culling, so the box cannot end up inside-out,
        // and it carries the overlay and light a custom vertex needs. It is also what makes the same
        // code work wherever the item is drawn — in hand, on the ground, or in the inventory.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));

        // Corners in the counter-clockwise order seen from outside, so the per-face normals shade the
        // way the real thing would.
        quad(vc, ms, argb, packedLight, overlay, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, u0, u1, v0, v1);
        quad(vc, ms, argb, packedLight, overlay, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, u0, u1, v0, v1);
        quad(vc, ms, argb, packedLight, overlay, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, u0, u1, v0, v1);
        quad(vc, ms, argb, packedLight, overlay, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u0, u1, v0, v1);
        quad(vc, ms, argb, packedLight, overlay, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, u0, u1, v0, v1);
        quad(vc, ms, argb, packedLight, overlay, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, u0, u1, v0, v1);
    }

    /** One face of the window, from four corners given in order — see the call sites. */
    private static void quad(VertexConsumer vc, PoseStack ms, int argb, int light, int overlay, float nx, float ny,
                             float nz, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy,
                             float cz, float dx, float dy, float dz, float u0, float u1, float v0, float v1) {
        vertex(vc, ms, argb, light, overlay, nx, ny, nz, ax, ay, az, u0, v1);
        vertex(vc, ms, argb, light, overlay, nx, ny, nz, bx, by, bz, u1, v1);
        vertex(vc, ms, argb, light, overlay, nx, ny, nz, cx, cy, cz, u1, v0);
        vertex(vc, ms, argb, light, overlay, nx, ny, nz, dx, dy, dz, u0, v0);
    }

    private static void vertex(VertexConsumer vc, PoseStack ms, int argb, int light, int overlay, float nx, float ny,
                               float nz, float x, float y, float z, float u, float v) {
        vc.addVertex(ms.last(), x, y, z)
            .setColor(argb)
            .setUv(u, v)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(ms.last(), nx, ny, nz);
    }
}
