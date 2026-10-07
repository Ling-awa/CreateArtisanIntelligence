package io.github.Ling.create_ai.item;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.tool.HammerToolActions;

/**
 * A hammer quarried from obsidian: everything the ordinary {@link HammerItem} does, and the other
 * grind on a sneaking strike.
 *
 * <p>Nothing about the press is different — the same one-click press of what a depot holds, the same
 * compression of what a basin holds, the same Create sound and crush particles, the same
 * {@link Config#hammerPressCooldown()} ticks of cooldown, and the same reach: Create's depot, found by block
 * entity, which leaves this mod's processing table out of it, since that is a workbench now and takes the
 * hammer as cargo like anything else. What differs is one word: where the ordinary
 * hammer's sneaking strike <em>mills</em> what is on a depot, as a {@code MillstoneBlockEntity} does,
 * this one <em>crushes</em> it, as a {@code CrushingWheelController} does. The recipe type is the whole
 * of the difference; the strike, the routing of the products and the
 * {@link Config#hammerGrindCooldown()} ticks of cooldown are shared (see {@link HammerToolActions}).
 *
 * <p>It is a separate item rather than a mode on the existing one on purpose: a mode would have to be
 * kept somewhere — a component on the stack, or a key to cycle it — and a hammer that says on its face
 * which of the two it is, is a hammer a player can hold two of.
 */
public class ObsidianHammerItem extends HammerItem {

    public ObsidianHammerItem(Properties properties) {
        super(properties, HammerToolActions.Grind.CRUSHING);
    }

    /** Four times the iron hammer's durability, and its own entry in the config. */
    @Override
    protected int configuredDurability() {
        return Config.obsidianHammerDurability();
    }
}
