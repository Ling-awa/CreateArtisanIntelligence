package io.github.Ling.create_ai.item;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.tool.BasinToolActions;
import io.github.Ling.create_ai.tool.DepotToolActions;
import io.github.Ling.create_ai.tool.HammerCombat;
import io.github.Ling.create_ai.tool.HammerToolActions;
import io.github.Ling.create_ai.tool.ToolCooldowns;
import io.github.Ling.create_ai.tool.ToolDurability;
import io.github.Ling.create_ai.tool.ToolReequip;
import io.github.Ling.create_ai.tool.ToolVisuals;
import io.github.Ling.create_ai.tool.ToolWear;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A hammer that presses whatever sits on Create's {@code create:depot} and compresses what is inside
 * Create's {@code create:basin}.
 *
 * <p>A right-click is the whole action: it strikes at once. The press is Create's own recipe chain
 * applied through the depot's routing, with Create's press activation sound, and the crush particles are
 * shown where the item is. Nothing is paced by kinetic speed — a hammer has none — so what a machine
 * would spend a cycle on, this spends a click on.
 *
 * <p>What follows the strike is {@link Config#hammerPressCooldown()} ticks of vanilla's item cooldown, over
 * every carried stack: one swing has weight, and a hammer is not a rapid-fire button. The grind's pause is
 * {@link Config#hammerGrindCooldown()}, the same half second unless a pack says otherwise.
 *
 * <p>Two things a block-targeted click needs, both learned the hard way: the click a right-click on a
 * block produces arrives as {@link #useOn} rather than {@link #use}, and a click that changes nothing has
 * to be *consumed* rather than passed — passing makes the client repeat it with its other hand, and that
 * retry arrives bare-handed, which on a depot means "take the contents". Consuming it also skips the arm
 * swing a plain {@code SUCCESS} would produce, so an occupied depot with nothing to press is not waved at
 * for as long as the use key is held.
 *
 * <p><b>The sneak.</b> A right-click while sneaking is not a press at all: it is a grind of one item
 * on the depot, run through {@link HammerToolActions}. The ordinary hammer mills, as a
 * {@code MillstoneBlockEntity} does, and the {@link ObsidianHammerItem} crushes, as a
 * {@code CrushingWheelController} does — the same strike with the other recipe type, which is the whole
 * of the difference between the two hammers. Sneaking is answered before the press is ever reached, so
 * a sneaking strike can never also press. The press is untouched either way.
 *
 * <p>A strike is aimed at a depot, and the depot is found by block entity rather than by block id, so this
 * mod's own processing table is not a target: it is a workbench now, and a right-click on it puts the hammer
 * down on the table like any other item would be put down.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class HammerItem extends Item {

    /** What a hammer adds to a player's bare-handed swing: six damage in total, one of them the fist. */
    private static final double ATTACK_DAMAGE_BONUS = 5.0D;

    /**
     * The swing's speed correction — an axe's, and the reason a hammer is a commitment rather than a
     * flurry. Vanilla's own axes pass the same number to the same modifier.
     */
    private static final double ATTACK_SPEED = -3.1D;

    /**
     * What a hammer swings like, as the two attribute modifiers vanilla gives a weapon.
     *
     * <p>Declared here rather than in the item's constructor so both hammers share one definition, and
     * read by {@code Create_ai} while the items are being registered — an item's attributes are part of
     * the item, so they cannot come from a config file that is read later. There is no knockback modifier
     * beside them: vanilla has an {@code ATTACK_KNOCKBACK} attribute, but a hit's knockback is not the
     * weapon's and does not go through it, so the hammer's knockback is applied by {@link HammerCombat}
     * instead.
     */
    public static ItemAttributeModifiers createAttributes() {
        return ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, ATTACK_DAMAGE_BONUS,
                    AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED,
                new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, ATTACK_SPEED,
                    AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .build();
    }

    /** Which of Create's two grinds a sneaking strike runs with this hammer. */
    private final HammerToolActions.Grind grind;

    /**
     * What the config says this hammer's durability is, asked once a tick rather than once at startup.
     *
     * <p>Overridden by the obsidian hammer, which is four times as tough; see {@link ToolDurability} for why
     * the answer cannot simply be given to the item when it is registered.
     */
    protected int configuredDurability() {
        return Config.hammerDurability();
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotIndex, boolean isSelected) {
        ToolDurability.syncMaxDamage(stack, configuredDurability());
    }

    public HammerItem(Properties properties) {
        this(properties, HammerToolActions.Grind.MILLING);
    }

    /**
     * A hammer whose sneaking strike runs the other grind. Everything else about the two hammers is the
     * press, which they share.
     */
    protected HammerItem(Properties properties, HammerToolActions.Grind grind) {
        super(properties);
        this.grind = grind;
    }

    /**
     * A hammer that lost a point of durability is still the hammer in the player's hand, and is not left
     * looking like a different item (see {@link ToolReequip}).
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return ToolReequip.shouldReequip(oldStack, newStack, slotChanged);
    }

    /** The block-targeted path — this is the one a right-click on a depot or basin takes. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;
        // Validate the block the interaction pipeline already resolved, rather than doing a second
        // raycast: the client and the server then agree on what was targeted. (UseOnContext keeps its
        // BlockHitResult protected, so the position is what is available.)
        Level level = player.level();
        BlockPos pos = context.getClickedPos();
        DepotBlockEntity depot = DepotToolActions.depotAt(level, pos);
        BasinBlockEntity basin = depot == null ? BasinToolActions.basinAt(level, pos) : null;
        if (depot == null && basin == null)
            return InteractionResult.PASS;
        // Sneaking is the grind on a depot: answered here, before the press is reached, so a sneaking strike
        // on a depot can never also press. Only a depot — a basin has nothing to grind, so sneaking at one
        // keeps the press it always had rather than being swallowed by this branch.
        if (player.isShiftKeyDown() && depot != null)
            return strikeGrind(level, player, depot, context.getHand());
        return depot != null ? strikeDepot(level, player, depot, context.getHand())
            : strikeBasin(level, player, basin, context.getHand());
    }

    /** Right-clicking something the block interaction did not take goes through here. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Either kind of target, or neither: aiming at nothing is the common case here, and it has to stay
        // a plain "do nothing" rather than an error.
        DepotBlockEntity depot = DepotToolActions.targetOf(player);
        // The grind, on the depot the eyes are on — the same ray the block path uses. Answered rather than
        // passed when there is a depot to grind, so a sneaking click never becomes the client's bare-handed
        // retry, which on a depot means "take the contents". With no depot in sight a sneaking click keeps
        // the ordinary path below, so a basin still presses.
        if (player.isShiftKeyDown() && depot != null)
            return new InteractionResultHolder<>(strikeGrind(level, player, depot, hand), stack);
        InteractionResult result = depot != null ? strikeDepot(level, player, depot, hand)
            : strikeBasin(level, player, BasinToolActions.targetOf(player), hand);
        if (result == InteractionResult.PASS)
            return InteractionResultHolder.pass(stack);
        return new InteractionResultHolder<>(result, stack);
    }

    /**
     * One grinding strike on a depot: the recipe, Create's press sound, and the fragments where the item
     * sits.
     *
     * <p>Everything but the recipe type is the press's own machinery — Create's recipe search over the
     * one type, Create's own application of the recipe with its chanced outputs rolled, and the depot's
     * own routing for the products. The two hammers differ in exactly one thing, which is
     * {@link #grind}, and it is handed straight to {@link HammerToolActions}.
     *
     * <p>The client shows the same item fragments a press throws, at the item's own position on the
     * depot: what a mill and a crusher both look like from the outside is pieces of what they are fed.
     */
    private InteractionResult strikeGrind(Level level, Player player, DepotBlockEntity depot, InteractionHand hand) {
        if (HammerToolActions.cannotGrind(depot, grind))
            // Answered, but with no swing: there is nothing here for the hammer to grind.
            return InteractionResult.CONSUME;
        if (level.isClientSide) {
            ToolVisuals.pressParticles(level, depot.getBlockPos(), depot.getHeldItem(),
                DepotToolActions.PRESS_PARTICLE_AMOUNT);
            return InteractionResult.SUCCESS;
        }
        if (HammerToolActions.grindWith(depot, grind)) {
            ToolCooldowns.lock(player, Config.hammerGrindCooldown());
            // One grind, one point of the hammer's own life. Nothing here runs on pressurized air, so the
            // backtank is not consulted: see ToolWear for which tools are which.
            ToolWear.wear(player, player.getItemInHand(hand), LivingEntity.getSlotForHand(hand));
        }
        return InteractionResult.SUCCESS;
    }

    /** One strike on a depot: the recipe, Create's sound, and the crush where the item sits. */
    private static InteractionResult strikeDepot(Level level, Player player, DepotBlockEntity depot,
                                                 InteractionHand hand) {
        if (DepotToolActions.cannotPress(depot))
            // Answered, but with no swing: there is nothing here for the hammer to do.
            return InteractionResult.CONSUME;
        if (level.isClientSide) {
            // The client works out the same answer the server will, and shows the crush at the item's own
            // position on the depot rather than at Create's belt-calibrated offset.
            ToolVisuals.pressParticles(level, depot.getBlockPos(), depot.getHeldItem(),
                DepotToolActions.PRESS_PARTICLE_AMOUNT);
            return InteractionResult.SUCCESS;
        }
        if (DepotToolActions.pressWith(depot)) {
            ToolCooldowns.lock(player, Config.hammerPressCooldown());
            ToolWear.wear(player, player.getItemInHand(hand), LivingEntity.getSlotForHand(hand));
        }
        return InteractionResult.SUCCESS;
    }

    /** The same for a basin, where the press compresses what the basin holds. */
    private static InteractionResult strikeBasin(Level level, Player player, @Nullable BasinBlockEntity basin,
                                                 InteractionHand hand) {
        if (basin == null)
            return InteractionResult.PASS;
        if (!BasinToolActions.canPress(basin))
            return InteractionResult.CONSUME;
        if (level.isClientSide) {
            // Create's press throws the basin's own input items up as it compresses them.
            ToolVisuals.basinPressParticles(level, basin.getBlockPos(), BasinToolActions.particleItems(basin));
            return InteractionResult.SUCCESS;
        }
        if (BasinToolActions.pressWith(basin)) {
            ToolCooldowns.lock(player, Config.hammerPressCooldown());
            ToolWear.wear(player, player.getItemInHand(hand), LivingEntity.getSlotForHand(hand));
        }
        return InteractionResult.SUCCESS;
    }
}
