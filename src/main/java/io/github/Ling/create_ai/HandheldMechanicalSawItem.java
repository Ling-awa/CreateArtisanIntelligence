package io.github.Ling.create_ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.content.logistics.depot.DepotBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;

/**
 * A mechanical saw you hold: aim it at a depot — this mod's {@link ProcessingTableBlock} or Create's own
 * {@code create:depot} — hold the use key, and one item on it is cut per second of holding, exactly as
 * Create's saw would cut it.
 *
 * <p>Everything about the cut is Create's, reached through {@link SawToolActions}: the recipe chain a saw
 * runs, the three filters it applies to what it finds, the rolls that decide a chanced output, and the
 * depot's own routing for the products. Nothing is paced by a shaft — a hand tool has none — so what a
 * machine spends a cycle on, this spends one second of holding on.
 *
 * <p><b>The hold.</b> {@value #CHARGE_TICKS} ticks of the use key is one stroke, and the hold is the
 * player's to end: the second stroke lands twenty ticks after the first, the third twenty ticks after
 * that, and so on for as long as the key is down. No cooldown is applied afterwards, so leaning on the
 * use key is one cut per second rather than one cut per click.
 *
 * <p><b>The cut in progress.</b> While the hold is running, {@link ToolVisuals#sawParticles} throws the
 * saw's own cutting fragments from the item being cut, away along the way the player is looking, so the
 * tool reads as a blade working rather than a switch that flips. The level picks the sound the finish
 * makes — the saw's wood or stone activation, chosen the way the saw chooses it, by the item's own sound
 * type.
 *
 * <p><b>The filter slot.</b> Right-clicking a saw in an inventory with an item puts that item in, and
 * right-clicking it with an empty hand takes it back out — the same interaction the handheld fan's socket
 * uses, and deliberately the same implementation, because a player reads a socket on a tool the same way
 * wherever it is. What the slot does is what Create's saw's slot does: it selects among the recipes the
 * input matches, so a log can be cut into this plank rather than that one. Unlike the fan's socket, what is
 * installed is kept as serialized NBT ({@link SawFilterSlotItem}), because a filter item carries its
 * contents in components and an item id alone would lose them.
 *
 * <p><b>Bypassing the filter.</b> Sneaking and right-clicking the saw toggles the filter's authority off
 * and on, and the toggle is a boolean component on the saw itself
 * ({@link SawFilterSlotItem#toggleFilter}). While it is off the installed filter is left where it is but
 * nothing it says is obeyed: the recipe search answers as though the slot were empty, so a player whose
 * filter is aimed at one output can take every other output the input matches without taking the filter
 * out and putting it back.
 *
 * <p>Registering this item in {@code Create_ai.isCustomTool} is what keeps it off the processing table: a
 * tool never becomes cargo, and its right-click always means its own action.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@EventBusSubscriber(modid = Create_ai.MODID)
// A Level is AutoCloseable, and a level read through here is never this mod's to close.
@SuppressWarnings("resource")
public class HandheldMechanicalSawItem extends Item {

    /** How long the use key has to be held before the first stroke: one second, which is one stroke. */
    public static final int CHARGE_TICKS = 20;

    /** Held for as long as the key is down, so the hold is the player's to end; the strokes run on inside it. */
    public static final int HOLD_TICKS = 72000;

    /**
     * When the last stroke of each running hold landed, counted in the use ticks {@link #heldTicks} reports.
     *
     * <p>This is the whole of the pacing, and it exists because a stroke no longer ends the hold: vanilla
     * calls {@code onUseTick} with a remaining duration and nothing else, so "the second has passed again"
     * is not a question the use state can answer twice on its own. The map is keyed by the player rather
     * than kept on the stack because two stacks of the same item are the same item to a component map; it
     * is small — one entry per player mid-stroke — and every way a hold can end removes the entry, so a new
     * hold always starts from zero.
     */
    private static final Map<Player, Integer> LAST_STROKE = new HashMap<>();

    public HandheldMechanicalSawItem(Properties properties) {
        super(properties);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return HOLD_TICKS;
    }

    /** The bow-like pull the hammer and the other hand tools use: wind up while the key is held. */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    // --- starting the hold ------------------------------------------------------------------------

    /**
     * The block-targeted path, which is the one a right-click on a depot takes.
     *
     * <p>The block the interaction pipeline already resolved is validated rather than raycast for a second
     * time, so the client and the server agree on what is aimed at. A click that cannot cut is
     * <em>consumed</em> rather than passed, for the same reason the other tools do it: a passed click is
     * repeated by the client with its other hand, and that retry arrives bare-handed, which on a depot
     * means "take the contents".
     *
     * <p>A sneaking right-click is not a hold at all: it is the filter toggle, which is answered here
     * because a click aimed at a depot arrives here first. It is consumed rather than allowed to succeed,
     * so the click ends in this handler and never continues into the chain that would offer it to the
     * block, or to the other hand, as a second, separate interaction.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;
        Level level = context.getLevel();
        DepotBlockEntity depot = DepotToolActions.depotAt(level, context.getClickedPos());
        if (depot == null)
            return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            toggleFilter(player, context.getItemInHand());
            return InteractionResult.CONSUME;
        }
        if (!SawToolActions.canCut(depot, context.getItemInHand()))
            return InteractionResult.CONSUME;
        player.startUsingItem(context.getHand());
        return InteractionResult.SUCCESS;
    }

    /**
     * Right-clicking something the block interaction did not take goes through here: the depot is found by
     * a ray from the eyes, which is how a hold can also be started from a step away.
     *
     * <p>The sneaking toggle is answered before the ray is cast, so it works while aiming at nothing — a
     * player reaching for the bypass should not have to find the depot first.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            toggleFilter(player, stack);
            return InteractionResultHolder.consume(stack);
        }
        DepotBlockEntity depot = DepotToolActions.targetOf(player);
        if (depot == null)
            // Aiming at nothing: an ordinary answer, and one that must not swing the arm.
            return InteractionResultHolder.pass(stack);
        if (!SawToolActions.canCut(depot, stack))
            return InteractionResultHolder.consume(stack);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    // --- the hold ---------------------------------------------------------------------------------

    /**
     * Every tick of the hold: show the blade cutting, and land each stroke as its second comes up.
     *
     * <p>The client shows the particles and the server does the cutting, which is the same split the other
     * tools use — a particle is the client's to build, and a recipe is the server's to run. Both sides count
     * the hold from the entity's own use timer, so neither has to be told when the first second is up, and
     * the target is re-aimed at the moment of each stroke rather than trusted from when the hold started.
     *
     * <p>The hold is not ended by a stroke. It runs until the player lets go, which is what makes holding
     * the key cut stroke after stroke; {@link #strokeNotDue} is what spaces the strokes a second apart.
     */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!(entity instanceof Player player))
            return;

        // The same ray both sides use, recomputed every tick: aiming away mid-hold ends the cut's
        // appearance honestly, and aiming at another depot mid-hold cuts that one.
        DepotBlockEntity depot = DepotToolActions.targetOf(player);
        if (level.isClientSide) {
            if (SawToolActions.canCut(depot, stack))
                ToolVisuals.sawParticles(level, depot.getBlockPos(), player.getLookAngle(), depot.getHeldItem(),
                    strokeProgress(entity));
            return;
        }
        if (strokeNotDue(player, entity))
            return;
        // One stroke: the recipe runs and Create's own saw activation is raised where the stroke is decided,
        // which is here on the server — the call broadcasts it to everyone nearby. Nothing is put on cooldown
        // and the use is not stopped: the next stroke is due a second from now.
        if (SawToolActions.cutWith(depot, stack))
            ToolVisuals.sawCutSound(level, depot.getBlockPos(), depot.getHeldItem());
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (entity instanceof Player player)
            LAST_STROKE.remove(player);
        // The client's copy of the hold ends here, and nothing has to be undone: the particles were the
        // whole of its visible part.
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        // Reached when the hold has run its whole course, which is a very long key press; the stroke pacing
        // has done its work by then, so ending the hold is all that is left.
        if (entity instanceof Player player) {
            LAST_STROKE.remove(player);
            player.stopUsingItem();
        }
        return stack;
    }

    /**
     * How long the key has been held, from the remaining use duration the entity keeps. The other tools
     * count a hold the same way, which is what keeps the client and the server in step without either side
     * being told anything.
     */
    public static int heldTicks(LivingEntity entity) {
        return HOLD_TICKS - entity.getUseItemRemainingTicks();
    }

    /**
     * How far into the stroke now in progress the blade is, 0 to 1 — what {@link ToolVisuals#sawParticles}
     * moves the fragments along the cut by.
     *
     * <p>A hold is a run of strokes, so the position within one stroke is the elapsed time taken modulo
     * {@value #CHARGE_TICKS}: the fragments travel the cut again with each swing of the blade instead of
     * freezing wherever the first stroke left them. Read off the entity's own timer, so the two sides agree
     * on it and neither has to keep a clock.
     */
    public static double strokeProgress(LivingEntity entity) {
        return (heldTicks(entity) % CHARGE_TICKS) / (double) CHARGE_TICKS;
    }

    /**
     * Whether the next stroke is still to come, and the clock it is measured against.
     *
     * <p>The first stroke is due as soon as the key has been held {@value #CHARGE_TICKS} ticks, and every
     * stroke after it is a further {@value #CHARGE_TICKS} ticks behind the last — both counted from the
     * same use-tick number {@link #heldTicks} reports, so the pacing needs no clock of its own beyond the
     * one number. The entry lives on the player rather than on the stack for the reason {@link #LAST_STROKE}
     * gives, and a fresh hold reads the elapsed time from zero again, which is what a hold that starts after
     * a release is.
     */
    private static boolean strokeNotDue(Player player, LivingEntity entity) {
        int held = heldTicks(entity);
        Integer last = LAST_STROKE.get(player);
        if (last == null)
            last = 0;
        if (held - last < CHARGE_TICKS)
            return true;
        LAST_STROKE.put(player, held);
        return false;
    }

    // --- bypassing the filter ---------------------------------------------------------------------

    /**
     * Flips the saw's filter off or on, and tells the player which way it went.
     *
     * <p>The click that does this is answered here and never becomes a use, so it cannot also start a
     * hold: sneaking is how a player says "the saw itself, not what it is pointed at".
     *
     * <p>The click reaches <em>both</em> sides — the client answers its own use and the server answers it
     * authoritatively — and each flips its own copy of the component, which is what keeps the two agreeing
     * without a round trip. The sound is therefore raised by the server alone: it broadcasts, so the player
     * who clicked hears one click. Raising it locally at the same time made the holder hear two, their own
     * client's and the server's broadcast, which is what a double click on a quick run of toggles was.
     */
    private static void toggleFilter(Player player, ItemStack stack) {
        boolean off = SawFilterSlotItem.toggleFilter(stack);
        if (!player.level().isClientSide)
            player.level()
                .playSound(null, player.blockPosition(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, .4f,
                    off ? 0.8f : 1.2f);
    }

    // --- the filter slot -------------------------------------------------------------------------

    /**
     * Right-clicking a saw with something puts it in the filter slot; right-clicking it with an empty hand
     * takes it back out. The same event and the same handling as the handheld fan's socket, down to the
     * side rules and the sound, because it is the same gesture to a player and should not have a second,
     * subtly different implementation.
     *
     * <p>The side that does it is the server, with one exception: the creative inventory's screen runs its
     * own menu locally and never sends a click packet, so the event is fired on the client there and the
     * client has to answer it, then push what changed back (see {@link CreativeSlotSync}). A click the
     * server does see must not also be answered here — it would be applied twice.
     */
    @SubscribeEvent
    public static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        if (event.getClickAction() != ClickAction.SECONDARY)
            return;

        Player player = event.getPlayer();
        boolean clientSide = player.level().isClientSide;
        if (clientSide && CreativeSlotSync.isNotCreativeScreenClick(player))
            return;

        ItemStack stackedOn = event.getStackedOnItem();
        ItemStack carried = event.getCarriedItem();
        boolean sawInSlot = stackedOn.is(Create_ai.HANDHELD_MECHANICAL_SAW.get());
        boolean sawOnCursor = carried.is(Create_ai.HANDHELD_MECHANICAL_SAW.get());
        if (!sawInSlot && !sawOnCursor)
            return;

        ItemStack saw = sawInSlot ? stackedOn : carried;
        ItemStack other = sawInSlot ? carried : stackedOn;

        if (other.isEmpty()) {
            ItemStack removed = SawFilterSlotItem.uninstall(saw, player.level()
                .registryAccess());
            if (removed.isEmpty())
                // Nothing installed: leave the click to the vanilla container logic.
                return;
            // Out of the slot and onto the cursor, or into the slot the saw came from.
            if (sawInSlot)
                event.getCarriedSlotAccess()
                    .set(removed);
            else
                event.getSlot()
                    .set(removed);
        } else {
            ItemStack previous = SawFilterSlotItem.install(saw, other, player.level()
                .registryAccess());
            // One item goes into the slot; a stack of filters leaves the rest where it was.
            other.shrink(1);
            if (!previous.isEmpty() && !player.getInventory()
                .add(previous))
                player.drop(previous, false);
        }
        // The slot is a data component on the saw itself, so whichever side holds the saw has to be told.
        if (sawInSlot)
            event.getSlot()
                .set(saw);
        else
            event.getCarriedSlotAccess()
                .set(saw);
        event.setCanceled(true);
        if (clientSide)
            // Only the slot can be told to the server; the carried stack has no packet that carries it
            // (see CreativeSlotSync). The filter travels with the saw, so the saw's slot is what matters.
            CreativeSlotSync.pushSlot(player, event.getSlot());

        Level level = player.level();
        float pitch = 1.2f + player.getRandom()
            .nextFloat() * .2f;
        if (level.isClientSide)
            level.playLocalSound(player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, .4f, pitch,
                false);
        else
            level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, .4f, pitch);
    }

    // --- the readout ------------------------------------------------------------------------------

    /**
     * Create's own tooltip blocks, plus the state of the filter slot in the same shape the handheld fan
     * shows its intake in.
     *
     * <p>The description entry is registered through {@link ItemTooltips}; this adds only the lines that
     * are about the individual stack: the filter, and whether it is being obeyed. The bypass line comes
     * first, because it is the line that changes what the filter line below it means.
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        if (SawFilterSlotItem.filterOff(stack))
            tooltip.add(Component.translatable("item.create_ai.handheld_mechanical_saw.filter_off")
                .withStyle(ChatFormatting.GOLD));
        Level level = context.level();
        ItemStack filter = level == null ? ItemStack.EMPTY
            : SawFilterSlotItem.installed(stack, level.registryAccess());
        if (filter.isEmpty()) {
            tooltip.add(Component.translatable("item.create_ai.handheld_mechanical_saw.no_filter")
                .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("item.create_ai.handheld_mechanical_saw.filter", filter.getHoverName())
            .withStyle(ChatFormatting.AQUA));
    }
}
