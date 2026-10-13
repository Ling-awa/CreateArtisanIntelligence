package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.fan.FanCurrent;
import io.github.Ling.create_ai.item.HandheldFanItem;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.data.ModelData;

import org.joml.Vector3f;

/**
 * The handheld fan's moving parts: the cog at its center, and the item installed in its socket.
 *
 * <p>The cog is the spout gun's own renderer with the fan's models in place of the gun's, which is itself the
 * potato cannon's renderer line for line:
 *
 * <ul>
 * <li>the item's baked model first — that is the fan's body, the artist's Blockbench export, reached
 *     through {@code models/item/handheld_encased_fan.json};</li>
 * <li>then a {@link PartialModel} for the cog, a second model file that nothing but this renderer refers
 *     to ({@code models/item/handheld_encased_fan/cog.json}), whose boxes are that artist's four bars
 *     crossed around their own origin {@code [8, 10, 7]}, each of them turned about Z by a further
 *     forty-five degrees;</li>
 * <li>rotated about Z, the axis those bars are laid out around — the same {@link Axis#ZP} the potato
 *     cannon and the spout gun turn their cogs about — about this cog's own pivot, {@code [8, 10, 7]},
 *     which is the origin those bars were turned about in the model.</li>
 * </ul>
 *
 * <p>Which rotation point is which is worth spelling out. The pose stack an item model is drawn in is
 * centred on the middle of the artist's 0..16 grid, {@code [8, 8, 8]} — the spout gun's renderer says so in
 * as many words — so a pivot has to be written as the pivot <em>less that centre</em>. The spout gun's cog
 * pivots at {@code [8, 8.5, 8]} and so its renderer lifts the turn by {@code .5f / 16}: half a pixel up, and
 * nothing sideways. This cog pivots at {@code [8, 10, 7]}, which is two pixels up and one pixel <em>back</em>
 * from the centre — three components, not one. Leaving the sideways pixel out is what made the cog turn
 * about a line beside it instead of through it. All three are written in model pixels and divided by
 * sixteen at the call site, because one pixel is a sixteenth of a block: a bare {@code 2} would be two
 * blocks up, and the cog would swing clear of the fan entirely.
 *
 * <p>Around that cog sits the socket's own item: whatever is installed in the fan, drawn as a small copy of
 * itself floating on the fan's face. It is the one thing drawn here that is not a model file — which item is
 * in the socket is known only at runtime, and any item in the game may be in it — so it is drawn through the
 * item renderer rather than as a {@link PartialModel}. The stack is
 * {@link HandheldFanItem#installed(ItemStack)}'s, which is the item and nothing else: a socket holds an item,
 * not a copy of one with its own components.
 *
 * <p>The socket is placed the same way the cog's pivot is, because the rule is the same rule. The artist put
 * it at {@code [6, 8, -1.75]} on their 0..16 grid, and the pose stack is centered on that grid's middle,
 * {@code [8, 8, 8]}, so the point has to be written as the artist's less the center — two pixels left, none
 * up or down, nine and three quarters back — and divided by sixteen at the call site, one pixel being a
 * sixteenth of a block. It is drawn small — the size is {@link Config#socketItemScale()}'s, a quarter of the
 * item model by default, four pixels across rather than the item model's sixteen — so it reads as a fitting
 * on the fan instead of as a second tool, and it is drawn in {@link ItemDisplayContext#NONE}, the one display
 * context that applies no transform of its own: the point above is meant to be the whole of where it goes,
 * not the item's own gui or ground pose. It is centered on that point by its own geometry rather than left
 * about the middle of its model cube: the item's own center of geometry is the point a scaling holds still,
 * so an item then grows and shrinks in place instead of sliding sideways as the scale changes. See
 * {@link #renderSocketItem} for the arithmetic, which is the whole of the difference. It takes the light and
 * the overlay the fan itself was drawn with, so it is shaded as part of the same item wherever the fan is
 * drawn, and it goes between the body and the cog so that the cog still turns in front of it. An empty socket
 * draws nothing.
 *
 * <p>The angle comes from {@link SpoutGunSpin#FAN_SPIN} — the same {@link ToolCogSpin} rate the spout
 * gun's cog turns by, on its own axis: a slow drift while the fan is merely held, and a turn per second
 * for as long as the use key is down.
 *
 * <p>Client-only: it is only ever built from {@code Create_ai.ClientModEvents#registerClientExtensions},
 * which NeoForge calls while the client is starting up — early enough that the partial model above is
 * baked along with the rest.
 */
public class FanCogSpinItemRenderer extends CustomRenderedItemModelRenderer {

