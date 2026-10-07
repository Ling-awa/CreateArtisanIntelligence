package io.github.Ling.create_ai.item;

import io.github.Ling.create_ai.client.CreativeSlotSync;
import io.github.Ling.create_ai.compat.ToolProcess;
import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.config.RecipeFilter;
import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.tool.DepotToolActions;
import io.github.Ling.create_ai.tool.ToolDurability;
import io.github.Ling.create_ai.tool.ToolReequip;
import io.github.Ling.create_ai.tool.ToolVisuals;
import io.github.Ling.create_ai.tool.ToolWear;

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
import net.minecraft.world.entity.Entity;
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
 * <li>capacity is {@link #capacity()} mB — eight buckets by default, and a config value
 *     ({@code spoutGunCapacity}) rather than a constant — and partial amounts are allowed;</li>
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
 * <p>A press held all the way out is the gun's pour, and what it pours into depends on what is in front of
 * it: Create's {@code create:depot} — the search asks for the block entity, so this mod's processing table, a
 * plain workbench now, is never poured on — takes the fluid under Create's own filling judgment (see
 * {@link DepotToolActions}), and any other block that stores fluid takes the amount the gun is set to, but
 * only while sneaking (see {@link #outputInto}). Held out with nothing in front of the gun at all, the same
 * press voids the tank instead. A press let go early is the opposite direction, the pickup described above,
 * so neither gesture has to be guessed at.
 *
 * <p>How much one press moves is the gun's one setting, and it is changed in the inventory rather than in the
 * world: right-click the gun there with an empty cursor and the amount steps to the next of its ten values,
 * with the new one shown under the crosshair (see {@link #onStackedOnOther}). Ten steps did not deserve a
 * screen, and every gesture the gun has out in the world was already spoken for.
 *
 * <p>What is in the tank is read off the item itself: a bar whose length is the fill level and whose
 * color follows the fluid (see {@link #resolveBarColor}), black and empty when there is nothing in
 * it, plus the tooltip.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@EventBusSubscriber(modid = Create_ai.MODID)
public class SpoutGunItem extends Item {

    /** The tank size the gun ships with: eight buckets, and the config's default. */
    public static final int DEFAULT_CAPACITY = 8000;

    /** The amount a gun moves until a player says otherwise: the largest of the ten. */
    public static final int DEFAULT_TRANSFER = 1000;

    /**
     * The ten amounts a sneaking press walks through, smallest first.
     *
     * <p>A list rather than a range because the steps are not even: halving down from a bucket reaches 1000,
     * 500, 250, 125, and the small end is the handful of millibuckets a machine's own recipe asks for. Ten
     * useful amounts that are not a formula is exactly what a list is for, and a press that steps one at a
     * time is the whole of the interface — the size a player sets it to is shown under the crosshair when
     * they set it and in the gun's tooltip the rest of the time.
     */
    public static final int[] AMOUNTS = {1, 5, 10, 25, 50, 100, 125, 250, 500, 1000};

    public SpoutGunItem(Properties properties) {
        super(properties);
    }

    /**
     * The gun's tank, its transfer amount and its durability all change while it is in the player's hand, and
     * none of that is the player changing items (see {@link ToolReequip}).
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return ToolReequip.shouldReequip(oldStack, newStack, slotChanged);
    }

    /** The gun's tank size in mB, as the config has it. */
    public static int capacity() {
        return Config.spoutGunCapacity();
    }

    // --- the amount a click moves ------------------------------------------------------------------

    /** How much fluid this gun moves in one right-click: what it was set to, or the default. */
    public static int transferAmount(ItemStack stack) {
        return stack.getOrDefault(Create_ai.SPOUT_GUN_TRANSFER, DEFAULT_TRANSFER);
    }

    /** Remember what the gun was set to. */
    public static void setTransferAmount(ItemStack stack, int amount) {
        stack.set(Create_ai.SPOUT_GUN_TRANSFER, amount);
    }

    /**
     * The amount after this one in the walk, wrapping round at the top.
     *
     * <p>An amount that is not in the list — a gun from an older version, or a stack something else edited —
     * starts the walk over at the default rather than at nothing, so the first press always lands somewhere
     * real.
     */
    public static int nextAmount(int current) {
        for (int i = 0; i < AMOUNTS.length; i++)
            if (AMOUNTS[i] == current)
                return AMOUNTS[(i + 1) % AMOUNTS.length];
        return DEFAULT_TRANSFER;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotIndex, boolean isSelected) {
        ToolDurability.syncMaxDamage(stack, Config.spoutGunDurability());
    }

    /** Wires the tank onto the item: NeoForge's stock item-stack fluid handler template. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(
            Capabilities.FluidHandler.ITEM,
            // The size is read when the handler is built for a stack rather than here, because this runs
            // during setup, before any config file has been read.
            (stack, context) -> new FluidHandlerItemStack(Create_ai.SPOUT_GUN_FLUID, stack, capacity()),
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
     * The press aimed at no block at all: air, or a fluid whose source the block ray passes straight through.
     *
     * <p>Both of the gun's long actions that are not about a block live on this path, so the charge is always
     * started and the press is always answered: holding it out with nothing in front of the gun empties the
     * tank (see {@link #finishUsingItem}), and letting it go early picks fluid up the way a bucket does (see
     * {@link #releaseUsing}). Which of the two it was is only knowable at the end of the press, so nothing
     * happens here but the press itself.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack gun = player.getItemInHand(hand);
        player.startUsingItem(hand);
        return InteractionResultHolder.sidedSuccess(gun, level.isClientSide);
    }

    /**
     * The block case: aiming at something solid that holds fluid — a cauldron, a tank, any block
     * exposing a fluid handler. Plain fluid blocks never reach here, because the block ray passes
     * straight through them; they are {@link #use}'s job.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        // Sneaking is the gun's own two settings rather than a block interaction, so the click is answered
        // and the charge is started: which of the two it was is decided by how long the press lasts.
        if (player == null)
            return InteractionResult.PASS;
        if (player.isSecondaryUseActive()) {
            player.startUsingItem(context.getHand());
            return InteractionResult.SUCCESS;
        }

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
     * The click half of a right-click on a block: take the gun's chosen amount out of it.
     *
     * <p>Only the taking. Putting fluid in is a sneaking hold — {@link #finishUsingItem} pours the chosen
     * amount into whatever container the player is looking at when the charge completes — which is what lets
     * a plain click stay what it always was. A full tank and an empty gun are the same gesture, and the one a
     * player makes most often is the one that needs no holding.
     *
     * @return whether nothing could be taken
     */
    private static boolean cannotPickUpFluid(Level level, Player player, ItemStack gun, BlockPos pos, Direction side,
                                             InteractionHand hand) {
        BlockState target = level.getBlockState(pos);
        IFluidHandler blockTank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);

        // A block that is a fluid handler of its own — a Create tank, a modded tank, a basin — is drawn from
        // here rather than by FluidUtil's pickup helper, which would empty as much of it into the gun as the
        // gun can hold, up to its whole tank in one right-click. The rule is one click, one chosen amount, so
        // the amount has to be named where the call is made. A source block or a cauldron gives up a bucket
        // and no more, which is what the stock helper is still for.
        ItemStack filled = blockTank == null
            ? pickUpBucketLike(level, player, gun, pos, side)
            : drawFromTank(blockTank, gun, transferLimit(gun));
        if (filled.isEmpty())
            return true;

        if (!level.isClientSide) {
            if (target.getBlock() instanceof BucketPickup pickup)
                pickup.getPickupSound(target)
                    .ifPresent(sound -> player.playSound(sound, 1.0F, 1.0F));
            // The action result carries the refilled container: picking up can swap the container
            // item, so the stack in hand is replaced rather than mutated.
            player.setItemInHand(hand, filled);
            ToolWear.wearWithAir(player, filled, LivingEntity.getSlotForHand(hand));
        }
        return false;
    }

    /**
     * How much one click moves: what the gun is set to, never above the config's own ceiling.
     */
    public static int transferLimit(ItemStack gun) {
        return Math.min(transferAmount(gun), Config.spoutGunTransferLimit());
    }

    /**
     * Pours the gun's chosen amount into a block's own tank, which is what a sneaking hold against anything
     * that stores fluid ends in — a tank, a basin, a machine's input.
     *
     * <p>NeoForge's transfer helper does the whole judgment: it asks the destination how much of the source's
     * fluid it wants, moves that much and no more, and reports what moved. Nothing is written by hand because
     * nothing has to be — the helper is the same one a bucket or a pipe uses, and the amount it is offered is
     * the one the gun is set to.
     *
     * <p>The feedback is the pour's own: the fluid's empty-bucket sound on the server and a splash on the
     * client, and one operation's worth of wear, exactly as a pour onto a depot costs. The client moves
     * nothing — it simulates the same transfer to know what to splash, which is how the depot pour works too.
     *
     * @return whether anything moved (or, on the client, is about to)
     */
    private static boolean outputInto(IFluidHandler tank, ItemStack gun, Level level, Player player, BlockPos pos,
                                      InteractionHand hand) {
        IFluidHandlerItem gunTank = FluidUtil.getFluidHandler(gun)
            .orElse(null);
        if (gunTank == null)
            return false;

        if (level.isClientSide) {
            FluidStack wouldMove = FluidUtil.tryFluidTransfer(tank, gunTank, transferLimit(gun), false);
            if (!wouldMove.isEmpty())
                ToolVisuals.splash(level, pos, wouldMove);
            return !wouldMove.isEmpty();
        }

        FluidStack moved = FluidUtil.tryFluidTransfer(tank, gunTank, transferLimit(gun), true);
        if (moved.isEmpty())
            return false;

        player.playSound(pourSoundFor(level, player, pos, moved), 1.0F, 1.0F);
        ToolWear.wearWithAir(player, gun, LivingEntity.getSlotForHand(hand));
        return true;
    }

    /**
     * FluidUtil's own pickup, for the blocks that give up exactly one bucket: a fluid source, a cauldron, a
     * block that is a bucket in disguise. No amount is named because there is nothing to cap — a bucket is a
     * bucket.
     *
     * @return the filled gun, or an empty stack when nothing could be taken
     */
    private static ItemStack pickUpBucketLike(Level level, Player player, ItemStack gun, BlockPos pos, Direction side) {
        FluidActionResult picked = FluidUtil.tryPickUpFluid(gun, player, level, pos, side);
        return picked.isSuccess() ? picked.getResult() : ItemStack.EMPTY;
    }

    /**
     * Moves at most {@code limit} mB out of a block's own tank and into the gun.
     *
     * <p>Worked out in full before anything moves, the way every other transfer in this class is: the drain
     * is simulated at the cap, the simulated fluid is offered to the gun to find how much of it fits, and
     * only then is that much taken for real. So a click either moves something or moves nothing, and a tank
     * is never left short by a fill that failed.
     *
     * @return the filled gun, or an empty stack when there was nothing to take or no room to take it into
     */
    private static ItemStack drawFromTank(IFluidHandler tank, ItemStack gun, int limit) {
        ItemStack filledGun = gun.copyWithCount(1);
        IFluidHandlerItem gunTank = FluidUtil.getFluidHandler(filledGun)
            .orElse(null);
        if (gunTank == null)
            return ItemStack.EMPTY;

        FluidStack offered = tank.drain(limit, IFluidHandler.FluidAction.SIMULATE);
        if (offered.isEmpty())
            return ItemStack.EMPTY;
        int fits = gunTank.fill(offered, IFluidHandler.FluidAction.SIMULATE);
        if (fits <= 0)
            return ItemStack.EMPTY;

        FluidStack taken = tank.drain(fits, IFluidHandler.FluidAction.EXECUTE);
        if (taken.isEmpty() || gunTank.fill(taken, IFluidHandler.FluidAction.EXECUTE) <= 0)
            return ItemStack.EMPTY;
        return gunTank.getContainer();
    }

    // --- purge: sneak and hold the use key --------------------------------------------------------

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return Config.spoutGunChargeTicks();
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    /**
     * The press held all the way out, which is one of three things depending on what is in front of the gun.
     *
     * <p><b>Nothing</b> — the ray lands on no block at all — is the purge: the tank is emptied into the air,
     * which is the only way to void it and the only thing a hold with nothing to aim at could sensibly mean.
     * The ray is the same open-surface one the pickup uses, so water counts as something rather than nothing
     * and holding at a lake does not quietly empty the gun.
     *
     * <p><b>A depot</b> is the pour, under Create's own filling judgment, exactly as it always was.
     *
     * <p><b>Anything else that stores fluid</b> is the pour into it, but only while sneaking: sneaking is what
     * says "this hold is about putting fluid somewhere", so a plain hold on a tank keeps doing what a plain
     * click does.
     */
    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!(entity instanceof Player player))
            return stack;

        Level world = level;
        BlockHitResult aimed = getPlayerPOVHitResult(world, player, ClipContext.Fluid.SOURCE_ONLY);
        if (aimed.getType() != HitResult.Type.BLOCK) {
            // Nothing in front of the gun: the purge. Draining everything is what removes the component, so
            // the gun ends up with no fluid type at all rather than an amount of zero.
            if (world instanceof ServerLevel && !getFluid(stack).isEmpty()) {
                drain(stack, Integer.MAX_VALUE);
                wear(player, stack);
            }
            return stack;
        }

        // A depot first: a depot is also a block that could store fluid, and the item on it is the more
        // interesting answer.
        DepotBlockEntity depot = DepotToolActions.targetOf(player);
        if (depot != null) {
            // The filter can close the spout gun out of a filling recipe. The pour is answered as though there
            // were nothing on the depot to fill, which is what keeps such a recipe in the machine's hands.
            if (!RecipeFilter.allowsItem(world, ToolProcess.FILLING, depot.getHeldItem()))
                return stack;
            if (world.isClientSide) {
                // The client can work out the same answer the server will, and it is the only side that
                // can build fluid particles, so it raises the splash itself.
                FluidStack fluid = getFluid(stack);
                if (DepotToolActions.canSpout(depot, stack))
                    ToolVisuals.splash(world, depot.getBlockPos(), fluid);
                return stack;
            }
            // Charged only for a pour that actually put fluid into something, which is the answer the pour
            // itself gives back.
            if (DepotToolActions.spoutWith(depot, stack))
                wear(player, stack);
            return stack;
        }

        if (!player.isSecondaryUseActive())
            return stack;
        IFluidHandler tank = world.getCapability(Capabilities.FluidHandler.BLOCK, aimed.getBlockPos(),
            aimed.getDirection());
        if (tank == null)
            return stack;
        outputInto(tank, stack, world, player, aimed.getBlockPos(), player.getUsedItemHand());
        return stack;
    }

    /**
     * The press that ended before the charge did: the pickup, which starting the charge had put on hold.
     *
     * <p>A press that takes can only be answered at its end, because until then nobody knows whether it was a
     * click or a hold — and the ray is the same one the hold uses, so a click at a tank takes fluid out while
     * a sneaking hold at the same tank puts fluid in. A sneaking press that ends early is nothing at all: the
     * two sneaking presses are the pour (held out) and nothing else, since the amount is set in the inventory
     * rather than out in the world.
     */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeCharged) {
        if (!(entity instanceof Player player) || player.isSecondaryUseActive())
            return;

        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK)
            return;
        BlockPos pos = hit.getBlockPos();
        if (!level.mayInteract(player, pos)
            || !player.mayUseItemAt(pos.relative(hit.getDirection()), hit.getDirection(), stack))
            return;
        cannotPickUpFluid(level, player, stack, pos, hit.getDirection(), player.getUsedItemHand());
    }

    /**
     * One operation's worth of the gun's life, out of a worn backtank's air when there is one to spend.
     *
     * <p>The one tool here whose bar is not handed over to Create's tank gauge: the gun's own bar is a fluid
     * gauge, which is the more useful thing to see on a fluid tool, so only the wear consults the backtank.
     */
    private static void wear(Player player, ItemStack gun) {
        ToolWear.wearWithAir(player, gun, LivingEntity.getSlotForHand(player.getUsedItemHand()));
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

        // An empty cursor right-clicking the gun is the gun's own setting rather than a transfer: the amount
        // steps on to the next of its ten values, and the new one is said under the crosshair. This is the
        // whole of the interface — it replaced a screen, which was more machinery than ten steps deserve —
        // and it is deliberately here rather than in the world, where every gesture the gun has is already
        // spoken for. Nothing is carried, so there is no fluid to move and the click is answered outright.
        if (carried.isEmpty()) {
            boolean answeredHere = level.isClientSide;
            if (answeredHere && CreativeSlotSync.isNotCreativeScreenClick(player))
                return;
            int next = nextAmount(transferAmount(stackedOn));
            setTransferAmount(stackedOn, next);
            Component line = Component.translatable("item.create_ai.spout_gun.transfer", next);
            if (answeredHere) {
                // The creative screen's own click: there is no server to whisper the line, so it goes on the
                // crosshair here, and what changed is pushed back the way the fluid transfers do.
                CreativeSlotSync.showOverlay(line);
                CreativeSlotSync.pushSlot(player, event.getSlot());
            } else {
                player.displayClientMessage(line, true);
            }
            event.setCanceled(true);
            return;
        }

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
        // One transfer, one point of the gun's life, charged on the server alone: the creative screen's own
        // click is answered on the client, and a creative player's backtank always has air, so there is
        // nothing to charge either way. The main-hand slot is named because a transfer happens in an
        // inventory and not in a hand — the slot is only what the break callback would be told.
        if (!clientSide) {
            ItemStack gun = stackedOn.is(Create_ai.SPOUT_GUN.get()) ? stackedOn : carried;
            ToolWear.wearWithAir(player, gun, LivingEntity.getSlotForHand(InteractionHand.MAIN_HAND));
        }
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
        return Math.max(1, Math.round(13f * fluid.getAmount() / capacity()));
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
        tooltip.add(Component.translatable("item.create_ai.spout_gun.transfer", transferAmount(stack))
            .withStyle(ChatFormatting.GOLD));
        if (fluid.isEmpty()) {
            tooltip.add(Component.translatable("item.create_ai.spout_gun.empty")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.translatable("item.create_ai.spout_gun.contents", fluid.getHoverName(),
            fluid.getAmount(), capacity())
            .withStyle(ChatFormatting.AQUA));
        if (Screen.hasShiftDown())
            tooltip.add(Component.translatable("item.create_ai.spout_gun.purge_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
