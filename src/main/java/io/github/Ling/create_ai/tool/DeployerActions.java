package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.compat.ToolProcess;
import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.config.RecipeFilter;
import io.github.Ling.create_ai.CreateAI;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.equipment.sandPaper.SandPaperItem;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.DeployerApplicationRecipe;
import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.foundation.recipe.RecipeApplier;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/**
 * The deployer's part in a processing step: what a mechanical hand does with the item in it, aimed at
 * what is lying in front of it.
 *
 * <p>A deployer never combines a held item with another item directly — that is not a mechanic Create
 * has. What it does have is a recipe chain, and both halves of it apply here:
 *
 * <ol>
 * <li><b>Sequenced assembly.</b> {@code create:sequenced_assembly} turns a run of steps into a product,
 *     and one of those steps is always a deployer step. Feeding the step's ingredient to the item on
 *     the table advances the assembly — a golden sheet plus a cogwheel, then a large cogwheel, then an
 *     iron nugget, is how a precision mechanism is built, one throw of the lever at a time. The step's
 *     result is the transitional item, exactly as on a belt.</li>
 * <li><b>Applying.</b> {@code create:deploying} and {@code create:item_application} are the
 *     single-shot versions of the same idea: an ingredient, a tool, and what comes out. Create ships
 *     168 deploying recipes and 9 item-application ones, and between them they are also the answer to
 *     "what does an axe do to a copper block here": 105 of those recipes take
 *     {@code #minecraft:axes} to scrape a weathered block back one stage or take its wax off, and 60
 *     take a honeycomb <em>block</em> to wax one. That is why a honeycomb block waxes without being
 *     spent — it is the tool those recipes name, and they all declare {@code keep_held_item}.</li>
 * </ol>
 *
 * <p>Then there is the part Create's own data does not cover, kept because a processing table is meant
 * to be the place where a player does by hand what the factory does by machine: <b>vanilla's own
 * item-on-block treatments</b>, with the block they would have been used on standing in as the item on
 * the table. Create's recipes name a honeycomb block as the waxing tool, so a plain honeycomb matches
 * nothing and lands here — where it waxes, and is spent, exactly as it would be in hand. Logs strip,
 * and modded blocks whose weathering or waxables Create has no recipe for are reached the same way.
 *
 * <p>The plumbing is Create's own: {@code DeployerBlockEntity#getRecipe} for finding the recipe, and
 * {@code BeltDeployerCallbacks#activate} for applying it — the same pair a deployer over a belt runs,
 * minus the deployer's arm. Its result routing is reused too, through the depot's
 * {@code TransportedItemStackHandlerBehaviour}, so products land in the table's eight output slots and
 * anything left over stays on top.
 *
 * <p>One right-click runs one step, and leaves {@link Config#deployerCooldown()} ticks of item cooldown
 * behind: a deployer has one arm and takes a moment to move it, and a player does not get to work faster
 * than that by leaning on the use key.
 */
public final class DeployerActions {

    private DeployerActions() {
    }

    /** What an item-on-item treatment produces, and whether the held item survives it. */
    private record Treatment(ItemStack result, boolean keepHeld) {
    }

    /**
     * Runs one deployer step against the item on the table.
     *
     * @param hand the hand the player is holding the ingredient in; a stack in the other hand is left
     *             alone
     */
    public static void deploy(Level level, DepotBlockEntity table, Player player, InteractionHand hand,
                              BlockHitResult hit) {
        if (level.isClientSide)
            return;

        ItemStack held = player.getItemInHand(hand);
        // A custom tool's right-click already means its own action, and the table's hand rules keep
        // tools out of the contents. Either way, it is not an ingredient and has no deployer step.
        if (held.isEmpty() || CreateAI.isCustomTool(held))
            return;
        // An item that is still cooling down is not an ingredient either. This is what makes one
        // right-click one step: the client stops sending clicks for a cooled item, and a client that
        // ignores its own cooldowns is stopped here.
        if (player.getCooldowns()
            .isOnCooldown(held.getItem()))
            return;

        DepotBehaviour depot = table.getBehaviour(DepotBehaviour.TYPE);
        TransportedItemStackHandlerBehaviour handler = table.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
        if (depot == null || handler == null)
            return;
        ItemStack onTable = depot.getHeldItemStack();
        if (onTable.isEmpty())
            return;

        List<ItemStack> results;
        boolean keepHeld;
        // The filter can close the goggles out of an application or a deploying recipe: the click is answered
        // as though no recipe matched, so the recipe stays a machine's job.
        Recipe<?> recipe = findRecipe(level, onTable, held);
        if (recipe != null && !RecipeFilter.allows(level, recipe, ToolProcess.DEPLOYING))
            return;
        if (recipe != null) {
            keepHeld = recipe instanceof ItemApplicationRecipe application && application.shouldKeepHeldItem();
            // Create's own scraping and de-waxing recipes are declared keep_held_item, because a
            // deployer arm does not wear out its tools. A player working the table by hand pays what
            // vanilla's own axe interaction costs — one point of durability — so an axe is never both
            // kept and free here.
            if (held.getItem() instanceof AxeItem)
                keepHeld = false;
            results = RecipeApplier.applyRecipeOn(level, onTable.copyWithCount(1), recipe, true);
        } else {
            Treatment treatment = treat(level, player, hand, held, onTable, table.getBlockPos(), hit, true);
            if (treatment == null)
                return;
            keepHeld = treatment.keepHeld();
            results = List.of(treatment.result());
        }

        boolean[] applied = { false };
        handler.handleProcessingOnAllItems(transported -> convert(level, transported, results, applied));
        if (!applied[0])
            return;

        // The lock goes on before the held item is spent, not after: the lock covers what the player is
        // carrying, and the item that was just used is about to stop being carried — so it would be the one
        // item left without a cooldown, both in the hotbar's indicator and for anything that asks whether it
        // may be used again. Locking first means the item the action spent is on cooldown with the rest.
        ToolCooldowns.lock(player, Config.deployerCooldown());
        consumeHeld(player, hand, keepHeld);
        table.notifyUpdate();
        // Create's own feedback for a deployer that did something.
        level.playSound(null, table.getBlockPos(), SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, .25f, .75f);
    }

