package io.github.Ling.create_ai.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The handheld mechanical saw's moving part: the cog in its side, turning about the tool's own X axis.
 *
 * <p>This is the handheld fan's renderer with the saw's models in place of the fan's, which is itself the
 * spout gun's, which is the potato cannon's:
 *
 * <ul>
 * <li>the item's baked model first — that is the saw's body, the artist's Blockbench export, reached
 *     through {@code models/item/handheld_mechanical_saw.json};</li>
 * <li>then a {@link PartialModel} for the cog, a second model file that nothing but this renderer refers
 *     to ({@code models/item/handheld_mechanical_saw/cog.json}), whose boxes the artist laid out around
 *     their own origin {@code [8, 10, 7]}, the teeth turned about X by forty-five degrees each;</li>
 * <li>rotated about X — the axis that cog's teeth are laid out around, and the one place the saw differs
 *     from the other two tools, which turn their cogs about {@link Axis#ZP} — about that same
 *     {@code [8, 10, 7]}.</li>
 * </ul>
 *
 * <p>The pivot is written the way every renderer here writes one: the pose stack an item model is drawn in
 * is centred on the middle of the artist's 0..16 grid, {@code [8, 8, 8]}, so {@code [8, 10, 7]} has to be
 * given as the pivot <em>less that centre</em> — nothing sideways, two pixels up, one pixel back — in model
 * pixels, divided by sixteen at the call site because one pixel is a sixteenth of a block. The fan's cog
 * happens to share the same origin, so these are the same three numbers; a bare {@code 2} instead of
 * {@code 2 / 16} would lift the cog two blocks clear of the tool.
 *
 * <p>The angle comes from {@link SpoutGunSpin#SAW_SPIN} — the same {@link ToolCogSpin} rate the other two
 * cogs turn by: a slow drift while the saw is merely held, and a turn per second for as long as the use key
 * is down, which for this tool is the winding-up it does before every stroke.
 *
 * <p>Two things are deliberately not drawn here. The blade does not turn in the model: it is a flat element
 * of the body, textured with vanilla's animated stonecutter blade, so what moves on the blade is the
 * texture. And the tool's filter slot is not shown — nothing names it, and the cup in the body model is
 * where an installed filter would be drawn if it is wanted there later.
 *
 * <p>Client-only: it is only ever built from {@code Create_ai.ClientModEvents#registerClientExtensions},
 * which NeoForge calls while the client is starting up — early enough that the partial model above is baked
 * along with the rest.
 */
public class SawCogSpinItemRenderer extends CustomRenderedItemModelRenderer {

    /**
     * Forces this class to initialise, and therefore creates {@link #COG}, before Flywheel's
     * {@code ModelEvent.RegisterAdditional} asks for it. Called from client setup; the call itself does
     * nothing — the static initializer below is the point, exactly as {@code FanCogSpinItemRenderer} does it.
     */
    public static void register() {
    }

    /** The cog, as a model of its own — the second file the artist shipped next to the saw's body. */
    protected static final PartialModel COG =
        PartialModel.of(ResourceLocation.fromNamespaceAndPath("create_ai", "item/handheld_mechanical_saw/cog"));

    /**
     * The cog's pivot, as an offset from the point the pose stack is centred on — the artist's own
     * {@code [8, 10, 7]} less the model centre {@code [8, 8, 8]}, in model pixels: nothing sideways, two
     * pixels up, one pixel back. See the class javadoc for where the rule comes from and for why these are
     * divided by sixteen at the call site rather than translated as they stand.
     */
    private static final float PIVOT_X = 0f;
    private static final float PIVOT_Y = 2f;
    private static final float PIVOT_Z = -1f;

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
                          ItemDisplayContext transformType, PoseStack ms, MultiBufferSource buffer, int light,
                          int overlay) {
        // Cutout, like the fan's body and cog: nothing on this item is meant to be semi-transparent, and
        // blending costs a depth-sorted pass and lets the two solid pieces show through each other. The
        // plain render(model, light) overload draws with Sheets.translucentCullBlockSheet(); the one that
        // takes a RenderType is Create's own, so this is the supported way to ask for another one.
        renderer.render(model.getOriginalModel(), Sheets.cutoutBlockSheet(), light);

        float angle = SpoutGunSpin.SAW_SPIN.angle(AnimationTickHolder.getPartialTicks()) % 360;

        // A turn about a line through the pivot, which is the whole of what a pivot means here: bring the
        // pivot to the origin, turn, put it back. T(p) . R . T(-p) leaves every point of that axis where it
        // is and swings everything else around it.
        ms.pushPose();
        ms.translate(PIVOT_X / 16, PIVOT_Y / 16, PIVOT_Z / 16);
        ms.mulPose(Axis.XP.rotationDegrees(angle));
        ms.translate(-PIVOT_X / 16, -PIVOT_Y / 16, -PIVOT_Z / 16);
        renderer.render(COG.get(), Sheets.cutoutBlockSheet(), light);
        ms.popPose();
    }
}
