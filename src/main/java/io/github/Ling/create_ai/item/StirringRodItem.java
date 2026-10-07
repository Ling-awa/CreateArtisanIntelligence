package io.github.Ling.create_ai.item;

import io.github.Ling.create_ai.client.StirringRodItemRenderer;
import io.github.Ling.create_ai.client.StirringRodStir;
import io.github.Ling.create_ai.client.StirringRodStirRenderer;
import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.tool.BasinToolActions;
import io.github.Ling.create_ai.tool.MixingCycle;
import io.github.Ling.create_ai.tool.ToolDurability;
import io.github.Ling.create_ai.tool.ToolReequip;
import io.github.Ling.create_ai.tool.ToolVisuals;
import io.github.Ling.create_ai.tool.ToolWear;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A stirring rod: hold the use key over a basin and it stirs, the way Create's mechanical mixer
 * stirs one, for as long as the key is held.
 *
 * <p>The mixer's recipe judgment, its processing rhythm and its particles are all Create's, called
 * through {@link BasinToolActions}, {@link MixingCycle} and {@link ToolVisuals}. The basin's contents
 * are stirred by the basin itself: it is told {@code setAreFluidsMoving}, which is what makes its
 * items orbit and its fluid surface swirl while the mixer's head would be down. The item itself winds
 * up with the same bow-like pull the hammer and the spout gun use.
 *
 * <p>Nothing is kept in a block entity or in the stack: while the key is held the item knows how long
 * it has been held, and {@link MixingCycle} turns that one number into the mixer's whole cycle. That
 * is why the client and the server always agree on when the contents move and when the recipe lands.
 *
 * <p>Which is also what the rod's own appearance is drawn from, on the client alone: while a stir is
 * running the rod is drawn standing in the basin and turning ({@link StirringRodStirRenderer}) and is not
 * drawn in the hand of the player doing it ({@link StirringRodItemRenderer}), in any view — see
 * {@link StirringRodStir} for how each client knows, without a packet, whose basin to draw it in and which
 * hand to leave it out of.
 *
 * <p>The hold never ends on its own — letting go is what stops it.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class StirringRodItem extends Item {

    public StirringRodItem(Properties properties) {
        super(properties);
    }

    /**
     * A rod that lost a point of durability is still the rod in the player's hand (see {@link ToolReequip}).
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return ToolReequip.shouldReequip(oldStack, newStack, slotChanged);
    }

    /** How long this use has been going, from the remaining duration the entity keeps. */
    public static int heldTicks(LivingEntity entity, ItemStack stack) {
        return Config.toolHoldTicks() - entity.getUseItemRemainingTicks();
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotIndex, boolean isSelected) {
        ToolDurability.syncMaxDamage(stack, Config.stirringRodDurability());
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return Config.toolHoldTicks();
    }

    /** The same bow-like pull the hammer and the spout gun use: wind up while the key is held. */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    /** The block-targeted path — what a right-click on a basin takes. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;

        BasinBlockEntity basin = BasinToolActions.basinAt(context.getLevel(), context.getClickedPos());
        if (basin == null)
            return InteractionResult.PASS;
        // Answered either way, for the same reason the hammer answers: an interaction that passes is
        // repeated by the client with its other hand, and that retry is a bare-handed click. CONSUME
        // keeps that answer without swinging the arm at a basin that has nothing to stir.
        if (BasinToolActions.cannotMix(basin))
            return InteractionResult.CONSUME;
        player.startUsingItem(context.getHand());
        return InteractionResult.SUCCESS;
    }

    /** Right-clicking something the block interaction did not take goes through here. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (BasinToolActions.cannotMix(BasinToolActions.targetOf(player)))
            return InteractionResultHolder.pass(stack);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    /** Every tick of the hold: stir on the server, and show the stir on the client. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!(entity instanceof Player player))
            return;
        BasinBlockEntity basin = BasinToolActions.targetOf(player);
        if (basin == null)
            return;

        int elapsed = heldTicks(entity, stack);
        if (!level.isClientSide) {
            // One completed mixing operation, one point of the rod's own life. No backtank here: the rod is
            // one of the two tools that have nothing to do with pressurized air (see ToolWear).
            if (BasinToolActions.stirTick(level, basin, elapsed))
                ToolWear.wear(player, stack, LivingEntity.getSlotForHand(player.getUsedItemHand()));
            return;
        }

        // Zero delay means nothing matches, so there is nothing to stir and the head is up.
        int delay = BasinToolActions.mixingDelay(basin);
        boolean stirring = delay > 0;

        // The basin's own stir: its items orbit and its fluid surface swirls, which is what a real mixer
        // drives through this call for as long as its head is down. Its head goes down once and stays
        // down while the recipe keeps matching, so the contents move for as long as there is something
        // to stir — they do not settle between applications.
        basin.setAreFluidsMoving(stirring);

        // The mixer's head at the bottom: it spills its contents and churns out its mixing noises on
        // every one of those ticks — renderParticles and tickAudio both run while runningTicks is
        // twenty, which for a mixer that keeps matching its recipe is continuously.
        if (stirring && elapsed >= MixingCycle.DOWN_TICKS) {
            ToolVisuals.mixParticles(level, basin);
            AllSoundEvents.MIXING.playAt(level, basin.getBlockPos(), .75f, 1, true);
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level.isClientSide) {
            BasinBlockEntity basin = entity instanceof Player player ? BasinToolActions.targetOf(player) : null;
            if (basin != null)
                basin.setAreFluidsMoving(false);
        }
        return stack;
    }
}
