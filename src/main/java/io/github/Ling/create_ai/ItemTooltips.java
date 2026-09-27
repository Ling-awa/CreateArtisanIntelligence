package io.github.Ling.create_ai;

import com.simibubi.create.foundation.item.ItemDescription;
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
 * either side — {@code _Depot_} — which is how Create's own language files do it.
 *
 * <p>Registration goes into Create's {@code TooltipModifier} registry, which Create's client events
 * consult when a tooltip is built. Create registers its own items here while they register, and this is
 * called from the same place in this mod's item registration, which is why the two look alike — the
 * description is only ever *read* on the client.
 */
public final class ItemTooltips {

    private ItemTooltips() {
    }

    /** Gives every item of this mod its entry. Called once, after the items are registered. */
    public static void register() {
        describe(Create_ai.PROCESSING_TABLE_ITEM.get());
        describe(Create_ai.HAMMER.get());
        describe(Create_ai.SPOUT_GUN.get());
        describe(Create_ai.STIRRING_ROD.get());
    }

    private static void describe(Item item) {
        // No extra modifiers: Create chains kinetic stats onto this for its own machines, which have
        // speed and stress to report. A hand tool does not.
        TooltipModifier.REGISTRY.register(item, new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE));
    }
}
