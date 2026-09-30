package io.github.Ling.create_ai;

/**
 * A hammer quarried from obsidian: everything the ordinary {@link HammerItem} does, and the other
 * grind on a sneaking strike.
 *
 * <p>Nothing about the press is different — the same one-click press of what a depot holds, the same
 * compression of what a basin holds, the same Create sound and crush particles, the same
 * {@value HammerItem#PRESS_COOLDOWN} ticks of cooldown, and the same refusal by
 * {@link ProcessingTableBlock} to take the tool as cargo. What differs is one word: where the ordinary
 * hammer's sneaking strike <em>mills</em> what is on a depot, as a {@code MillstoneBlockEntity} does,
 * this one <em>crushes</em> it, as a {@code CrushingWheelController} does. The recipe type is the whole
 * of the difference; the strike, the routing of the products and the
 * {@value HammerItem#GRIND_COOLDOWN} ticks of cooldown are shared (see {@link HammerToolActions}).
 *
 * <p>It is a separate item rather than a mode on the existing one on purpose: a mode would have to be
 * kept somewhere — a component on the stack, or a key to cycle it — and a hammer that says on its face
 * which of the two it is, is a hammer a player can hold two of.
 */
public class ObsidianHammerItem extends HammerItem {

    public ObsidianHammerItem(Properties properties) {
        super(properties, HammerToolActions.Grind.CRUSHING);
    }
}