    /**
     * Forces this class to initialise, and therefore creates {@link #COG}, before Flywheel's
     * {@code ModelEvent.RegisterAdditional} asks for it. Called from client setup; the call itself does
     * nothing — the static initializer below is the point, exactly as {@code BrassMechanicalSawBladeModels}
     * does it.
     */
    public static void register() {
    }

    /** The cog, as a model of its own — the second file the artist shipped next to the fan's body. */
    protected static final PartialModel COG =
        PartialModel.of(ResourceLocation.fromNamespaceAndPath("create_ai", "item/handheld_encased_fan/cog"));

    /**
     * This cog's pivot, as an offset from the point the pose stack is centred on — the artist's own
     * {@code [8, 10, 7]} less the model centre {@code [8, 8, 8]}, in model pixels: nothing sideways, two
     * pixels up, one pixel back. See the class javadoc for where the rule comes from and for why these are
     * divided by sixteen at the call site rather than translated as they stand.
     */
    private static final float PIVOT_X = 0f;
    private static final float PIVOT_Y = 2f;
    private static final float PIVOT_Z = -1f;

    // --- the socket's item ------------------------------------------------------------------------

    /** The middle of the artist's 0..16 grid, in model pixels: what every offset here is measured from. */
    private static final float MODEL_CENTER = 8f;

    /**
     * Where the artist put the socket, in model pixels — their own number, before any offset, kept beside
     * the offset below so the two can be read against each other.
     */
    private static final float SOCKET_ARTIST_X = 8f;
    private static final float SOCKET_ARTIST_Y = 10f;
    private static final float SOCKET_ARTIST_Z = 0.25f;

    /**
     * Where the socket's item is drawn, as an offset from the model center: the artist's point less
     * {@link #MODEL_CENTER} — two pixels left, nothing up or down, nine and three quarters of a pixel back.
     * See the class javadoc for the rule and for why these are divided by sixteen at the call site, exactly
     * as the cog's pivot is.
     */
    private static final float SOCKET_X = SOCKET_ARTIST_X - MODEL_CENTER;
    private static final float SOCKET_Y = SOCKET_ARTIST_Y - MODEL_CENTER;
    private static final float SOCKET_Z = SOCKET_ARTIST_Z - MODEL_CENTER;

    // --- where an item's own geometry sits inside its model ---------------------------------------

    /**
     * How many ints one vertex of a {@link BakedQuad} is packed into, and where in them the position is:
     * x, y and z are the first three, each a float bit-cast into an int. The format is
     * {@code DefaultVertexFormat.BLOCK}, which is what every model the game bakes is drawn through.
     */
    private static final int VERTEX_INTS = 8;

    /**
     * The random source seed every quad lookup here is made with — the same {@code 42} the item renderer
     * itself uses, so that a model which picks between variants at random hands this code the same quads
     * it is about to draw.
     */
    private static final long QUAD_SEED = 42L;

    /**
     * The center of each baked model's own geometry, in the model space its quads are baked in — the 0..1
     * cube an item model occupies — keyed by that baked model. Measured from the quads the first time a
     * model is drawn and kept from then on, because walking every quad of a model is far too much work to
     * do per frame for a number that only ever changes when the model does.
     *
     * <p>Keyed by the model rather than by the item, so an item whose model depends on the stack is
     * measured once per model, and so that a resource reload needs no hook anywhere: a reload hands out
     * fresh baked models, the old ones become unreachable and the weak keys take their entries with them,
     * and the next frame measures the new model once. Nothing here is ever iterated — only looked up and
     * put — so the synchronized wrapper is all the safety this needs.
     */
    private static final Map<BakedModel, Vector3f> SOCKET_ITEM_CENTERS =
        Collections.synchronizedMap(new WeakHashMap<>());

