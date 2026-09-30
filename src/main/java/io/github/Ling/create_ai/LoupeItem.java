package io.github.Ling.create_ai;

import com.simibubi.create.content.equipment.goggles.GogglesItem;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * The loupe: Create's own goggles, plus the deployer's half of a processing table.
 *
 * <p>Everything a pair of goggles <em>is</em> comes from Create, by inheritance rather than by copy:
 * {@link GogglesItem} is a plain {@code Item} that implements {@code Equipable} with a head slot, so
 * this item is worn in the helmet slot, is put on with a right-click, and can be dispensed onto a
 * player's head, exactly as Create's goggles are. It is deliberately not an armor item — Create's
 * goggles are not one either, so there are no armor points, no durability and no armor layer: the
 * overlay is the whole of what wearing goggles does.
 *
 * <p>The overlay itself is not asked for, it is <em>registered</em> for. Create's goggle overlay,
 * the goggles entry in a kinetic block's tooltip and the rotation indicator every ask one question,
 * {@code GogglesItem.isWearingGoggles(player)}, and that question is answered by a list of
 * predicates which Create fills with its own goggles alone. Adding this item to that list is the
 * extension point Create documents for exactly this case ("use this method to add custom entry
 * points to the goggles overlay, e.g. custom armor, handheld alternatives"), and it is the only
 * thing an item has to do to be goggles: the overlay reads Create's own block entities through
 * {@code IHaveGoggleInformation}, so a machine's speed, its contents and its recipe appear without
 * this mod implementing a line of it.
 *
 * <p>What is this mod's own is the other half — the deployer action on a depot, in
 * {@link LoupeDeployerOnDepots}. It is not on the item because it is not the item's click: the
 * ingredient in the player's hand is what gets installed, so the click is read from the
 * {@code PlayerInteractEvent} instead.
 */
public class LoupeItem extends GogglesItem {

    public LoupeItem(Properties properties) {
        super(properties);
    }

    /**
     * Tells Create's goggles that this item counts as wearing them.
     *
     * <p>Called once, from common setup, which is after both mods' items exist — the predicate reads
     * this mod's item, and Create's own predicate reads its goggles, so neither may run before the
     * registry is frozen. Client and server both get the entry; only the client ever asks.
     */
    public static void registerGogglesOverlay() {
        GogglesItem.addIsWearingPredicate(LoupeItem::isWorn);
    }

    /** Whether this player has the loupe on their head, which is what the goggles overlay asks. */
    public static boolean isWorn(Player player) {
        return player.getItemBySlot(EquipmentSlot.HEAD)
            .is(Create_ai.LOUPE.get());
    }
}
