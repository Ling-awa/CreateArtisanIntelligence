package io.github.Ling.create_ai;

import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;

import net.minecraft.ChatFormatting;
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
 *     bundle works, driven by {@link ItemStackedOnOtherEvent};</li>
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

        if (!pickUpFluid(level, player, gun, pos, hit.getDirection(), hand))
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

        if (!pickUpFluid(level, player, gun, pos, context.getClickedFace(), context.getHand()))
            return InteractionResult.PASS;
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Bucket-style pickup at one position. Create's helper removes the fluid (and the block, for a
     * fluid source) but plays no sound, so that part is done here, exactly as a bucket does.
     *
     * @return whether fluid was taken
     */
    private static boolean pickUpFluid(Level level, Player player, ItemStack gun, BlockPos pos, Direction side,
                                       InteractionHand hand) {
        BlockState target = level.getBlockState(pos);
        FluidActionResult picked = FluidUtil.tryPickUpFluid(gun, player, level, pos, side);
        if (!picked.isSuccess())
            return false;

        if (!level.isClientSide) {
            if (target.getBlock() instanceof BucketPickup pickup)
                pickup.getPickupSound(target)
                    .ifPresent(sound -> player.playSound(sound, 1.0F, 1.0F));
            // The action result carries the refilled container: picking up can swap the container
            // item, so the stack in hand is replaced rather than mutated.
            player.setItemInHand(hand, picked.getResult());
        }
        return true;
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

    /** Whether any fluid could move from {@code source} into {@code destination}. */
    public static boolean canMoveFluid(ItemStack source, ItemStack destination) {
        return FluidUtil.getFluidHandler(destination)
            .flatMap(destinationHandler -> FluidUtil.getFluidHandler(source)
                .map(sourceHandler -> FluidUtil
                    .tryFluidTransfer(destinationHandler, sourceHandler, Integer.MAX_VALUE, false)
                    .getAmount() > 0))
            .orElse(false);
    }

    /**
     * Moves as much fluid as fits from one stack into another through their item fluid handlers.
     *
     * @return null when nothing moved
     */
    @Nullable
    public static Transfer moveFluid(ItemStack source, ItemStack destination) {
        IFluidHandlerItem sourceHandler = FluidUtil.getFluidHandler(source)
            .orElse(null);
        IFluidHandlerItem destinationHandler = FluidUtil.getFluidHandler(destination)
            .orElse(null);
        if (sourceHandler == null || destinationHandler == null)
            return null;

        FluidStack moved =
            FluidUtil.tryFluidTransfer(destinationHandler, sourceHandler, Integer.MAX_VALUE, true);
        if (moved.isEmpty())
            return null;
        return new Transfer(moved, sourceHandler.getContainer(), destinationHandler.getContainer());
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

        // One item at a time, worked out on a one-item view of each side. Buckets are the reason: a
        // bucket's fluid handler refuses to do anything unless it is holding exactly one item, so a
        // stack of empty buckets answers "cannot be filled" — and the click used to fall through to the
        // vanilla swap. NeoForge's own FluidUtil reads a single item out of a stack the same way.
        ItemStack carriedOne = carried.copyWithCount(1);
        ItemStack stackedOnOne = stackedOn.copyWithCount(1);
        if (!canMoveFluid(carriedOne, stackedOnOne))
            // Nothing to move: leave the click to the vanilla container logic.
            return;
        if (event.getPlayer()
            .level().isClientSide)
            return;

        Transfer transfer = moveFluid(carriedOne, stackedOnOne);
        if (transfer == null)
            return;

        // Both sides are written back, because both were worked on as copies: a single item is simply
        // replaced, and a stack is worked out by writeBack below.
        Player player = event.getPlayer();
        writeBack(carried, transfer.source(), player, event.getCarriedSlotAccess()::set);
        writeBack(stackedOn, transfer.destination(), player, event.getSlot()::set);
        // Fluid moved: the vanilla swap must not also happen.
        event.setCanceled(true);

        if (stackedOn.is(Create_ai.SPOUT_GUN.get()))
            // Something else emptied into the gun: that fluid's pouring sound.
            playPourSound(player, transfer.moved());
        else
            // The gun did the pouring, so it sounds like Create's spout.
            AllSoundEvents.SPOUTING.playOnServer(player.level(), player.blockPosition(), 0.75f,
                0.9f + 0.2f * player.getRandom()
                    .nextFloat());
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
     */
    private static void playPourSound(Player player, FluidStack fluid) {
        Level level = player.level();
        SoundEvent sound = pourSoundFor(level, player, player.blockPosition(), fluid);
        level.playSound(null, player.getX(), player.getY() + 0.5, player.getZ(), sound, SoundSource.PLAYERS, 1.0F, 1.0F);
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
