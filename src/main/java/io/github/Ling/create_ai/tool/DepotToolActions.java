package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.item.SpoutGunItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.fluids.spout.FillingBySpout;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.press.PressingRecipe;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.foundation.recipe.RecipeApplier;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * What a tool does to a depot: the press and the pour, written against Create's own
 * {@link DepotBlockEntity} rather than against any particular block, so the action follows the block
 * entity rather than a block id — which is what makes {@code create:depot} the only thing it can land on.
 * This mod's processing table used to be a depot and used to be a target here; it is a plain workbench now.
 *
 * <p>Nothing here duplicates Create's decisions. The pour asks {@code FillingBySpout} for its three
 * answers (can this item be filled, does this fluid fill it, how much), and the press reads the
 * press's recipe chain and routes products through the depot's own
 * {@code TransportedItemStackHandlerBehaviour} — the same call the depot's own {@code applyToAllItems}
 * makes for a belt-pressed item.
 *
 * <p>A press from a tool lands at once, on either depot. Create's own press takes time only because it
 * is driven by a shaft; a hammer is not, so it gets the recipe, the sound and the particles and none of
 * the waiting.
 */
public final class DepotToolActions {

    /** Same crush-particle count Create's mechanical press uses. */
    public static final int PRESS_PARTICLE_AMOUNT = 15;

    private DepotToolActions() {
    }

    // --- targets ---------------------------------------------------------------------------------

    /** Create's depot at a position, which is the only depot there is. */
    @Nullable
    public static DepotBlockEntity depotAt(Level level, BlockPos pos) {
        return ToolTargets.at(level, pos, DepotBlockEntity.class);
    }

    /**
     * The depot a ray from the player's eyes lands on, whatever it holds.
     *
     * <p>Re-checked when a hold completes, because the player may have looked or walked away in the
     * meantime.
     */
    @Nullable
    public static DepotBlockEntity targetOf(Player player) {
        return ToolTargets.lookingAt(player, DepotBlockEntity.class);
    }

    // --- pouring, with Create's spout's judgment -------------------------------------------------

    /**
     * Whether a pour with this gun would do something, decided by exactly the checks Create's spout
     * makes before it commits: the item must be fillable at all, this fluid must have a filling path
     * for it, and the amount that path asks for must be no more than the gun holds. Asking this
     * before a hold starts means a hold that cannot succeed never begins.
     *
     * <p>Null-tolerant on purpose: "there is no depot" is an ordinary answer here.
     */
    public static boolean canSpout(@Nullable DepotBlockEntity depot, ItemStack gun) {
        if (depot == null)
            return false;
        Level level = depot.getLevel();
        return level != null && requiredFluidFor(level, depot.getHeldItem(), SpoutGunItem.getFluid(gun)) != -1;
    }

    /**
     * Pours from the gun onto the item on the depot.
     *
     * <p>The fluid taken is worked out from what the fill actually consumed rather than from the
     * amount asked for, so the gun is debited by exactly what went into the item.
     *
     * <p>What it answers is whether anything was actually poured, which is what the gun's own wear is
     * charged on: a hold over an item the gun's fluid cannot fill costs nothing, the same way a hammer's
     * strike that found nothing to press costs nothing.
     */
    public static boolean spoutWith(@Nullable DepotBlockEntity depot, ItemStack gun) {
        if (depot == null)
            return false;
        Level level = depot.getLevel();
        if (level == null || level.isClientSide)
            return false;
        TransportedItemStackHandlerBehaviour handler = depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
        if (handler == null)
            return false;

        FluidStack available = SpoutGunItem.getFluid(gun);
        if (requiredFluidFor(level, depot.getHeldItem(), available) == -1)
            return false;

        int before = available.getAmount();
        handler.handleProcessingOnAllItems(transported -> spoutResult(level, transported, available));
        int used = before - available.getAmount();
        if (used <= 0)
            return false;

        SpoutGunItem.drain(gun, used);
        // The sound Create's spout makes, with its own volume and pitch jitter. The splash that goes
        // with it is raised by the client, which is the only side that can build fluid particles.
        AllSoundEvents.SPOUTING.playOnServer(level, depot.getBlockPos(), 0.75f,
            0.9f + 0.2f * level.getRandom()
                .nextFloat());
        depot.notifyUpdate();
        return true;
    }

    /**
     * The result half of {@code SpoutBlockEntity#whenItemHeld}, line for line: fill the item, keep
     * whatever is left of the stack on the depot and hand the filled item to Create's routing.
     *
     * <p>{@code FillingBySpout.fillItem} is what shrinks both the stack and {@code available}: it
     * consumes one input item and takes the fluid it needs, which is how the caller knows how much to
     * debit the gun.
     *
     * @return null for "leave the item alone", which {@code applyToAllItems} treats as no change
     */
    @Nullable
    private static TransportedResult spoutResult(Level level, TransportedItemStack transported, FluidStack available) {
        int required = requiredFluidFor(level, transported.stack, available);
        if (required == -1)
            return null;

        ItemStack filled = FillingBySpout.fillItem(level, required, transported.stack, available);
        if (filled.isEmpty())
            // A filling path that yields nothing: leave the item where it is, as the spout does.
            return null;

        TransportedItemStack product = transported.copy();
        product.stack = filled;
        TransportedItemStack remainder = transported.stack.isEmpty() ? null : transported.copy();
        return TransportedResult.convertToAndLeaveHeld(List.of(product), remainder);
    }

