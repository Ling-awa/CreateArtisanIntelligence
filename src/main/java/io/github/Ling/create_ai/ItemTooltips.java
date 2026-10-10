package io.github.Ling.create_ai;

import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;

import net.createmod.catnip.lang.FontHelper;
import net.minecraft.world.item.Item;

/**
 * Create's item tooltips, for this mod's items.
 *
 * <p>This is Create's own presentation, not a hand-rolled one: {@code ItemDescription} reads the text
 * out of the language file under {@code <item id>.tooltip.summary} and
 * {@code .tooltip.condition<N>} / {@code .tooltip.behaviour<N>} pairs, draws it in Create's palette, and
 * adds the "hold shift for summary" line itself. The marker for an emphasised word is an underscore on
 * either side —{@code _Depot_} —which is how Create's own language files do it.
 *
 * <p>Registration goes into Create's {@code TooltipModifier} registry, which Create's client events
 * consult when a tooltip is built. Create registers its own items here while they register, and this is
 * called from the same place in this mod's item registration, which is why the two look alike —the
 * description is only ever *read* on the client.
 */
public final class ItemTooltips {

    private ItemTooltips() {
    }

    /** Gives every item of this mod its entry. Called once, after the items are registered. */
    public static void register() {
        describe(CreateAI.PROCESSING_TABLE_ITEM.get());
        describe(CreateAI.BRASS_MECHANICAL_SAW_ITEM.get());
        describe(CreateAI.HAMMER.get());
        describe(CreateAI.OBSIDIAN_HAMMER.get());
        describe(CreateAI.SPOUT_GUN.get());
        describe(CreateAI.STIRRING_ROD.get());
        describe(CreateAI.HANDHELD_FAN.get());
        describe(CreateAI.HANDHELD_MECHANICAL_SAW.get());
        describe(CreateAI.GOGGLES.get());
    }

    private static void describe(Item item) {
        // Exactly one entry per item. Create's registry rejects a second registration for the same item
        // ("Tried to register duplicate values for object ..."), so the description and the kinetic stats
        // are composed into a single modifier instead of registered separately. TooltipModifier.andThen
        // is Create's own mechanism for that: the description first, then the stats block under it, which
        // is the order Create's own kinetic blocks show them in.
        TooltipModifier modifier = new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE);

        // KineticStats.create returns null for anything that is not a kinetic block, so offering it to
        // every item is safe - hand tools and the depot simply keep the description alone.
        modifier = modifier.andThen(TooltipModifier.mapNull(KineticStats.create(item)));

        TooltipModifier.REGISTRY.register(item, modifier);
    }
}
