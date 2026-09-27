package io.github.Ling.create_ai;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
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
 * <p>The hold never ends on its own — letting go is what stops it.
 */
public class StirringRodItem extends Item {

    /** Long enough that a held stir never runs out by itself. */
    public static final int HOLD_TICKS = 72000;

    public StirringRodItem(Properties properties) {
        super(properties);
    }

    /** How long this use has been going, from the remaining duration the entity keeps. */
    public static int heldTicks(LivingEntity entity, ItemStack stack) {
        return HOLD_TICKS - entity.getUseItemRemainingTicks();
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return HOLD_TICKS;
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
        if (!BasinToolActions.canMix(basin))
            return InteractionResult.CONSUME;
        player.startUsingItem(context.getHand());
        return InteractionResult.SUCCESS;
    }

    /** Right-clicking something the block interaction did not take goes through here. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!BasinToolActions.canMix(BasinToolActions.targetOf(player)))
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
            BasinToolActions.stirTick(level, basin, elapsed);
            return;
        }

        int delay = BasinToolActions.mixingDelay(basin);
        // The basin's own stir: its items orbit and its fluid surface swirls while the mixer's head
        // would be down, which is exactly what a real mixer tells its basin.
        basin.setAreFluidsMoving(MixingCycle.contentsMoving(elapsed, delay));

        // The mixer throws a spill of everything in the basin whenever its head is down, and plays
        // its mixing sound with them.
        if (MixingCycle.mixerTick(elapsed, delay) == MixingCycle.DOWN_TICKS) {
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