    /**
     * Whether a deployer step would do nothing here, worked out the way {@link #deploy} works it out and
     * without doing any of it. Phrased as the failure so the callers, which all ask it before a click, can
     * read it straight.
     *
     * <p>The client is the reason this exists: whether a click is worth swinging at is an animation
     * decision, and the client makes it before the server ever hears about the click. Both sides ask the
     * same question, so the arm and the action agree.
     */
    public static boolean cannotDeploy(Level level, ItemStack onTable, ItemStack held, @Nullable Player player,
                                       InteractionHand hand, BlockHitResult hit) {
        if (level == null || onTable.isEmpty() || held.isEmpty() || CreateAI.isCustomTool(held))
            return true;
        // The same cooldown gate the action itself uses: a click that is still cooling down does nothing,
        // and a click that does nothing should not look like it did.
        if (player != null && player.getCooldowns()
            .isOnCooldown(held.getItem()))
            return true;
        if (findRecipe(level, onTable, held) != null)
            return false;
        return treat(level, player, hand, held, onTable, BlockPos.ZERO, hit, false) == null;
    }

    /**
     * The recipe a deployer would find for this pair, or null for nothing at all.
     *
     * <p>{@code DeployerBlockEntity#getRecipe}, with its search order kept — a sequenced assembly step
     * outranks a plain recipe, and {@code CAN_BE_AUTOMATED} still screens out the recipes automation is
     * not allowed to run.
     */
    public static Recipe<?> findRecipe(Level level, ItemStack onTable, ItemStack held) {
        if (level == null || onTable.isEmpty() || held.isEmpty())
            return null;

        // Sand paper is not an ingredient, so Create checks it on its own, against the item alone.
        // All three lookups come back typed by their erased input, so each is unwrapped to its recipe.
        if (held.getItem() instanceof SandPaperItem) {
            Recipe<?> polishing = level.getRecipeManager()
                .getRecipeFor(AllRecipeTypes.SANDPAPER_POLISHING.getType(), new SingleRecipeInput(onTable), level)
                .filter(AllRecipeTypes.CAN_BE_AUTOMATED)
                .map(RecipeHolder::value)
                .orElse(null);
            if (polishing != null)
                return polishing;
        }

        // Slot 0 is what the deployer looks at, slot 1 is what it holds: the order every
        // ItemApplicationRecipe matches on.
        ItemStackHandler inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, onTable);
        inventory.setStackInSlot(1, held);
        RecipeWrapper wrapper = new RecipeWrapper(inventory);

        // A sequenced assembly step outranks a plain recipe, as it does in Create.
        Optional<RecipeHolder<DeployerApplicationRecipe>> step = SequencedAssemblyRecipe.getRecipe(level, wrapper,
            AllRecipeTypes.DEPLOYING.getType(), DeployerApplicationRecipe.class);
        if (step.isPresent())
            return step.get()
                .value();

        Recipe<?> deploying = level.getRecipeManager()
            .getRecipeFor(AllRecipeTypes.DEPLOYING.getType(), wrapper, level)
            .filter(AllRecipeTypes.CAN_BE_AUTOMATED)
            .map(RecipeHolder::value)
            .orElse(null);
        if (deploying != null)
            return deploying;

