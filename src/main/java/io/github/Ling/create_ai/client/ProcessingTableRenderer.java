package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.block.ProcessingTableBlockEntity;
import io.github.Ling.create_ai.Create_ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The workbench's layout, drawn flat on the table.
 *
 * <p>Nine cells across at two pixels each, and the items at four, so neighbouring items overlap by two pixels
 * of empty model and sit edge to edge once drawn. A full sheet is therefore eighteen pixels and its outermost
 * cells hang a pixel over the edge of the block — the price of nine cells at a legible size, chosen knowingly
 * over a smaller sheet.
 *
 * <p><b>Flat.</b> Create's depot renderer is the model for this, and the pieces taken from it are the ones
 * that make an item lie down: the {@link ItemDisplayContext#FIXED} context, which is the orientation an item
 * has in an item frame and is drawn at its model's own size, a translation of three sixteenths down before a
 * quarter turn about X, and the turn itself, which is what puts the item's face to the sky. Everything else
 * in {@code DepotRenderer#renderItem} is about stacking, spinning and camera-facing bills of goods, none of
 * which a layout on a table wants.
 *
 * <p>Each item is turned in plan as well, so its top points the way the recipe reads: the direction the
 * player faced when they placed the first item. An item that is not a block model shows that turn; a block
 * model does not, and is drawn standing as Create's depot draws it.
 *
 * <p><b>Where.</b> Cells are drawn where they are in the frame, and the middle of the table is always where
 * the next item is put down: laying a sheet out is placing an item and pushing the layout along, so an item
 * the player just placed is always the one in the middle.
 *
 * <p>The completion animation is the mechanical crafter's, in miniature: while
 * {@link ProcessingTableBlockEntity#craftTicks()} runs down, the layout converges on the middle and the
 * product fades up there, and when it ends the table is empty.
 *
 * <p>Client-only: registered from {@code Create_ai.ClientModEvents#registerRenderers}.
 */
public class ProcessingTableRenderer implements BlockEntityRenderer<ProcessingTableBlockEntity> {

    /** An item's size on the table: a quarter of its own, which is about four pixels. */
    private static final float ITEM_SCALE = 0.25f;

    /**
     * How far apart two cells are: two pixels, so a nine by nine sheet is eighteen pixels of table.
     *
     * <p>Smaller than an item on purpose. An item's model is a square with the artwork floating in the middle
     * of it, so a four-pixel model on a two-pixel grid reads as items sitting edge to edge — the boxes overlap
     * and the pictures do not.
     */
    private static final float CELL_SIZE = 2f / 16f;

    /** How far a flat item is pushed down before it is laid down, as Create's depot does it. */
    private static final float FLAT_DROP = 3f / 16f;

    /** The depot casing is thirteen pixels tall: where a laid-flat item's underside belongs. */
    private static final double SURFACE_Y = 13 / 16d;

    /**
     * How far one cell of the sheet floats above the cell below it in the stacking order.
     *
     * <p>Items are four pixels wide on a two-pixel grid, so neighbours overlap by half an item and would be
     * exactly coplanar — which is what makes two overlapping faces flicker against each other. A separate
     * height per cell removes the tie: the depth test then decides, and it decides the same way every frame.
     * The step is a two-thousandth of a block, which is invisible at a glance and far larger than the depth
     * buffer's resolution at arm's length. The whole sheet climbs by eighty of them, a third of a pixel.
     */
    private static final float LAYER_STEP = 1f / 2048f;

    /** Cells per row, which is how many layers one row of the sheet is worth. */
    private static final int LAYERS_PER_ROW = 2 * ProcessingTableBlockEntity.GRID_LIMIT + 1;

    /** One above every cell: where the product sits while the sheet converges under it. */
    private static final int PRODUCT_LAYER = LAYERS_PER_ROW * LAYERS_PER_ROW;

    public ProcessingTableRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * One cell's place in the stack of the sheet, lowest first.
     *
     * <p>Up beats down and left beats right, and one number settles both: a cell is a whole row above the one
     * below it and one step above the one to its right, so an item covers the items under it and the items to
     * the right of it — the way a page is read. Diagonal neighbours fall out of the same order: of two items
     * touching corner to corner, the one further up is also the one further left, so there is nothing to
     * arbitrate.
     */
    private static int layer(int cellX, int cellY) {
        int limit = ProcessingTableBlockEntity.GRID_LIMIT;
        return (cellY + limit) * LAYERS_PER_ROW + (limit - cellX);
    }

    @Override
    public void render(ProcessingTableBlockEntity table, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Direction forward = table.forward();
        Direction right = table.right();
        if (level == null)
            return;

        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        int light = LevelRenderer.getLightColor(level, table.getBlockPos()
            .above());

        // Zero with no animation running, rising to one as it finishes: what the layout converges by.
        float progress = table.craftTicks() <= 0 ? 0f
            : 1f - (table.craftTicks() - partialTicks) / ProcessingTableBlockEntity.CRAFT_ANIMATION_TICKS;

        // Without a frame there is nothing placed and nothing to lay out in it.
        if (forward != null && right != null) {
            float yaw = planYaw(forward);

            // Drawn lowest first, so the items that end up on top are also drawn on top. The heights alone
            // would be enough for the depth test; the order makes the result the same whatever the depth
            // buffer is doing, and a sheet is at most eighty-one items.
            List<Map.Entry<Pair<Integer, Integer>, ItemStack>> layout = new ArrayList<>(table.cells()
                .entrySet());
            layout.sort(Comparator.comparingInt((Map.Entry<Pair<Integer, Integer>, ItemStack> entry) -> layer(
                entry.getKey()
                    .getLeft(),
                entry.getKey()
                    .getRight())));

            for (Map.Entry<Pair<Integer, Integer>, ItemStack> entry : layout) {
                int cellX = entry.getKey()
                    .getLeft();
                int cellY = entry.getKey()
                    .getRight();
                double sideways = cellX * CELL_SIZE * (1f - progress);
                double onwards = cellY * CELL_SIZE * (1f - progress);
                draw(itemRenderer, ms, buffer, level, light, entry.getValue(),
                    right.getStepX() * sideways + forward.getStepX() * onwards,
                    right.getStepZ() * sideways + forward.getStepZ() * onwards, yaw, ITEM_SCALE,
                    layer(cellX, cellY) * LAYER_STEP);
            }
        }

        // The product, in the middle, once there is one to show. It grows a little as the layout arrives,
        // which is what makes the last moment of the animation read as "made".
        ItemStack result = table.shownResult();
        if (!result.isEmpty())
            draw(itemRenderer, ms, buffer, level, light, result, 0, 0,
                forward == null ? 0f : planYaw(forward), ITEM_SCALE * (1f + 0.4f * progress),
                PRODUCT_LAYER * LAYER_STEP);
    }

    /**
     * The turn about Y that points an item's top away from the player, which is the way the recipe reads.
     *
     * <p>A laid-flat item's top ends up along +Z, so the turn is the one that takes +Z to the frame's
     * forward direction: none when the player faced south, a half turn when they faced north, and the
     * quarter turns in between.
     */
    private static float planYaw(Direction forward) {
        return (float) Math.toDegrees(Math.atan2(forward.getStepX(), forward.getStepZ()));
    }

    /**
     * One item, laid flat on the surface at an offset from the middle of the block.
     *
     * @param lift how far above the surface this cell's layer sits, which is what keeps overlapping items out
     *             of each other's plane (see {@link #layer})
     */
    private static void draw(ItemRenderer itemRenderer, PoseStack ms, MultiBufferSource buffer, ClientLevel level,
                             int light, ItemStack stack, double x, double z, float yaw, float scale, float lift) {
        if (stack.isEmpty())
            return;
        ms.pushPose();
        // The drop before the turn is scaled with the item, so the height it has to be given back is the
        // drop times the scale, plus the item's own half-thickness — a flat item's underside rests on the
        // casing rather than its middle.
        ms.translate(0.5D + x, SURFACE_Y + scale * (FLAT_DROP + 0.03f) + lift, 0.5D + z);
        ms.mulPose(Axis.YP.rotationDegrees(yaw));
        ms.scale(scale, scale, scale);
        ms.translate(0, -FLAT_DROP, 0);
        ms.mulPose(Axis.XP.rotationDegrees(90));
        itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ms, buffer,
            level, 0);
        ms.popPose();
    }
}
