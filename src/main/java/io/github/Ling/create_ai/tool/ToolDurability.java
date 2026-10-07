package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps an item's own maximum damage in step with the config.
 *
 * <p>Why this exists at all: an item's maximum damage is part of the item — vanilla keeps it in the stack's
 * own {@code MAX_DAMAGE} component, and the durability bar, the breaking threshold, the tooltip's
 * "Durability: x / y" line, an anvil and Mending all read it from there. The obvious way to fill that in is
 * while the item is being registered, from the config, and it does not work: this mod's common config is
 * loaded *after* NeoForge fires the registry events, so reading it there throws "Cannot get config value
 * before config is loaded" and takes the whole mod's loading down with it (which is how this class came to
 * exist — the boot test said so).
 *
 * <p>So the items are registered with the numbers below as their defaults, and each of them calls this once
 * a tick for every stack it is in an inventory of. The first tick a stack is held, its component is set to
 * whatever the config says; from then on the two agree and this returns immediately. That also means a
 * change to a durability entry is picked up by every stack in play rather than only by stacks made after a
 * restart, and a stack that is on the ground or in a chest keeps the value it had.
 */
public final class ToolDurability {

    private ToolDurability() {
    }

    /**
     * Writes the configured maximum damage onto a stack if it is not what the stack already carries.
     *
     * <p>A stack mid-break is pulled back under the new ceiling rather than left over it: raising a maximum
     * cannot hurt anything, but lowering one must not leave an item whose damage is past its own limit —
     * vanilla treats that as "already broken" and would refuse to damage it again.
     */
    public static void syncMaxDamage(ItemStack stack, int configuredMax) {
        if (configuredMax <= 0)
            return;
        if (stack.getOrDefault(DataComponents.MAX_DAMAGE, 0) == configuredMax)
            return;

        stack.set(DataComponents.MAX_DAMAGE, configuredMax);
        if (stack.getDamageValue() > configuredMax - 1)
            stack.setDamageValue(configuredMax - 1);
    }
}
