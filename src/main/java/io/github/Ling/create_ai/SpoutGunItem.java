package io.github.Ling.create_ai;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.fluids.spout.FillingBySpout;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;

import net.createmod.catnip.data.Pair;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.fluids.FluidActionResult;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack;

/**
 * The spout gun: a hand-held fluid tank.
 *
 * <p>It is a plain NeoForge fluid item. The tank is {@link FluidHandlerItemStack} over one data
 * component holding a {@link FluidStack}, so:
 * <ul>
 * <li>capacity is {@value #CAPACITY} mB, and partial amounts are allowed;</li>
 * <li>an empty tank accepts any fluid, and a loaded tank only accepts the fluid it already holds —
 *     the template's own {@code fill} is what enforces that, not code here;</li>
 * <li>draining the last drop removes the component outright, so "no fluid" really means no fluid
 *     type rather than an amount of zero.</li>
 * </ul>
 * Because the capability is registered the stock way ({@link Capabilities.FluidHandler#ITEM}), it
 * works with everything that speaks that capability — including Create's spout, which needs no
 * special casing here.
 *
 * <p>Three ways in and out:
 * <ol>
 * <li>right-clicking fluid in the world picks it up like a bucket. Both halves of that are covered:
 *     the block-interaction ray ignores fluids, so {@link #use} does its own
 *     {@link ClipContext.Fluid#SOURCE_ONLY} raycast for the open-surface case (and simply does
 *     nothing when it hits nothing), while {@link #useOn} covers the blocks that <em>do</em> produce
 *     a hit and hold fluid — cauldrons, tanks, anything exposing a block fluid handler;</li>
 * <li>in the inventory, right-clicking one stack onto another moves fluid between them, the way a
 *     bundle works, driven by {@link ItemStackedOnOtherEvent}. Emptying goes through Create's own
 *     generic item emptying — {@link GenericItemEmptying}, the class Create's item drain calls — and
 *     filling through {@link FillingBySpout}, the entry point Create's spout itself calls, which is
 *     what puts potions, splash potions, honey bottles, water bottles, buckets and anything else
 *     Create knows how to fill or empty on the same footing, with no per-fluid code here (see
 *     {@link #moveFluid});</li>
 * <li>Create's spout fills and empties it through the capability.</li>
 * </ol>
 *
 * <p>Holding the use key for {@value #HOLD_TICKS} ticks (1 s) while looking at a depot — this
 * mod's {@link ProcessingTableBlock} or Create's own {@code create:depot} — pours from the gun onto
 * whatever is on it, under Create's own filling judgment (see {@link DepotToolActions}). Sneaking
 * while holding instead voids the contents.
 *
 * <p>What is in the tank is read off the item itself: a bar whose length is the fill level and whose
 * color follows the fluid (see {@link #resolveBarColor}), black and empty when there is nothing in
 * it, plus the tooltip.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@EventBusSubscriber(modid = Create_ai.MODID)
public class SpoutGunItem extends Item {

    /** Tank size of the spout gun. */
    public static final int CAPACITY = 4000;

    /** How long the use key has to be held: one second — for the spout action, and for the purge. */
    public static final int HOLD_TICKS = 20;

    public SpoutGunItem(Properties properties) {
        super(properties);
    }

    /** Wires the tank onto the item: NeoForge's stock item-stack fluid handler template. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(
            Capabilities.FluidHandler.ITEM,
            (stack, context) -> new FluidHandlerItemStack(Create_ai.SPOUT_GUN_FLUID, stack, CAPACITY),
            Create_ai.SPOUT_GUN.get()
        );
    }

    /** What a gun stack currently holds; empty when it holds nothing. */
    public static FluidStack getFluid(ItemStack stack) {
        return FluidUtil.getFluidHandler(stack)
            .map(handler -> handler.getFluidInTank(0))
            .orElse(FluidStack.EMPTY);
    }

    /**
     * Takes fluid out of a gun. Draining the last drop removes the component, so an emptied gun
     * really has no fluid type rather than an amount of zero.
     */
    public static void drain(ItemStack stack, int amount) {
        FluidUtil.getFluidHandler(stack)
            .ifPresent(handler -> handler.drain(amount, IFluidHandler.FluidAction.EXECUTE));
    }

    // --- 1) filling from fluid in the world -------------------------------------------------------

    /**
     * The open-surface case: aiming at fluid. The block-interaction ray ignores fluids, so a bucket
     * has to do its own raycast with {@link ClipContext.Fluid#SOURCE_ONLY}; this does the same, and
     * always, because the gun only ever picks fluid up.
     *
     * <p>Aiming at air lands here too, and correctly does nothing — there is simply no fluid to take.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack gun = player.getItemInHand(hand);

        // Sneaking and holding voids the contents.
        if (player.isSecondaryUseActive() && !getFluid(gun).isEmpty()) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(gun);
        }

        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK)
            return InteractionResultHolder.pass(gun);
        BlockPos pos = hit.getBlockPos();
        if (!level.mayInteract(player, pos)
            || !player.mayUseItemAt(pos.relative(hit.getDirection()), hit.getDirection(), gun))
            return InteractionResultHolder.pass(gun);

        if (cannotPickUpFluid(level, player, gun, pos, hit.getDirection(), hand))
            return InteractionResultHolder.pass(gun);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    /**
     * The block case: aiming at something solid that holds fluid — a cauldron, a tank, any block
     * exposing a fluid handler. Plain fluid blocks never reach here, because the block ray passes
     * straight through them; they are {@link #use}'s job.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        // Sneaking is reserved for the purge, so it never picks fluid up.
        if (player == null || player.isSecondaryUseActive())
            return InteractionResult.PASS;

        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        ItemStack gun = context.getItemInHand();

        // A depot — Create's own or this mod's table — passes tool clicks through to the item
        // pipeline for exactly this: the gun's right-click there is the spout action, not a fluid
        // pickup. The result is always consumed, even when the pour is impossible: an interaction
        // that passes makes the client repeat itself with the other hand, and that retry arrives as
        // a bare-handed click, which on a depot means "take the contents". "No reaction" has to be
        // answered, not passed — and answered with CONSUME when the pour really cannot happen, so that
        // a held use key does not produce an endless arm swing over an item nothing can be poured into.
        DepotBlockEntity depot = DepotToolActions.depotAt(level, pos);
        if (depot != null) {
            if (!DepotToolActions.canSpout(depot, gun))
                return InteractionResult.CONSUME;
            // Same as the hammer: the hold has to be started explicitly, or getUseDuration and
            // finishUsingItem never run.
            player.startUsingItem(context.getHand());
            return InteractionResult.SUCCESS;
        }

        // Same guard set as the open-surface path, mirroring what a bucket checks before it acts.
        if (!level.mayInteract(player, pos)
            || !player.mayUseItemAt(pos.relative(context.getClickedFace()), context.getClickedFace(), gun))
            return InteractionResult.PASS;

        if (cannotPickUpFluid(level, player, gun, pos, context.getClickedFace(), context.getHand()))
            return InteractionResult.PASS;
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Bucket-style pickup at one position. Create's helper removes the fluid (and the block, for a
     * fluid source) but plays no sound, so that part is done here, exactly as a bucket does.
     *
     * @return whether nothing could be taken
     */
    private static boolean cannotPickUpFluid(Level level, Player player, ItemStack gun, BlockPos pos, Direction side,
                                             InteractionHand hand) {
        BlockState target = level.getBlockState(pos);
        FluidActionResult picked = FluidUtil.tryPickUpFluid(gun, player, level, pos, side);
        if (!picked.isSuccess())
            return true;

        if (!level.isClientSide) {
            if (target.getBlock() instanceof BucketPickup pickup)
                pickup.getPickupSound(target)
                    .ifPresent(sound -> player.playSound(sound, 1.0F, 1.0F));
            // The action result carries the refilled container: picking up can swap the container
            // item, so the stack in hand is replaced rather than mutated.
            player.setItemInHand(hand, picked.getResult());
        }
        return false;
    }

    // --- purge: sneak and hold the use key --------------------------------------------------------

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return HOLD_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!(entity instanceof Player player))
            return stack;

        if (player.isSecondaryUseActive()) {
            // Sneaking means the held use was the purge. Draining everything is what removes the
            // component, so the gun ends up with no fluid type at all rather than an amount of zero.
            if (level instanceof ServerLevel)
                drain(stack, Integer.MAX_VALUE);
            return stack;
        }

        // Otherwise the hold was aimed at a depot: pour, with Create's own filling judgment.
        DepotBlockEntity depot = DepotToolActions.targetOf(player);
        if (depot == null)
            return stack;

        if (level.isClientSide) {
            // The client can work out the same answer the server will, and it is the only side that
            // can build fluid particles, so it raises the splash itself.
            FluidStack fluid = getFluid(stack);
            if (DepotToolActions.canSpout(depot, stack))
                ToolVisuals.splash(level, depot.getBlockPos(), fluid);
            return stack;
        }
        DepotToolActions.spoutWith(depot, stack);
        return stack;
    }

    // --- 2) moving fluid between inventory stacks, the way a bundle interacts ----------------------

    /**
     * The stacks to write back after a transfer. An item fluid handler may hand back a different
     * container item (a water bucket becoming an empty one), so the caller writes both sides back
     * rather than assuming the originals were mutated in place.
     */
    public record Transfer(FluidStack moved, ItemStack source, ItemStack destination) {
    }

    /**
     * Whether any fluid could move from {@code source} into {@code destination}, asked of the same code
     * that would do the transfer, so the preview and the transfer can never disagree. The answer covers
     * both halves of a transfer — that there is fluid to move at all, and that the whole amount fits where
     * it is going.
     *
     * <p>The level is the one the recipe lookup needs: Create decides what a honey bottle or a potion
     * gives up, and what an item takes, by looking recipes up in it.
     */
    public static boolean canMoveFluid(Level level, ItemStack source, ItemStack destination) {
        // Whether the whole transfer would happen, asked of the same code that would do it, so the
        // preview and the transfer can never disagree.
        if (destination.is(Create_ai.SPOUT_GUN.get()))
            return emptyIntoGun(level, source, destination, true) != null;
        if (source.is(Create_ai.SPOUT_GUN.get()))
            return fillFromGun(level, source, destination, true) != null;
        return false;
    }

    /**
     * Moves one item's worth of fluid from one stack into another: out of an item through Create's own
     * generic emptying, and into one through {@link FillingBySpout}.
     *
     * <p>Filling has to be {@link FillingBySpout} rather than {@code GenericItemFilling}, and that is the
     * whole point of this method. Create's spout fills an item from either of two places: the item's own
     * fluid handler, which {@code GenericItemFilling} is the wrapper for, or a Create filling recipe, which
     * only {@code FillingBySpout} looks at. A glass bottle has no fluid handler at all — what makes it a
     * honey bottle is the filling recipe — so a gun that asked {@code GenericItemFilling} refused the very
     * pour a spout would perform. Asking {@code FillingBySpout} makes the inventory path agree with the
     * depot path ({@link DepotToolActions}, which has always gone through it): honey, and every modded
     * filling recipe, works in both.
     *
     * <p>Emptying stays on {@code GenericItemEmptying}, which is the class Create's item drain and its
     * spout's emptying recipe path both use — it knows that a honey bottle gives up honey and hands the
     * glass bottle back as the second half of its answer.
     *
     * <p>One item at a time. The stack handed in may be a stack of many, so each side is worked as a
     * one-item copy: exactly one item is emptied, or exactly one is filled, and the caller writes the
     * result back over the original stack.
     *
     * @param level the level the recipe lookup needs; it may be a server or a client level, and Create's
     *              own recipe caches answer on either
     * @return null when nothing moved
     */
    @Nullable
    public static Transfer moveFluid(Level level, ItemStack source, ItemStack destination) {
        if (destination.is(Create_ai.SPOUT_GUN.get()))
            return emptyIntoGun(level, source, destination, false);
        if (source.is(Create_ai.SPOUT_GUN.get()))
            return fillFromGun(level, source, destination, false);
        return null;
    }

    /**
     * An item emptying into the gun, the way Create's item drain empties one into its tank: ask whether
     * there is anything to empty, work the answer out on a copy first, and only commit when the whole
     * amount fits.
     *
     * <p>The whole amount, not a part of it: {@link GenericItemEmptying#emptyItem} reports what the item
     * gives up as one indivisible answer — the honey bottle becomes a glass bottle whether the tank has
     * room for all 250 mB or not — so a simulated run that does not fit is simply refused here, exactly
     * as the drain refuses to process the item.
     *
     * @param simulate whether to work the answer out without touching the stacks handed in
     * @return null when the item cannot be emptied into the gun
     */
    @Nullable
    private static Transfer emptyIntoGun(Level level, ItemStack source, ItemStack gun, boolean simulate) {
        if (!GenericItemEmptying.canItemBeEmptied(level, source))
            return null;

        // The item being emptied is a copy, and the simulation works on that copy: generic emptying
        // mutates the stack it is handed, so nothing the caller owns is touched before a commit.
        Pair<FluidStack, ItemStack> emptied = GenericItemEmptying.emptyItem(level, source.copyWithCount(1), true);
        FluidStack fluid = emptied.getFirst();
        if (fluid.isEmpty())
            return null;

        IFluidHandlerItem tank = FluidUtil.getFluidHandler(gun.copyWithCount(1))
            .orElse(null);
        if (tank == null || tank.fill(fluid, IFluidHandler.FluidAction.SIMULATE) < fluid.getAmount())
            return null;
        if (simulate)
            // Nothing was touched, so both sides are handed back as they came in.
            return new Transfer(fluid, source.copyWithCount(1), gun.copyWithCount(1));

        // One item of the stack goes away, and what that one item turned into comes back as the second
        // half of Create's answer. Generic emptying does not convert the stack it is handed: it shrinks
        // that stack by one and hands the container back separately, so the container has to be taken from
        // the pair. The stack handed in is the caller's one-item view (see moveFluid) and the write-back
        // puts a single item in its place, so the two agree. Generic emptying only ever touches the copy it
        // is given, never the caller's stack.
        ItemStack emptiedStack = source.copyWithCount(Math.max(1, source.getCount() - 1));
        Pair<FluidStack, ItemStack> result = GenericItemEmptying.emptyItem(level, emptiedStack, false);
        FluidStack moved = result.getFirst();
        if (moved.isEmpty() || tank.fill(moved, IFluidHandler.FluidAction.EXECUTE) < moved.getAmount())
            return null;
        return new Transfer(moved, result.getSecond(), tank.getContainer());
    }

    /**
     * The gun filling an item, the way Create's spout fills one from its tank: ask whether the item can be
     * filled at all, work out how much of this particular fluid it needs, and hand the gun's fluid to
     * Create to build the result. All three questions go to {@link FillingBySpout} — the same three
     * {@link DepotToolActions} asks, which is what makes the two paths agree.
     *
     * <p>The amount is asked for before anything is touched, and the gun is debited by exactly that
     * amount, so an item and a fluid that disagree about how much leaves the tank holding what it should.
     * {@code FillingBySpout.fillItem} shrinks the item stack it is handed and the fluid stack it is
     * handed to what was consumed; both are copies here, so nothing the caller owns is touched until the
     * commit, and the tank is debited from its own handler rather than by rewriting the component, which
     * is what keeps "the last drop removes the fluid type" true here as everywhere else.
     *
     * @param simulate whether to work the answer out without touching the stacks handed in
     * @return null when the gun cannot fill the item with what it holds
     */
    @Nullable
    private static Transfer fillFromGun(Level level, ItemStack gun, ItemStack destination, boolean simulate) {
        FluidStack available = getFluid(gun);
        if (available.isEmpty())
            return null;
        ItemStack item = destination.copyWithCount(1);
        if (!FillingBySpout.canItemBeFilled(level, item))
            return null;

        int required = FillingBySpout.getRequiredAmountForItem(level, item, available.copy());
        if (required <= 0 || required > available.getAmount())
            return null;

        IFluidHandlerItem tank = FluidUtil.getFluidHandler(gun.copyWithCount(1))
            .orElse(null);
        if (tank == null)
            return null;

        // Create's filling path shrinks the fluid stack it is handed to what the item consumed, which is
        // how the caller knows what was used; the tank's own copy is passed so the real one is untouched.
        FluidStack toFill = available.copy();
        ItemStack filled = FillingBySpout.fillItem(level, required, item, toFill);
        if (filled.isEmpty())
            return null;
        if (simulate)
            return new Transfer(toFill.copy(), tank.getContainer(), filled);

        FluidStack moved = tank.drain(required, IFluidHandler.FluidAction.EXECUTE);
        if (moved.isEmpty())
            return null;
        return new Transfer(moved, tank.getContainer(), filled);
    }

    @SubscribeEvent
    public static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        ItemStack carried = event.getCarriedItem();
        ItemStack stackedOn = event.getStackedOnItem();
        if (!carried.is(Create_ai.SPOUT_GUN.get()) && !stackedOn.is(Create_ai.SPOUT_GUN.get()))
            return;
        // Right-click, matching how a bundle is used. The carried stack acts on the one in the slot,
        // so fluid always moves carried -> stacked on.
        if (event.getClickAction() != ClickAction.SECONDARY)
            return;

        // The level the transfer is decided on, taken from the player the click belongs to. Create's two
        // generic entry points both read it — for the emptying recipe lookup and for whether an item can
        // be filled at all — and both sides of an inventory click happen in the player's own level, on the
        // server for a real click and on the client for the creative screen's own.
        Player player = event.getPlayer();
        Level level = player.level();

        // One item at a time, worked out on a one-item view of each side. Buckets are the reason: a
        // bucket's fluid handler refuses to do anything unless it is holding exactly one item, so a
        // stack of empty buckets answers "cannot be filled" — and the click used to fall through to the
        // vanilla swap. NeoForge's own FluidUtil reads a single item out of a stack the same way.
        ItemStack carriedOne = carried.copyWithCount(1);
        ItemStack stackedOnOne = stackedOn.copyWithCount(1);
        if (!canMoveFluid(level, carriedOne, stackedOnOne))
            // Nothing to move: leave the click to the vanilla container logic.
            return;

        // The side that does the transfer is the server, with the one exception the event is written
        // around: the creative inventory's screen runs its own inventory menu locally and never sends a
        // click packet, so the event is fired on the client there and the client has to answer it, then
        // push what changed back (see CreativeSlotSync). A click the server does see must not also be
        // answered here — the fluid would move twice.
        boolean clientSide = level.isClientSide;
        if (clientSide && CreativeSlotSync.isNotCreativeScreenClick(player))
            return;

        Transfer transfer = moveFluid(level, carriedOne, stackedOnOne);
        if (transfer == null)
            return;

        // Both sides are written back, because both were worked on as copies: a single item is simply
        // replaced, and a stack is worked out by writeBack below.
        writeBack(carried, transfer.source(), player, event.getCarriedSlotAccess()::set);
        writeBack(stackedOn, transfer.destination(), player, event.getSlot()::set);
        // Fluid moved: the vanilla swap must not also happen.
        event.setCanceled(true);
        if (clientSide)
            // Only the slot can be told to the server; a stack on the cursor has no packet that carries it
            // (see CreativeSlotSync). Whichever of the two sides sat in a slot is the one pushed here.
            CreativeSlotSync.pushSlot(player, event.getSlot());

        if (stackedOn.is(Create_ai.SPOUT_GUN.get()))
            // Something else emptied into the gun: that fluid's pouring sound.
            playPourSound(player, transfer.moved());
        else if (clientSide)
            // The gun did the pouring, so it sounds like Create's spout. This branch is the client
            // answering a click of the creative screen's own, and nothing comes back to a client for an
            // action it took itself, so the sound has to be raised here rather than waited for.
            // SoundEntry#playAt is the client's half of the entry's own playSound.
            AllSoundEvents.SPOUTING.playAt(player.level(), player.blockPosition(), 0.75f, spoutPitch(player),
                false);
        else
            // The gun did the pouring, so it sounds like Create's spout.
            AllSoundEvents.SPOUTING.playOnServer(player.level(), player.blockPosition(), 0.75f, spoutPitch(player));
    }

    /**
     * The pitch jitter Create gives a spout, drawn once per interaction so the two sides of the branch
     * above agree on it: the server's pour and the creative client's are the same pour, heard in the same
     * place.
     */
    private static float spoutPitch(Player player) {
        return 0.9f + 0.2f * player.getRandom()
            .nextFloat();
    }

    /**
     * Puts one item's worth of a transfer back where it came from.
     *
     * <p>A single item is replaced outright. A stack is left one item lighter, and what that one item
     * turned into — a filled bucket for an empty one, or the other way round — is handed to the player,
     * which is what vanilla does with a container's remainder. So a stack of empty buckets gives up one
     * bucket per click, filled, and keeps the rest.
     */
    private static void writeBack(ItemStack original, ItemStack result, Player player, Consumer<ItemStack> setter) {
        if (original.getCount() <= 1) {
            setter.accept(result);
            return;
        }
        setter.accept(original.copyWithCount(original.getCount() - 1));
        if (!player.getInventory()
            .add(result))
            player.drop(result, false);
    }

    /**
     * Plays the sound of a fluid being poured, with the same fallback a bucket uses — see
     * {@link #pourSoundFor}.
     *
     * <p>Which half of the sound system is asked depends on the side. On the server the pour is broadcast
     * to everyone nearby, which is how every other inventory interaction is heard. The client reaches this
     * only for the creative screen's own click, and an action a client took itself is never announced back
     * to it, so there the sound is played locally — {@code playLocalSound} is that half, and a no-op on a
     * server, so the one call site covers both.
     */
    private static void playPourSound(Player player, FluidStack fluid) {
        Level level = player.level();
        SoundEvent sound = pourSoundFor(level, player, player.blockPosition(), fluid);
        if (level.isClientSide)
            level.playLocalSound(player.getX(), player.getY() + 0.5, player.getZ(), sound, SoundSource.PLAYERS, 1.0F,
                1.0F, false);
        else
            level.playSound(null, player.getX(), player.getY() + 0.5, player.getZ(), sound, SoundSource.PLAYERS, 1.0F,
                1.0F);
    }

    /**
     * The sound a fluid pours with: the fluid type's own pour sound when it declares one, and
     * otherwise the generic bucket sound, or lava's for anything in the lava tag. This mirrors
     * vanilla's own bucket fallback exactly.
     *
     * <p>The fallback is the point. Declaring a pour sound is optional, so most modded fluids have
     * none; without it, everything but the handful of fluids that bother would pour in silence.
     */
    static SoundEvent pourSoundFor(Level level, @Nullable Player player, BlockPos pos, FluidStack fluid) {
        SoundEvent sound = fluid.getFluidType()
            .getSound(player, level, pos, SoundActions.BUCKET_EMPTY);
        if (sound != null)
            return sound;
        return fluid.is(FluidTags.LAVA) ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY;
    }

    // --- readout ---------------------------------------------------------------------------------

    /** What a fluid type reports when its color lives in its texture instead of a tint. */
    private static final int NO_TINT = 0xFFFFFFFF;

    /**
     * Vanilla's lava color, taken from its lava drip particles. Only a last resort: lava's own still
     * texture is read for its color like any other fluid's, and this is what is left if that texture
     * cannot be sampled at all.
     */
    private static final int LAVA_BAR_COLOR = 0xFFFF4915;

    /**
     * Always: an empty gun shows the bare black track, which is the "nothing in the tank" readout.
     */
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        FluidStack fluid = getFluid(stack);
        if (fluid.isEmpty())
            // Zero segments over the black track: a fully black, empty bar.
            return 0;
        // 13 segments is what the vanilla bar draws; any fluid at all shows at least one of them.
        return Math.max(1, Math.round(13f * fluid.getAmount() / CAPACITY));
    }

    /**
     * Colors the bar after the fluid itself, whatever fluid it is: the tint a fluid declares when it
     * declares one, and its own texture's average color when it paints the color in instead.
     * {@link #resolveBarColor} is where that decision lives.
     *
     * <p>The tint lives on NeoForge's client extensions — the only place it is kept — so this is
     * client-side code by nature, reached from the one caller that exists: the GUI drawing the bar.
     */
    @Override
    public int getBarColor(ItemStack stack) {
        FluidStack fluid = getFluid(stack);
        if (fluid.isEmpty())
            // No fluid to color: the empty bar is the black track on its own.
            return 0xFF000000;
        return resolveBarColor(fluid, IClientFluidTypeExtensions.of(fluid.getFluidType())
            .getTintColor(fluid));
    }

    /**
     * Picks the bar color out of the fluid itself, in the order the answer is likely to be found:
     * the tint the fluid declares, then the average color of its own still texture for the fluids
     * that paint their color instead of tinting it, and only then a fallback.
     *
     * @param declaredTint what the fluid's client extensions reported
     */
    static int resolveBarColor(FluidStack fluid, int declaredTint) {
        if ((declaredTint & 0xFFFFFF) != 0xFFFFFF)
            // Bars do not read well translucent, and some fluids strip their own alpha on purpose.
            return declaredTint | 0xFF000000;

        // No tint to read: the color is in the texture. Water, lava and a good many modded fluids
        // are this case, and the texture is the only place their color exists.
        int sampled = FluidBarColors.textureAverage(fluid);
        if (sampled != FluidBarColors.UNKNOWN)
            return sampled;

        // Neither a tint nor a texture that could be read. Lava is the one case where the answer is
        // not in doubt; anything else gets the plain white bar.
        return fluid.is(FluidTags.LAVA) ? LAVA_BAR_COLOR : NO_TINT;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        FluidStack fluid = getFluid(stack);
        if (fluid.isEmpty()) {
            tooltip.add(Component.translatable("item.create_ai.spout_gun.empty")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.translatable("item.create_ai.spout_gun.contents", fluid.getHoverName(),
            fluid.getAmount(), CAPACITY)
            .withStyle(ChatFormatting.AQUA));
        if (Screen.hasShiftDown())
            tooltip.add(Component.translatable("item.create_ai.spout_gun.purge_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
