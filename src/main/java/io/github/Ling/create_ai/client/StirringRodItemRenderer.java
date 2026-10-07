package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.Create_ai;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The stirring rod as it is held: the artist's sprite, and nothing else — except that while its holder is
 * stirring a basin with it, the rod is not drawn in their hand at all.
 *
 * <p>Why the rod leaves the hand: while a stir is running, the rod is drawn standing in the basin and
 * turning ({@link StirringRodStirRenderer}). A copy in the hand at the same time is two rods, one of them
 * stirring nothing.
 *
 * <p>Whose hand is not this renderer's to know — it is handed a stack, a display context and a pose, and
 * nothing else — so {@link StirringRodStir#hideHeldRod} answers the question instead, out of the stirs it
 * collected this tick and the player whose model is currently being drawn. That covers the stirrer's own
 * first-person view and every third-person view of them, which is what everyone else sees.
 *
 * <p>The sprite is drawn with {@link Sheets#translucentItemSheet()}, which is what vanilla draws a flat
 * item with: it does not cull back faces, so the rod is still there when the surface faces away.
 *
 * <p>Client-only: it is only ever built from {@code Create_ai.ClientModEvents#registerClientExtensions}.
 */
public class StirringRodItemRenderer extends CustomRenderedItemModelRenderer {

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
                          ItemDisplayContext transformType, PoseStack ms, MultiBufferSource buffer, int light,
                          int overlay) {
        if (StirringRodStir.hideHeldRod(transformType))
            return;

        renderer.render(model.getOriginalModel(), Sheets.translucentItemSheet(), light);
    }
}
