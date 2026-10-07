package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;

import com.simibubi.create.content.equipment.armor.BacktankUtil;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * What one use of a tool costs it: a point of durability, or a share of a backtank instead.
 *
 * <p>This is Create's own arrangement, copied from its potato cannon rather than invented here. That item
 * ends its shot with
 *
 * <pre>{@code if (!BacktankUtil.canAbsorbDamage(player, maxUses()))
 *     heldStack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));}</pre>
 *
 * and so does this: the tank is asked first, and it spends {@code airInBacktank / usesPerTank} of the air
 * in the emptiest pressurized tank the wearer has. While some tank can pay, the tool is not touched at all;
 * once none can, the tool starts wearing out itself. A creative player's tank always can, which is also why
 * nothing here has to check for creative: {@link BacktankUtil#canAbsorbDamage} does.
 *
 * <p>Two methods, because the mod has two kinds of tool. The fan, the saw and the spout gun run on air and
 * use {@link #wearWithAir}; the hammers and the stirring rod have nothing to do with a backtank and use
 * {@link #wear}, which is the plain vanilla call with the creative-player guard the cannon also has.
 */
public final class ToolWear {

    private ToolWear() {
    }

    /**
     * One use of a tool that runs on pressurized air, paid for out of a worn backtank when there is one.
     *
     * <p>Never called for an item without durability: {@code hurtAndBreak} is a no-op on one, but the air
     * would still be spent, so a tool that is meant to take no wear must not call this at all.
     */
    public static void wearWithAir(LivingEntity user, ItemStack tool, EquipmentSlot slot) {
        if (BacktankUtil.canAbsorbDamage(user, Config.backtankUsesPerTank()))
            return;
        tool.hurtAndBreak(1, user, slot);
    }

    /** One use of a tool that has no backtank behind it: the plain vanilla call, minus creative players. */
    public static void wear(LivingEntity user, ItemStack tool, EquipmentSlot slot) {
        if (user instanceof Player player && player.isCreative())
            return;
        tool.hurtAndBreak(1, user, slot);
    }
}