    /**
     * How much fluid the item needs for this particular fluid, or -1 when it cannot be filled with it
     * at all or needs more than is on offer.
     */
    private static int requiredFluidFor(Level level, ItemStack item, FluidStack available) {
        if (item.isEmpty() || available.isEmpty())
            return -1;
        if (!FillingBySpout.canItemBeFilled(level, item))
            return -1;
        int required = FillingBySpout.getRequiredAmountForItem(level, item, available.copy());
        return required > 0 && required <= available.getAmount() ? required : -1;
    }

    // --- pressing, with Create's press's recipe chain and routing --------------------------------

    /**
     * Whether a press with a tool would do nothing: nothing on the depot, or nothing a pressing recipe
     * applies to. Phrased as the failure so the callers, which all ask it before a strike, can read it
     * straight.
     *
     * <p>Null-tolerant on purpose: "there is no depot" is an ordinary answer here, and it is an answer
     * of "nothing to press".
     */
    public static boolean cannotPress(@Nullable DepotBlockEntity depot) {
        if (depot == null)
            return true;
        Level level = depot.getLevel();
        ItemStack cargo = depot.getHeldItem();
        return level == null || cargo.isEmpty() || pressRecipe(level, cargo).isEmpty();
    }

    /**
     * Presses whatever is on the depot once, at once.
     *
     * <p>Either depot, and the same instant either way: a tool is a hand tool, so the pacing a
     * mechanical press gets from its kinetic speed has nothing to apply to. Create's press activation
     * sound and its crush particles are shown from here and by the item that called it, which is what
     * makes poking an item with a hammer look like a press doing the job.
     *
     * @return whether the press ran
     */
    public static boolean pressWith(@Nullable DepotBlockEntity depot) {
        if (depot == null)
            return false;
        Level level = depot.getLevel();
        if (level == null || level.isClientSide || cannotPress(depot))
            return false;
        TransportedItemStackHandlerBehaviour handler = depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
        if (handler == null)
            return false;

        handler.handleProcessingOnAllItems(transported -> pressResult(depot, transported));
        playPressSound(level, depot.getBlockPos());
        depot.notifyUpdate();
        return true;
    }

    /**
     * Create's mechanical press activation, at the volume and pitch a press on a belt would use: the speed
     * a hand tool stands in for is {@link Config#pressStrikeSpeed()}, which is the default 256.
     *
     * <p>Shared with {@link HammerToolActions}, whose grind raises the same sound in the same place in its
     * sequence: a mill or a crusher standing in for a press should be heard as one, and a strike that found
     * nothing to do must stay silent — which is why every caller raises it only after its own change landed.
     */
    static void playPressSound(Level level, BlockPos pos) {
        AllSoundEvents.MECHANICAL_PRESS_ACTIVATION.playOnServer(level, pos, .5f,
            .75f + (Config.pressStrikeSpeed() / 1024f));
    }

    /**
     * The result half of Create's {@code BeltPressingCallbacks#whenItemHeld}, line for line.
     *
     * @return null for "leave the item alone", which {@code applyToAllItems} treats as no change
     */
    @Nullable
    static TransportedResult pressResult(DepotBlockEntity depot, TransportedItemStack transported) {
        Level level = depot.getLevel();
        if (level == null)
            return null;

        List<ItemStack> results = new ArrayList<>();
        if (!tryProcessOnBelt(level, transported, results))
            return null;

        boolean bulk = bulkPressing() || transported.stack.getCount() == 1;

        List<TransportedItemStack> collect = results.stream()
            .map(stack -> {
                TransportedItemStack copy = transported.copy();
                boolean centered = BeltHelper.isItemUpright(stack);
                copy.stack = stack;
                copy.locked = true;
                copy.angle = centered ? 180 : level.random.nextInt(360);
                return copy;
            })
            .toList();

        if (bulk) {
            if (collect.isEmpty())
                return TransportedResult.removeItem();
            return TransportedResult.convertTo(collect);
        }

        TransportedItemStack left = transported.copy();
        left.stack.shrink(1);
        if (collect.isEmpty())
            return TransportedResult.convertTo(left);
        return TransportedResult.convertToAndLeaveHeld(collect, left);
    }

    /**
     * The press's own extension point, implemented the way {@code MechanicalPressBlockEntity} does it:
     * read the recipe through the press's chain, then apply it with {@code RecipeApplier}.
     */
    private static boolean tryProcessOnBelt(Level level, TransportedItemStack input, List<ItemStack> outputList) {
        Optional<RecipeHolder<PressingRecipe>> recipe = pressRecipe(level, input.stack);
        if (recipe.isEmpty())
            return false;
        outputList.addAll(RecipeApplier.applyRecipeOn(level,
            bulkPressing() ? input.stack : input.stack.copyWithCount(1), recipe.get()
                .value(),
            true));
        return true;
    }

    /**
     * The press's recipe chain, verbatim: a sequenced-assembly step first, then the pressing type.
     * Mirrors {@code MechanicalPressBlockEntity#getRecipe}.
     */
    public static Optional<RecipeHolder<PressingRecipe>> pressRecipe(Level level, ItemStack item) {
        Optional<RecipeHolder<PressingRecipe>> assembly =
            SequencedAssemblyRecipe.getRecipe(level, item, AllRecipeTypes.PRESSING.getType(), PressingRecipe.class);
        if (assembly.isPresent())
            return assembly;
        return AllRecipeTypes.PRESSING.find(new SingleRecipeInput(item), level);
    }

    /** Create's own setting for whether one stroke eats one item or the whole stack. */
    public static boolean bulkPressing() {
        return AllConfigs.server().recipes.bulkPressing.get();
    }

    /** The depot behaviors a tool action needs; null when this is not a depot at all. */
    @Nullable
    public static DepotBehaviour behaviorOf(DepotBlockEntity depot) {
        return depot.getBehaviour(DepotBehaviour.TYPE);
    }
}
