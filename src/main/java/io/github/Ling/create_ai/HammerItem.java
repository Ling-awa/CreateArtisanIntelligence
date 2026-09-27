package io.github.Ling.create_ai;

import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.core.BlockPos;
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
 * A hammer that presses whatever sits on a depot — this mod's {@link ProcessingTableBlock} or Create's
 * own {@code create:depot} — and compresses what is inside Create's {@code create:basin}.
 *
 * <p>Hold the use key for {@value #PRESS_TICKS} ticks (0.75 s) while looking at a depot or a basin that
 * holds something pressable, and it is pressed. The press itself is instant and complete: on either
 * depot it is Create's own recipe chain applied through the depot's routing, with Create's press
 * activation sound, and the crush particles are shown where the item is. Nothing is paced by kinetic
 * speed — a hammer has none — so what a machine would spend a cycle on, this spends a click on.
 *
 * <p>Nothing is locked out afterwards either: the wind-up is the cost, and a swing that has landed
 * leaves the hands free.
 *
 * <p>Two details make the hold actually work, and both were needed:
 * <ul>
 * <li>A block-targeted click arrives as {@link #useOn}, not {@link #use} — that is what both
 *     {@code MultiPlayerGameMode.useItemOn} and {@code ServerPlayerGameMode.useItemOn} call once the
 *     block's own interaction has passed.</li>
 * <li>The use session has to be started explicitly with {@code player.startUsingItem(hand)}, exactly
 *     as vanilla's bow and food do it. Returning {@code consume} alone starts nothing, so
 *     {@code getUseDuration}/{@code finishUsingItem} would never run.</li>
 * </ul>
 *
 * <p>The table refuses to accept this item (see {@link ProcessingTableBlock}), so a right-click starts
 * this hold instead of putting the hammer down.
 */
public class HammerItem extends Item {

    /** How long the use key has to be held: 0.75 seconds. */
    public static final int PRESS_TICKS = 15;

    public HammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return PRESS_TICKS;
    }

    /** Bow-like wind-up, as requested: the arm pulls back for the duration of the hold. */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    /** The block-targeted path — this is the one a right-click on a depot or basin takes. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;
        // Validate the block the interaction pipeline already resolved, rather than doing a second
        // raycast: the client and the server then agree on what was targeted. (UseOnContext keeps
        // its BlockHitResult protected, so the position is what is available.)
        Level level = player.level();
        BlockPos pos = context.getClickedPos();
        DepotBlockEntity depot = DepotToolActions.depotAt(level, pos);
        BasinBlockEntity basin = depot == null ? BasinToolActions.basinAt(level, pos) : null;
        if (depot == null && basin == null)
            return InteractionResult.PASS;
        // The click is answered either way: passing it on makes the client repeat itself with the other
        // hand, and that retry arrives as a bare-handed click, which on a depot means "take the
        // contents". Which answer depends on whether there is anything to press — CONSUME still counts
        // as handled but does not swing the arm, so an occupied depot with nothing to press is not
        // waved at for as long as the use key is held.
        boolean somethingToPress = depot != null ? DepotToolActions.canPress(depot) : BasinToolActions.canPress(basin);
        if (!somethingToPress)
            return InteractionResult.CONSUME;
        player.startUsingItem(context.getHand());
        return InteractionResult.SUCCESS;
    }

    /** Right-clicking something the block interaction did not take goes through here. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Either kind of target, or neither: aiming at nothing is the common case here, and it has to
        // stay a plain "do nothing" rather than an error.
        DepotBlockEntity depot = DepotToolActions.targetOf(player);
        boolean pressable;
        if (depot != null)
            pressable = DepotToolActions.canPress(depot);
        else
            pressable = BasinToolActions.canPress(BasinToolActions.targetOf(player));
        if (!pressable)
            return InteractionResultHolder.pass(stack);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!(entity instanceof Player player))
            return stack;

        DepotBlockEntity depot = DepotToolActions.targetOf(player);
        if (depot != null) {
            if (level.isClientSide) {
                // The press lands at once, so the crush is shown from here — at the item's own position
                // on the depot rather than Create's belt-calibrated offset.
                if (DepotToolActions.canPress(depot))
                    ToolVisuals.pressParticles(level, depot.getBlockPos(), depot.getHeldItem(),
                        DepotToolActions.PRESS_PARTICLE_AMOUNT);
                return stack;
            }
            DepotToolActions.pressWith(depot);
            return stack;
        }

        BasinBlockEntity basin = BasinToolActions.targetOf(player);
        if (basin == null)
            return stack;

        if (level.isClientSide) {
            // Create's press throws the basin's own input items up as it compresses them.
            if (BasinToolActions.canPress(basin))
                ToolVisuals.basinPressParticles(level, basin.getBlockPos(), BasinToolActions.particleItems(basin));
            return stack;
        }
        BasinToolActions.pressWith(basin);
        return stack;
    }
}
