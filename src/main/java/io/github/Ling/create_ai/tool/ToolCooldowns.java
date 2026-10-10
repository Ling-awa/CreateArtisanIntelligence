package io.github.Ling.create_ai.tool;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;

/**
 * The pause a tool leaves behind after it has done something.
 *
 * <p>A tool here stands in for a machine, and a machine does not work faster because someone leans on
 * the button. Vanilla's own item cooldown is what expresses that: the client consults it before it
 * even sends a click, the server consults it as the authority, it ticks down on the player's own tick,
 * and the hotbar draws it — so the pause is both real and visible.
 *
 * <p>Every carried stack is put on that cooldown, not just the one in hand, because the tools are used
 * in sequences (a press, then the next ingredient). Switching slots is not a way to work faster.
 */
public final class ToolCooldowns {

    private ToolCooldowns() {
    }

    /** Puts everything the player is carrying on vanilla's item cooldown, for {@code ticks} ticks. */
    public static void lock(Player player, int ticks) {
        ItemCooldowns cooldowns = player.getCooldowns();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty())
                cooldowns.addCooldown(stack.getItem(), ticks);
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty())
            cooldowns.addCooldown(offhand.getItem(), ticks);
    }
}