        return level.getRecipeManager()
            .getRecipeFor(AllRecipeTypes.ITEM_APPLICATION.getType(), wrapper, level)
            .filter(AllRecipeTypes.CAN_BE_AUTOMATED)
            .map(RecipeHolder::value)
            .orElse(null);
    }

    /**
     * The item-out of one step, routed the way {@code BeltDeployerCallbacks#activate} routes it: the
     * step consumes one item from the stack on the table, the results take its place, and what is left
     * of the stack is held back on the table.
     */
    private static TransportedResult convert(Level level, TransportedItemStack transported, List<ItemStack> results,
                                             boolean[] applied) {
        TransportedItemStack left = transported.copy();
        left.stack.shrink(1);
        applied[0] = true;

        if (results.isEmpty())
            return TransportedResult.convertTo(left);

        List<TransportedItemStack> products = new ArrayList<>(results.size());
        for (ItemStack stack : results) {
            TransportedItemStack product = transported.copy();
            product.stack = stack;
            product.locked = false;
            product.angle = BeltHelper.isItemUpright(stack) ? 180 : level.random.nextInt(360);
            products.add(product);
        }
        return TransportedResult.convertToAndLeaveHeld(products, left);
    }

    /**
     * Vanilla's tool-on-block treatments, on the item lying on the table instead of a placed block.
     *
     * @param apply whether to show the treatment's sounds and particles; false works the answer out and
     *              touches nothing, which is how {@link #canDeploy} asks
     * @return null when neither the axe nor the honeycomb has anything to do here
     */
    private static Treatment treat(Level level, Player player, InteractionHand hand, ItemStack held, ItemStack onTable,
                                   BlockPos pos, BlockHitResult hit, boolean apply) {
        Block block = Block.byItem(onTable.getItem());
        if (block == Blocks.AIR)
            return null;
        BlockState state = block.defaultBlockState();

        if (held.is(Items.HONEYCOMB) || held.is(Items.HONEYCOMB_BLOCK)) {
            Optional<BlockState> waxed = HoneycombItem.getWaxed(state);
            if (waxed.isEmpty())
                return null;
            // 3003 is the client-side wax-on effect: the sound and the white particles.
            if (apply)
                level.levelEvent(player, 3003, pos, 0);
            // A honeycomb is spent on the block it waxes; a honeycomb block is a block, and a tool
            // that stays in the hand.
            return new Treatment(new ItemStack(waxed.get()
                .getBlock()
                .asItem()), held.is(Items.HONEYCOMB_BLOCK));
        }

        if (!(held.getItem() instanceof AxeItem))
            return null;

        UseOnContext context = new UseOnContext(player, hand, hit);
        // Vanilla's own order and its own effects: strip a log, scrape a weathered block back one
        // stage, then take the wax off.
        ItemAbility[] abilities =
            { ItemAbilities.AXE_STRIP, ItemAbilities.AXE_SCRAPE, ItemAbilities.AXE_WAX_OFF };
        for (ItemAbility ability : abilities) {
            BlockState modified = state.getToolModifiedState(context, ability, false);
            if (modified == null || modified.isAir())
                continue;
            if (apply) {
                if (ability == ItemAbilities.AXE_STRIP) {
                    level.playSound(player, pos, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 1f, 1f);
                } else if (ability == ItemAbilities.AXE_SCRAPE) {
                    level.playSound(player, pos, SoundEvents.AXE_SCRAPE, SoundSource.BLOCKS, 1f, 1f);
                    level.levelEvent(player, 3005, pos, 0);
                } else {
                    level.playSound(player, pos, SoundEvents.AXE_WAX_OFF, SoundSource.BLOCKS, 1f, 1f);
                    level.levelEvent(player, 3004, pos, 0);
                }
            }
            return new Treatment(new ItemStack(modified.getBlock()
                .asItem()), false);
        }
        return null;
    }

    /**
     * Spends the held item the way a deployer does: nothing for a recipe that declares its tool is kept,
     * one point of durability for anything damageable, and otherwise the item itself — with whatever it
     * leaves behind put where a player would have it.
     */
    private static void consumeHeld(Player player, InteractionHand hand, boolean keepHeld) {
        if (keepHeld)
            return;
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty())
            return;

        if (held.isDamageableItem()) {
            held.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
            return;
        }
        // The crafting remainder, asked of the stack: NeoForge's ItemStack-sensitive form answers with an
        // empty stack when there is nothing to hand back, where the deprecated item-only getter answers
        // with a null.
        ItemStack remainder = held.getCraftingRemainingItem();
        held.shrink(1);
        if (held.isEmpty())
            player.setItemInHand(hand, remainder);
        else if (!remainder.isEmpty() && !player.getInventory()
            .add(remainder))
            player.drop(remainder, false);
    }
}
