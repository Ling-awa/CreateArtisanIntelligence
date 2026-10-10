package io.github.Ling.create_ai.tool;

import net.minecraft.world.item.ItemStack;

/**
 * One answer for every tool in this mod about the held-item "change item" animation.
 *
 * <p>NeoForge asks an item whether a change to the stack in the player's hand should play that animation, and
 * answers it by default with {@code !oldStack.equals(newStack)} — so <em>any</em> change to the stack counts,
 * components included. For an ordinary item that is right: it is a different thing in the hand. For a tool it
 * is not, because a tool is constantly changing its own state while it stays exactly the thing the player is
 * holding:
 *
 * <ul>
 * <li>every hit it lands writes its durability, and a tool that twitched in the hand each time it took a point
 *     of damage would be a tool nobody could stand to use;</li>
 * <li>the spout gun writes its tank and the saw writes where its run through a recipe's outputs got to, both
 *     of which change while the item is being used exactly as intended.</li>
 * </ul>
 *
 * <p>None of that is the player changing items, so none of it should look like it. What is a change of item is
 * left alone: a different item, a different count (a stack of them shrinking or growing), or the selected
 * hotbar slot moving — which is the one case NeoForge passes in as {@code slotChanged}, and the case vanilla
 * uses to decide whether switching between two slots of the same item animates.
 *
 * <p>The tools override {@code Item#shouldCauseReequipAnimation} and hand the answer here, so the reasoning
 * above is written once rather than five times.
 */
public final class ToolReequip {

    private ToolReequip() {
    }

    /** Whether the change from one held stack to the next should play the change-item animation. */
    public static boolean shouldReequip(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged
            || oldStack.getItem() != newStack.getItem()
            || oldStack.getCount() != newStack.getCount();
    }
}
