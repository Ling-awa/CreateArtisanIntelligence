package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.compat.ToolProcess;
import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.config.RecipeFilter;
import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.item.SawFilterSlotItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.saw.CuttingRecipe;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.foundation.item.ItemHelper;
import com.simibubi.create.foundation.recipe.RecipeConditions;
import com.simibubi.create.foundation.recipe.RecipeFinder;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * What the handheld mechanical saw does to a depot: one cutting cycle, written against Create's own
 * {@link DepotBlockEntity} rather than against any particular block, so the action follows the block entity
 * and {@code create:depot} is the only thing it can land on. This mod's processing table used to be a depot
 * and used to be a target here; it is a plain workbench now.
 *
 * <p>Nothing here invents a recipe judgment. The recipe a stroke would run is found exactly the way
 * {@code SawBlockEntity#getRecipes} finds it — a sequenced-assembly cutting step first, then Create's own
 * recipe search over the cutting type (and stonecutting, when Create's server config allows it), with the
 * same three filters the saw applies: the filter slot accepts the output, the first ingredient accepts the
 * input, and the recipe is not one automation should leave alone. The results are rolled with
 * {@code CuttingRecipe#rollResults}, which is where a recipe's chanced outputs are decided, and routed
 * through the depot's own {@link TransportedItemStackHandlerBehaviour}. A filter that has been toggled off
 * is handed to that search as an empty one, so every recipe the input matches is on offer again while the
 * filter item itself stays in the slot.
 *
 * <p>Two differences from the machine, both deliberate. It lands at once: a saw takes time only because
 * it is driven by a shaft, and a hand tool is not, so a hold gets the recipe once. And the place in the
 * list — which of several matching recipes this stroke takes — lives <em>on the item</em>
 * ({@link SawFilterSlotItem#recipeIndex}) rather than in a block entity field, so repeated holds cycle
 * through a multi-output recipe's results exactly as the saw's own {@code RecipeIndex} does.
 *
 * <p><b>One stroke, one item.</b> A stroke spends exactly one item of the stack and produces that recipe's
 * result for one item; whatever is left of the stack stays where it was, on the depot. That is Create's
 * own single-item behavior, which is what its saw does when {@code bulkCutting} is off — the setting is
 * false by default, and false is what {@code SawBlockEntity}'s constructor turns into a one-item slot
 * limit on its {@code ProcessingInventory} — and it is the behavior a hand tool wants in any case: the
 * saw also reads the config, so a player who turns bulk cutting on for their machines does not have to
 * expect a lever to eat a chest of logs. A recipe that yields more than one item per input keeps its
 * whole result: {@code 1x oak_planks} under a filter for {@code oak_slab} still gives back
 * {@code 2x oak_slab}, because that count is the recipe's, not a stack's.
 *
 * <p>A recipe's result is produced here and handed to the depot as ordinary cargo, so products take the
 * item's place and anything past the depot's eight output slots is dropped where the depot stands — the
 * same place the depot itself puts what it cannot hold.
 */
public final class SawToolActions {

    /**
     * The cache key of the recipe search, the same idiom the saw uses: one object per search site, so the
     * first search of the cutting type is remembered and the next one starts from it. The saw keeps one
     * key in a static field that every saw shares, which is safe because the key stands only for "the
     * recipes of this type", never for any one machine's input.
     */
    private static final Object CUTTING_RECIPES_KEY = new Object();

    private SawToolActions() {
    }

    // --- cutting ---------------------------------------------------------------------------------

    /**
     * Whether a stroke would do something: an item on the depot that one of the cuttable recipes applies
     * to, under the filter the saw carries — or under no filter at all, when the filter has been toggled
     * off and the search is answering with everything the input matches.
     *
     * <p>Null-tolerant on purpose: "there is no depot" is an ordinary answer here.
     */
    public static boolean canCut(@Nullable DepotBlockEntity depot, ItemStack saw) {
        if (depot == null || saw.isEmpty())
            return false;
        Level level = depot.getLevel();
        ItemStack input = depot.getHeldItem();
        return level != null && !input.isEmpty() && !cuttable(level, saw, input).isEmpty();
    }

    /**
     * The recipes a saw is still allowed to run, in the order the saw's filter put them in.
     *
     * <p>Where the filter bites: a recipe a blacklist names is not among the candidates, so the saw over it
     * behaves as it does over an item nothing applies to, and the products it would otherwise have handed out
     * in turn are never reached.
     */
    private static List<RecipeHolder<? extends Recipe<?>>> cuttable(Level level, ItemStack saw, ItemStack input) {
        return RecipeFilter.allowed(level, recipesFor(level, saw, input), ToolProcess.SAWING);
    }

    /**
     * Cuts whatever is on the depot once, at once.
     *
     * <p>Either depot, and the same instant either way. One item of the stack is spent and the products
     * for that one item take its place; the rest of the stack stays on the depot, which is what the saw
     * does when bulk cutting is off.
     *
     * @return whether a recipe ran
     */
    public static boolean cutWith(@Nullable DepotBlockEntity depot, ItemStack saw) {
        if (depot == null)
            return false;
        Level level = depot.getLevel();
        if (level == null || level.isClientSide || !canCut(depot, saw))
            return false;
        TransportedItemStackHandlerBehaviour handler = depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
        if (handler == null)
            return false;

        // The recipe is chosen before the depot is touched, because the choice advances the place in the
        // list on the saw itself and because "none matched" must leave the depot exactly as it was.
        List<RecipeHolder<? extends Recipe<?>>> recipes = cuttable(level, saw, depot.getHeldItem());
        if (recipes.isEmpty())
            return false;
        int index = SawFilterSlotItem.recipeIndex(saw);
        if (index >= recipes.size())
            index = 0;
        Recipe<?> recipe = recipes.get(index)
            .value();
        // The saw's own place in its cycle: start() steps the index before applyRecipe() runs, so the next
        // stroke answers with the next recipe of the list.
        SawFilterSlotItem.advanceRecipeIndex(saw, recipes.size());

        boolean[] ran = { false };
        handler.handleProcessingOnAllItems(transported -> cutOne(level, transported, recipe, ran));
        if (!ran[0])
            return false;

        depot.notifyUpdate();
        return true;
    }

    /**
     * One item of the stack cut, and the rest of it left where it was.
     *
     * <p>The depot's own contract decides the shape of this: a result is applied by discarding the
     * incoming stack outright, so "leave the remainder" is not "return it how it was" — it is the
     * {@code heldOutput} of {@code TransportedResult.convertToAndLeaveHeld}, which the depot puts back on
     * its center slot while the products go to its output buffer. The single item that was cut is the one
     * the products stand for, which is what Create's own saw does when bulk cutting is off.
     *
     * <p>A stack of one item has nothing to leave, so it is replaced outright — the same call with no
     * held output, which is what the depot expects and what keeps an emptied center slot empty.
     *
     * @return null for "leave the item alone", which the depot's own handler treats as no change
     */
    @Nullable
    private static TransportedResult cutOne(Level level, TransportedItemStack transported, Recipe<?> recipe,
                                            boolean[] ran) {
        ItemStack input = transported.stack;
        List<ItemStack> results = resultsOf(level, input, recipe);
        if (results.isEmpty())
            return null;
        ran[0] = true;

        List<TransportedItemStack> products = products(level, transported, results);
        if (input.getCount() <= 1)
            return TransportedResult.convertTo(products);

        TransportedItemStack remainder = transported.copy();
        remainder.stack = input.copyWithCount(input.getCount() - 1);
        return TransportedResult.convertToAndLeaveHeld(products, remainder);
    }

    /**
     * The items one stroke produces, mirroring {@code SawBlockEntity#applyRecipe} roll for roll: a cutting
     * recipe has its outputs rolled — that is where a chanced result is decided — while a stonecutting
     * recipe (or a modded woodcutting one) is a single plain output. What each cut leaves behind is added
     * after each roll, exactly as the saw adds it.
     *
     * <p>One roll, because one item is cut. Create's {@code applyRecipe} rolls {@code input.getCount()}
     * times, which is what spends a whole stack when bulk cutting is on; the roll of a single item is the
     * first of those, with the {@code CraftingRemainingItem} a cut is owed along with it.
     */
    static List<ItemStack> resultsOf(Level level, ItemStack input, Recipe<?> recipe) {
        List<ItemStack> list = new ArrayList<>();
        RandomSource random = level.random;
        List<ItemStack> rolled;
        if (recipe instanceof CuttingRecipe cutting)
            rolled = cutting.rollResults(random);
        else
            rolled = List.of(recipe.getResultItem(level.registryAccess())
                .copy());
        for (ItemStack stack : rolled) {
            if (!stack.isEmpty())
                ItemHelper.addToList(stack.copy(), list);
        }
        ItemStack remainder = input.getCraftingRemainingItem();
        if (!remainder.isEmpty())
            ItemHelper.addToList(remainder, list);
        return list;
    }

    /**
     * The result stacks as depot cargo: one {@link TransportedItemStack} per product, angled the way the
     * depot angles a placed item.
     */
    private static List<TransportedItemStack> products(Level level, TransportedItemStack transported,
                                                      List<ItemStack> results) {
        List<TransportedItemStack> products = new ArrayList<>(results.size());
        for (ItemStack stack : results) {
            TransportedItemStack product = transported.copy();
            product.stack = stack;
            product.locked = true;
            product.angle = BeltHelper.isItemUpright(stack) ? 180 : level.random.nextInt(360);
            products.add(product);
        }
        return products;
    }

    // --- the recipes, exactly as the saw finds them ----------------------------------------------

    /**
     * The recipes this input has under this filter, in the saw's own order and through the saw's own
     * filters. Mirrors {@code SawBlockEntity#getRecipes()}.
     *
     * <p>One step is not mirrored, and cannot be: the saw reads its own inventory, so where
     * {@code RecipeConditions.firstIngredientMatches} is handed the item a saw is about to cut, the item a
     * stroke is about to cut is handed over instead. The test is the same test; only the place the input
     * comes from differs.
     */
    public static List<RecipeHolder<? extends Recipe<?>>> recipesFor(Level level, ItemStack saw, ItemStack input) {
        if (level == null || input.isEmpty())
            return List.of();

        // 1) A sequenced-assembly cutting step outranks a plain recipe, and is used when the filter
        // accepts its result — the saw checks the filter here, before its search, and so does this.
        Optional<RecipeHolder<CuttingRecipe>> assembly =
            SequencedAssemblyRecipe.getRecipe(level, input, AllRecipeTypes.CUTTING.getType(), CuttingRecipe.class);
        if (assembly.isPresent() && SawFilterSlotItem.accepts(level, saw,
            assembly.get()
                .value()
                .getResultItem(level.registryAccess())))
            return List.of(assembly.get());

        // 2) Create's own search, over the cutting type and — when Create's server config says a saw may
        // run them — stonecutting recipes too. The flag is read from Create and never duplicated: a player
        // who turns stonecutting off on saws turns it off here as well.
        Predicate<RecipeHolder<? extends Recipe<?>>> types = RecipeConditions.isOfType(AllRecipeTypes.CUTTING.getType(),
            AllConfigs.server().recipes.allowStonecuttingOnSaw.get() ? RecipeType.STONECUTTING : null);
        List<RecipeHolder<? extends Recipe<?>>> found = RecipeFinder.get(CUTTING_RECIPES_KEY, level, types);

        // 3) The saw's three filters. outputMatchesFilter is RecipeConditions' own test, written against a
        // FilteringBehaviour; a tool has no behaviour, so its meaning is replicated — the filter's own test
        // against the recipe's result item, which is what RecipeConditions does — and a multi-output recipe
        // is judged by its first output on both.
        return found.stream()
            .filter(r -> SawFilterSlotItem.accepts(level, saw, r.value()
                .getResultItem(level.registryAccess())))
            .filter(RecipeConditions.firstIngredientMatches(input))
            .filter(r -> !AllRecipeTypes.shouldIgnoreInAutomation(r))
            .toList();
    }
}
