package io.github.Ling.create_ai.item;

import io.github.Ling.create_ai.client.CreativeSlotSync;
import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.CreateAI;
import io.github.Ling.create_ai.fan.CatalystQueryLevel;
import io.github.Ling.create_ai.fan.FanCurrent;
import io.github.Ling.create_ai.fan.OmniFanCurrent;
import io.github.Ling.create_ai.fan.SocketCatalyst;
import io.github.Ling.create_ai.tool.ToolDurability;
import io.github.Ling.create_ai.tool.ToolReequip;
import io.github.Ling.create_ai.tool.ToolWear;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;

/**
 * A fan you hold: the encased fan's air, aimed by looking, and a socket in its side.
 *
 * <p>Hold the use key and the fan blows along the direction the player is actually looking — diagonals
 * included — {@link OmniFanCurrent} walking the ray from the eyes for four blocks. It does what a fan's air
 * does: pushes what is loose, and processes what is in it when the air has passed through a fan-processing
 * catalyst — because that judgment is Create's own and is made while the flow is built, not here. One thing it
 * deliberately does not do is blow its own holder. (The fan's first airflow, along one of the six axes, is
 * still here in {@link FanCurrent}; {@link FanCurrent#MODE} is the one line that switches between them.)
 *
 * <p><b>What is installed in it</b> is the other half. Right-clicking a fan in an inventory with another
 * item puts that item in — swapping out whatever was there — and right-clicking it with an empty hand
 * takes the installed item back out. The fan then reads that item: a block item stands for the block it
 * places and a fluid item for the fluid it carries, and either is looked up as a fan-processing catalyst,
 * so air leaving the fan behaves as though it had already passed through one. A soul campfire in the
 * socket means haunting, and a bucket of lava means blasting, for the lava it holds.
 *
 * <p>The lookup is {@link SocketCatalyst}, and it is Create's own twice over. The registered processing
 * types are walked in the order Create resolves them in — the order of their priorities, so splashing and
 * haunting come before smoking and blasting — and each type is asked the very question Create asks about a
 * position, {@code FanProcessingType#isValidAt}, in a level that knows only the installed item's block and
 * fluid ({@link CatalystQueryLevel}). That is what lets a catalyst which exists only in a NeoForge data map
 * or only in an addon's own code answer here as it does in the world, and nothing in this mod names it.
 * Only when a type's world answer is no does the socket fall back to the catalyst tags derived from the
 * type's id, which is the data-driven answer that has always worked for Create's own catalysts and for
 * addons that tag theirs. Nothing here lists a block, a fluid or a mod.
 *
 * <p>What an item still cannot answer is a state a world would have given it: a campfire in a socket is the
 * campfire block's default state, which is unlit, and the tag fallback is what keeps such a block resolving
 * as it always did. {@link SocketCatalyst} carries the whole of that reasoning.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@EventBusSubscriber(modid = CreateAI.MOD_ID)
// A Level is AutoCloseable, and a level read through here is never this mod's to close.
@SuppressWarnings("resource")
public class HandheldFanItem extends Item {

    public HandheldFanItem(Properties properties) {
        super(properties);
    }

    /**
     * The fan's own state changing — its intake, its durability — is not the player changing items
     * (see {@link ToolReequip}).
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return ToolReequip.shouldReequip(oldStack, newStack, slotChanged);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotIndex, boolean isSelected) {
        ToolDurability.syncMaxDamage(stack, Config.fanDurability());
    }

    // --- what the bar shows -----------------------------------------------------------------------

    /**
     * The bar: the backtank's air while the wearer has any, and the fan's own wear once none is left.
     *
     * <p>Create's own arrangement, taken from its potato cannon: the three methods below are why that item's
     * durability bar becomes a tank gauge while one is worn. They are delegated to rather than copied, so a
     * change in Create's arithmetic is picked up here.
     */
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return BacktankUtil.isBarVisible(stack, Config.backtankUsesPerTank());
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return BacktankUtil.getBarWidth(stack, Config.backtankUsesPerTank());
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BacktankUtil.getBarColor(stack, Config.backtankUsesPerTank());
    }

    /** Held for as long as the key is down: {@link Config#toolHoldTicks()} ticks, and the use only ends when
     * the player lets go (or the stack changes under it). */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return Config.toolHoldTicks();
    }

    /** Held out in front, the way the other tools are held. */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    /** Aiming at nothing: the fan still runs, which is the usual way to use it. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    /** Aiming at a block: the same hold, answered so the client does not retry the click bare-handed. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;
        player.startUsingItem(context.getHand());
        return InteractionResult.CONSUME;
    }

    /** Every tick of the hold, on both sides: the fan blows on both, and only the server charges for it. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (entity instanceof Player player)
            FanCurrent.tick(player);
        if (level.isClientSide || !(entity instanceof Player player))
            return;
        // One point of wear per second of holding it open, charged on the second rather than per tick:
        // twenty per second would be a tool that breaks in half a minute. Paid out of a backtank's air when
        // there is one to spend, which is what ToolWear decides.
        if ((Config.toolHoldTicks() - entity.getUseItemRemainingTicks()) % 20 != 0)
            return;
        ToolWear.wearWithAir(player, stack, LivingEntity.getSlotForHand(entity.getUsedItemHand()));
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (entity instanceof Player player)
            FanCurrent.stop(player);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof Player player)
            FanCurrent.stop(player);
        return stack;
    }

    /**
     * The installed item, shown under the fluid-style tooltip, and beside it what the socket makes of it.
     *
     * <p>The second line is the lookup's own answer, so a player can see that a bucket of dye or a bucket of
     * powder snow does stand for something without having to blow air at something and watch. The level is
     * the one the tooltip was built with — NeoForge's {@code TooltipContext} offers one, and the client has
     * it — and a tooltip built without a level still writes the line when the item's catalyst tags name a
     * type, because {@link SocketCatalyst} falls back to them.
     *
     * <p>A nozzle is the one item in the socket that is not read as a catalyst, so it is the one item that
     * gets a line of its own instead: while it is installed the fan spreads its air in every direction, and
     * the socket stops providing a processing at all — a nozzle is not a catalyst, and everything the air
     * does from then on comes from the catalysts it passes through in the world.
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        ItemStack installed = installed(stack);
        if (installed == null) {
            tooltip.add(Component.translatable("item.create_ai.handheld_encased_fan.empty")
                .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("item.create_ai.handheld_encased_fan.installed", installed.getHoverName())
            .withStyle(ChatFormatting.AQUA));
        if (FanCurrent.isNozzle(installed)) {
            tooltip.add(Component.translatable("item.create_ai.handheld_encased_fan.nozzle")
                .withStyle(ChatFormatting.GOLD));
            return;
        }
        FanProcessingType processing = processingOf(installed, context.level());
        ResourceLocation processingId =
            processing == null ? null : CreateBuiltInRegistries.FAN_PROCESSING_TYPE.getKey(processing);
        if (processingId != null)
            tooltip.add(Component.translatable("item.create_ai.handheld_encased_fan.processing", processingId.toString())
                .withStyle(ChatFormatting.GRAY));
    }

    // --- the socket -------------------------------------------------------------------------------

    /** What is in the socket, or null when it is empty. */
    @Nullable
    public static ItemStack installed(ItemStack fan) {
        ResourceLocation id = fan.get(CreateAI.FAN_UPGRADE.get());
        if (id == null)
            return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : new ItemStack(item);
    }

    /** Puts one item in the socket, handing back whatever was there. */
    @Nullable
    public static ItemStack install(ItemStack fan, ItemStack upgrade) {
        if (upgrade.isEmpty())
            return installed(fan);
        ItemStack previous = installed(fan);
        fan.set(CreateAI.FAN_UPGRADE.get(), BuiltInRegistries.ITEM.getKey(upgrade.getItem()));
        return previous;
    }

    /** Empties the socket, handing back what was in it, or null when there was nothing. */
    @Nullable
    public static ItemStack uninstall(ItemStack fan) {
        ItemStack previous = installed(fan);
        if (previous != null)
            fan.remove(CreateAI.FAN_UPGRADE.get());
        return previous;
    }

    /**
     * The processing the socket currently stands for, or null for plain air. The level is the one the fan is
     * being used in, which is where a catalyst that is only valid in a level — a data map, an addon's own
     * code — can be answered about; null is allowed and falls back to the catalyst tags.
     */
    @Nullable
    public static FanProcessingType installedProcessing(ItemStack fan, @Nullable Level level) {
        ItemStack upgrade = installed(fan);
        return upgrade == null ? null : processingOf(upgrade, level);
    }

    /**
     * What an item means to a fan's air: the block it places, or the fluid it carries, judged by Create's own
     * processing types.
     *
     * <p>{@link SocketCatalyst} does the work and its javadoc carries the reasoning: each type is asked
     * Create's own question — {@code isValidAt} — in a level that knows only the item's block and fluid
     * ({@link CatalystQueryLevel}), and then, if that says nothing, about the catalyst tags derived from its
     * registry id. No block, fluid or mod is listed anywhere.
     *
     * <p>What is still here is the call the rest of the mod makes — both flows ask a held fan this question
     * through {@link #installedProcessing(ItemStack, Level)}, once per tick, and the tooltip asks it once per
     * frame.
     */
    @Nullable
    public static FanProcessingType processingOf(ItemStack upgrade, @Nullable Level level) {
        return SocketCatalyst.resolve(upgrade, level);
    }

    // --- putting things in and taking them out, from an inventory ---------------------------------

    /**
     * Right-clicking a fan with something: into the socket. Right-clicking it with an empty hand: back out
     * again. The same event the spout gun's filling rides on, and the same reasoning — a bundle-style
     * inventory interaction, cancelled once it has done something so the vanilla swap does not also run.
     *
     * <p>Both ways round are handled, because "right-click the fan with an item" and "right-click the item
     * with the fan" are the same gesture to a player: either the fan or the other item may be the one on the
     * cursor. The event says which is which and nothing more.
     *
     * <p>The side that does it is the server, with one exception: the creative inventory's screen runs its
     * own inventory menu locally and never sends a click packet, so the event is fired on the client there
     * and the client has to answer it, then push what changed back (see {@link CreativeSlotSync}). A click
     * the server does see must not also be answered here — it would be applied twice.
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
        boolean fanInSlot = stackedOn.is(CreateAI.HANDHELD_FAN.get());
        boolean fanOnCursor = carried.is(CreateAI.HANDHELD_FAN.get());
        if (!fanInSlot && !fanOnCursor)
            return;

        ItemStack fan = fanInSlot ? stackedOn : carried;
        ItemStack other = fanInSlot ? carried : stackedOn;

        if (other.isEmpty()) {
            ItemStack removed = uninstall(fan);
            if (removed == null)
                // Nothing installed: leave the click to the vanilla container logic.
                return;
            // Out of the socket and onto the cursor, or into the slot the fan came from.
            if (fanInSlot)
                event.getCarriedSlotAccess()
                    .set(removed);
            else
                event.getSlot()
                    .set(removed);
        } else {
            ItemStack previous = install(fan, other);
            other.shrink(1);
            if (previous != null && !player.getInventory()
                .add(previous))
                player.drop(previous, false);
        }
        // The socket is a data component on the fan itself, so whichever slot holds it has to be told.
        if (fanInSlot)
            event.getSlot()
                .set(fan);
        else
            event.getCarriedSlotAccess()
                .set(fan);
        event.setCanceled(true);
        if (clientSide)
            // Only the slot can be told to the server; the carried stack has no packet that carries it
            // (see CreativeSlotSync). The fan's socket travels with the fan, so the fan's slot is what
            // matters, and it is pushed now rather than left to the screen's own listener.
            CreativeSlotSync.pushSlot(player, event.getSlot());
        // The pickup sound, with the jitter a socket change has always used. Which half of the sound
        // system answers depends on the side: the server broadcasts it, and the client — which reaches here
        // only for the creative screen's own click — plays it locally, because an action a client took
        // itself is never announced back to it. playLocalSound is that half and a no-op on a server, so the
        // one place is the whole of it.
        Level level = player.level();
        float pitch = 1.2f + player.getRandom()
            .nextFloat() * .2f;
        if (level.isClientSide)
            level.playLocalSound(player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, .4f, pitch,
                false);
        else
            level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, .4f, pitch);
    }
}