    // How much of the item's own size is drawn lives in Config#socketItemScale: it is a number judged by
    // eye, and a config entry can be tuned without a rebuild.

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
                          ItemDisplayContext transformType, PoseStack ms, MultiBufferSource buffer, int light,
                          int overlay) {
        // Cutout, not Create's default: the plain render(model, light) overload draws with
        // Sheets.translucentCullBlockSheet(), which blends. Nothing on this item is meant to be
        // semi-transparent, and blending costs a depth-sorted pass and lets the two solid pieces show
        // through each other. The render overload that takes a RenderType is Create's own, so this is the
        // supported way to ask for a different one.
        renderer.render(model.getOriginalModel(), Sheets.cutoutBlockSheet(), light);

        // The socket's item goes after the body and before the cog, so the cog stays the frontmost thing on
        // the fan. Swapping these two calls is the whole of reversing that layering.
        renderSocketItem(stack, ms, buffer, light, overlay);

        float angle = SpoutGunSpin.FAN_SPIN.angle(AnimationTickHolder.getPartialTicks()) % 360;

        ms.pushPose();
        ms.translate(PIVOT_X / 16, PIVOT_Y / 16, PIVOT_Z / 16);
        ms.mulPose(Axis.ZP.rotationDegrees(angle));
        ms.translate(-PIVOT_X / 16, -PIVOT_Y / 16, -PIVOT_Z / 16);
        renderer.render(COG.get(), Sheets.cutoutBlockSheet(), light);
        ms.popPose();
    }

    /**
     * Draws the item installed in the fan's socket, if there is one: at the artist's own point for the
     * socket, at the size {@link Config#socketItemScale()} asks for, centered on that point by the item's own
     * geometry, and with no display transform of the item's own. See the class javadoc for the placement and
     * for why the context is {@link ItemDisplayContext#NONE}, and
     * {@link HandheldFanItem#installed(ItemStack)} for the stack itself.
     *
     * <p>The model is fetched per frame rather than kept: which item is in the socket changes whenever the
     * player puts one in, and the item renderer's own lookup and overrides are what know how any item in the
     * game draws itself. What <em>is</em> kept is the center of that model's geometry, measured once — see
     * {@link #SOCKET_ITEM_CENTERS}.
     *
     * <p>The last of the three translates below is what centers the item, so here is the arithmetic behind
     * it. Write {@code p} for the artist's socket point, {@code s} for the scale, {@code c} for that
     * translate, and {@code g} and {@code v} for the center of the model's geometry and for any other point
     * of the model — the last two both in the model space of the 0..1 cube an item model is baked in. The
     * pose stack translates by {@code p} and scales by {@code s}, and {@link net.minecraft.client.renderer.entity.ItemRenderer}
     * itself then translates by {@code -0.5} on each axis before it draws the model, so {@code v} ends up at
     *
     * <pre>    p + s * (c + v - 0.5)</pre>
     *
     * <p>A full block has its geometry centered in its cube, and for it {@code c} of zero is right — which is
     * what this renderer used to do, leaving the scaling to work about the middle of the cube. Anything whose
     * geometry sits off that middle — a flat item, whose quads cover only the pixels its texture actually
     * used, or a campfire, a torch, a slab — then slid across the socket as the scale changed, because the
     * one point a scaling holds still is the cube's middle and not the item's own middle. What is wanted
     * instead is for the item's own center {@code g} to be the point that stays put: {@code g} must land on
     * the artist's {@code p} at every scale, so
     *
     * <pre>    p + s * (c + g - 0.5) = p
     *              s * (c + g - 0.5) = 0
     *                  c + g - 0.5 = 0      (for every s, so certainly for any s that is not zero)
     *                            c = 0.5 - g</pre>
     *
     * <p>That is the translate applied after the scale, so that it is scaled along with the model rather than
     * added to it. With it, every point of the model lands at {@code p + s * (v - g)}: {@code g} itself sits
     * on the artist's point at every scale, and every other point is its own distance from {@code g} times
     * the scale — the item grows and shrinks in place instead of sliding. A model with no quads to measure,
     * a model drawn by a custom renderer being the common case, falls back to {@code g = (0.5, 0.5, 0.5)},
     * which makes {@code c} zero and leaves the old behavior exactly as it was.
     */
    private static void renderSocketItem(ItemStack stack, PoseStack ms, MultiBufferSource buffer, int light,
                                         int overlay) {
        ItemStack installed = HandheldFanItem.installed(stack);
        if (installed == null || installed.isEmpty())
            return;

        Minecraft minecraft = Minecraft.getInstance();
        float scale = Config.socketItemScale();
        BakedModel model = minecraft.getItemRenderer().getModel(installed, minecraft.level, null, 0);
        Vector3f center = socketItemCenter(model, installed);
        float centerX = 0.5f - center.x;
        float centerY = 0.5f - center.y;
        float centerZ = 0.5f - center.z;
        ms.pushPose();
        ms.translate(SOCKET_X / 16, SOCKET_Y / 16, SOCKET_Z / 16);
        ms.scale(scale, scale, scale);
        // The turn goes between the scale and the centring, which is what makes it a turn about the item's
        // own centre: the stack so far is T(point) · S(scale) · R · T(0.5 - g), and renderStatic appends its
        // own T(-0.5), so a model point v lands at point + scale · R · (v - g) — the rotation fixes g, which
        // is the measured centre. Putting the rotation on the other side of T(0.5 - g) would swing the item
        // around the pose origin instead, i.e. around the socket, not around itself.
        //
        // A plain item is turned half a turn about Y, to face the way the fan does. Create's Nozzle — 分散网 —
        // is the exception: the artist's model stands it up, and on the fan it is wanted lying down with its
        // top face pointing forwards, out of the fan. Which way round that quarter turn goes is what decides
        // whether the mesh faces out of the fan or into it: the negative direction is the one that turns its
        // face outward, and the positive one buried its top face against the fan's own opening. FanCurrent
        // recognises the nozzle by registry id, because ItemStack has no is(ResourceLocation) in 1.21.1.
        if (FanCurrent.isNozzle(installed))
            ms.mulPose(Axis.XP.rotationDegrees(-90));
        else
            ms.mulPose(Axis.YP.rotationDegrees(180));
        ms.translate(centerX, centerY, centerZ);
        minecraft.getItemRenderer()
            .renderStatic(installed, ItemDisplayContext.NONE, light, overlay, ms, buffer, minecraft.level, 0);
        ms.popPose();
    }

    // --- measuring a model's own center --------------------------------------------------------------
    /**
     * The center of {@code model}'s own geometry in model space, measured and cached the first time that
     * model is drawn. See {@link #SOCKET_ITEM_CENTERS} for how long the answer is kept and why a resource
     * reload needs nothing done to it.
     */
    private static Vector3f socketItemCenter(BakedModel model, ItemStack stack) {
        Vector3f cached = SOCKET_ITEM_CENTERS.get(model);
        if (cached != null)
            return cached;

        Bounds bounds = new Bounds();
        boolean measurable = true;
        for (BakedModel pass : model.getRenderPasses(stack, false)) {
            // Every direction and then null, which is how the item renderer itself walks a model: a baked
            // model answers the culled quads of that face for a direction and the unculled quads for null,
            // so the two together are the model once over, not twice.
            for (Direction direction : Direction.values()) {
                if (!bounds.include(pass.getQuads(null, direction, RandomSource.create(QUAD_SEED),
                    ModelData.EMPTY, null)))
                    measurable = false;
            }
            if (!bounds.include(pass.getQuads(null, null, RandomSource.create(QUAD_SEED), ModelData.EMPTY, null)))
                measurable = false;
        }

        Vector3f center = measurable ? bounds.center() : new Vector3f(Bounds.CUBE_CENTER);
        SOCKET_ITEM_CENTERS.put(model, center);
        return center;
    }

    /**
     * The smallest and the largest corner of everything measured so far, in model space, with nothing in it
     * until a quad is measured. The center of a model's geometry is taken as the midpoint of those two
     * corners, which is the center of its bounding box.
     */
    private static final class Bounds {

        /** The middle of the 0..1 model cube: the point a scaling already holds still, and the fallback. */
        private static final Vector3f CUBE_CENTER = new Vector3f(0.5f, 0.5f, 0.5f);

        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;
        private boolean empty = true;

        /**
         * Adds every vertex of every quad to the measured box, and answers whether the quads were
         * measurable at all.
         *
         * <p>A {@link BakedQuad} packs its four vertices into one int array, {@link #VERTEX_INTS} ints each,
         * the position first: x, y and z as floats bit-cast into ints. A quad with no vertex in it has no
         * position to measure — {@link BakedQuad#getDirection()} is read here and is the only thing such a
         * quad does have, and a direction is an orientation, never a position, so it cannot say where the
         * quad is. Such a quad is therefore reported as unmeasurable rather than guessed at, and the caller
         * falls back to the middle of the cube. A quad that is empty of both positions and direction is a
         * placeholder that carries nothing at all, and is simply passed over.
         */
        private boolean include(List<BakedQuad> quads) {
            boolean measurable = true;
            for (BakedQuad quad : quads) {
                int[] vertices = quad.getVertices();
                if (vertices.length < VERTEX_INTS) {
                    measurable = measurable && quad.getDirection() == null;
                    continue;
                }
                for (int vertex = 0; vertex + VERTEX_INTS <= vertices.length; vertex += VERTEX_INTS) {
                    float x = Float.intBitsToFloat(vertices[vertex]);
                    float y = Float.intBitsToFloat(vertices[vertex + 1]);
                    float z = Float.intBitsToFloat(vertices[vertex + 2]);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                    empty = false;
                }
            }
            return measurable;
        }

        /** The midpoint of the measured box, or the middle of the cube if nothing was measured. */
        private Vector3f center() {
            if (empty)
                return new Vector3f(CUBE_CENTER);
            return new Vector3f((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f);
        }
    }
}
