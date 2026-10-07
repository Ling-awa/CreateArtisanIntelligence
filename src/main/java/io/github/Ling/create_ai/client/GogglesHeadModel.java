package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.item.ArtisanGogglesItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * The artisan's goggles wear Create's engineer's goggles.
 *
 * <p>Create's goggles are not armor and have no armor layer. They are an ordinary item that is equipped to the
 * head slot, so what a player sees on someone's face is the item's own model drawn in the
 * {@link ItemDisplayContext#HEAD} context — and Create ends that context with a model of the goggles'
 * geometry instead of the flat sprite ({@code GogglesModel}, registered for its own item through Registrate's
 * item-model hook).
 *
 * <p>This is that same wrapper, put on this mod's item a different way. There is no Registrate here, so the
 * swap is made where NeoForge hands the baked models over: {@link ModelEvent.ModifyBakingResult}, which is
 * the last moment before they are used. Every model whose id is this mod's {@code goggles} is wrapped, so the
 * item still draws its own sprite in the hand, the inventory and on the ground, and only the head slot is
 * Create's goggles.
 *
 * <p>Nothing about the overlay changes: that is a predicate on the goggles item and is registered separately
 * (see {@link ArtisanGogglesItem}).
 */
@EventBusSubscriber(modid = Create_ai.MODID, value = Dist.CLIENT)
public final class GogglesHeadModel {

    private GogglesHeadModel() {
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ResourceLocation goggles = ResourceLocation.fromNamespaceAndPath(Create_ai.MODID, "goggles");

        // Collected first, then written: the event's map is the one being iterated, and a model can be baked
        // under more than one key (the inventory form, and any variant naming the same item model).
        List<Map.Entry<ModelResourceLocation, BakedModel>> toWrap = new ArrayList<>();
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : event.getModels()
            .entrySet()) {
            if (goggles.equals(entry.getKey()
                .id()))
                toWrap.add(entry);
        }

        for (Map.Entry<ModelResourceLocation, BakedModel> entry : toWrap)
            event.getModels()
                .put(entry.getKey(), new WornModel(entry.getValue()));
    }

    /** The item's own model everywhere but the head, and Create's goggles geometry on the head. */
    private static class WornModel extends BakedModelWrapper<BakedModel> {

        WornModel(BakedModel template) {
            super(template);
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHanded) {
            if (context == ItemDisplayContext.HEAD)
                return AllPartialModels.GOGGLES.get()
                    .applyTransform(context, pose, leftHanded);
            return super.applyTransform(context, pose, leftHanded);
        }
    }
}
