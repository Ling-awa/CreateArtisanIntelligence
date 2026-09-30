package io.github.Ling.create_ai;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.foundation.item.ItemHelper;
import com.simibubi.create.foundation.recipe.RecipeApplier;
import com.simibubi.create.foundation.recipe.RecipeConditions;
import com.simibubi.create.foundation.recipe.RecipeFinder;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * The two grinds a hammer has: the mill and the crush.
 *
 * <p>Neither is invented here. The mill is {@code create:milling}, the recipe type a
 * {@code MillstoneBlockEntity} runs; the crush is {@code create:crushing}, the one a
 * {@code CrushingWheelController} runs — and, when the item has no crushing recipe, the milling one, which
 * is what that controller asks for next. A crush therefore covers everything a mill does, and that is what
 * makes the obsidian hammer an upgrade of the plain hammer rather than a different tool. Each is found
 * through Create's own {@link RecipeFinder} over the machine's own recipe types — the same search a saw
 * does, narrowed the same way, by the recipe's type and by
 * {@code RecipeConditions#firstIngredientMatches}, so that only a recipe whose first ingredient is the
 * item on the depot is on offer. The recipe's result is worked out by {@link RecipeApplier}, which is
 * Create's own application of a processing recipe and the place where a chanced output is decided.
 *
 * <p><b>One strike, one item.</b> Exactly as {@link SawToolActions} spends one item per stroke, a
 * grind spends one item of the stack and produces that recipe's result for that one item; whatever is
 * left of the stack stays where it was, on the depot. That is the machine's own arithmetic for a
 * single input — a millstone feeds one item per cycle, and a crushing wheel pair grinds what it is
 * fed — and it is the behavior a hand tool wants in any case: a lever should not eat a chest of ore.
 *
 * <p>The products are handed to the depot as ordinary cargo through its own
 * {@link TransportedItemStackHandlerBehaviour}, so they take the item's place and anything past the
 * depot's eight output slots is dropped where the depot stands — the same place the depot itself puts
 * what it cannot hold.
 *
 * <p>What a grind does <em>not</em> do is gate itself on Create's automation flag. The hammer's press
 * does not read it either (see {@link DepotToolActions#pressRecipe}), and it is the wrong question
 * here: the recipes automation is kept away from are the ones an arm must not run by itself, and a
 * player working a depot by hand is not that arm. A recipe the item matches is a recipe the hammer
 * runs.
 */
public final class HammerToolActions {

    /**
     * The cache key of each grind's recipe search, one per kind — the same idiom the saw uses. A key
     * stands for "the recipes of this type" and never for any one depot's input, so one key may be
     * shared by every call.
     */
    private static final Object MILLING_RECIPES_KEY = new Object();
    private static final Object CRUSHING_RECIPES_KEY = new Object();

    private HammerToolActions() {
    }

    /**
     * Which of Create's two grinds a sneaking strike runs: the whole of the difference between the two
     * hammers, and the only thing the recipe search below is told.
     */
    public enum Grind {

        /** The millstone's recipe type, {@code create:milling}. */
        MILLING,

        /** The crushing wheels' recipe type, {@code create:crushing}. */
        CRUSHING;

        /**
         * This grind's first recipe type — the one its name is about.
         *
         * <p>Looked up when a strike runs rather than kept in a field: Create's recipe types are
         * registered, so a field would ask for one while this class is loading, which may be before the
         * register event has run.
         */
        public RecipeType<?> recipeType() {
            return this == MILLING ? AllRecipeTypes.MILLING.getType() : AllRecipeTypes.CRUSHING.getType();
        }

        /**
         * The recipe types a strike searches, in the order Create's own machine searches them.
         *
         * <p>The crushing wheels are why this is a list rather than one type.
         * {@code CrushingWheelControllerBlockEntity} asks for a {@code create:crushing} recipe and, finding
         * none, asks for a {@code create:milling} one — so a crusher does everything a millstone does and
         * more besides. The obsidian hammer has to behave the same way, or it would be a <em>worse</em> tool
         * than the plain hammer on everything that only mills, such as cobblestone. The millstone itself
         * asks for milling alone, which is why {@link #MILLING} stays a single type.
         */
        public List<RecipeType<?>> recipeTypes() {
            return this == CRUSHING
                ? List.of(AllRecipeTypes.CRUSHING.getType(), AllRecipeTypes.MILLING.getType())
                : List.of(AllRecipeTypes.MILLING.getType());
        }
    }

    // --- grinding --------------------------------------------------------------------------------

    /**
     * Whether a grind would do something: an item on the depot that a recipe of this grind's type
     * applies to.
     *
     * <p>Null-tolerant on purpose: "there is no depot" is an ordinary answer here.
     */
    public static boolean canGrind(@Nullable DepotBlockEntity depot, Grind grind) {
        if (depot == null)
            return false;
        Level level = depot.getLevel();
        ItemStack input = depot.getHeldItem();
        return level != null && !input.isEmpty() && !recipesFor(level, input, grind).isEmpty();
    }

    /**
     * Grinds whatever is on the depot once, at once.
     *
     * <p>Either depot, and the same instant either way: a hand tool has no kinetics, so there is no
     * cycle to wait out. One item of the stack is spent and the products for that one item take its
     * place; the rest of the stack stays on the depot.
     *
     * @return whether a recipe ran
     */
    public static boolean grindWith(@Nullable DepotBlockEntity depot, Grind grind) {
        if (depot == null)
            return false;
        Level level = depot.getLevel();
        if (level == null || level.isClientSide || !canGrind(depot, grind))
            return false;
        TransportedItemStackHandlerBehaviour handler = depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
        if (handler == null)
            return false;

        // The recipe is chosen before the depot is touched: "nothing matched" has to leave the depot
        // exactly as it was.
        List<RecipeHolder<? extends Recipe<?>>> recipes = recipesFor(level, depot.getHeldItem(), grind);
        if (recipes.isEmpty())
            return false;
        Recipe<?> recipe = recipes.get(0)
            .value();

        boolean[] ran = { false };
        handler.handleProcessingOnAllItems(transported -> grindOne(level, transported, recipe, ran));
        if (!ran[0])
            return false;

        // Create's press, raised through the same call the press path uses and in the same place in the
        // sequence, so a grind sounds like the press it is standing in for. A strike that found nothing to
        // do stays silent: the early returns above are all before this line.
        DepotToolActions.playPressSound(level, depot.getBlockPos());
        depot.notifyUpdate();
        return true;
    }

    /**
     * One item of the stack ground, and the rest of it left where it was.
     *
     * <p>The depot's own contract decides the shape of this: a result is applied by discarding the
     * incoming stack outright, so "leave the remainder" is not "return it how it was" — it is the
     * {@code heldOutput} of {@code TransportedResult.convertToAndLeaveHeld}, which the depot puts back
     * on its center slot while the products go to its output buffer. The single item that was ground is
     * the one the products stand for.
     *
     * <p>A stack of one item has nothing to leave, so it is replaced outright — the same call with no
     * held output, which is what the depot expects and what keeps an emptied center slot empty.
     *
     * @return null for "leave the item alone", which the depot's own handler treats as no change
     */
    @Nullable
    private static TransportedResult grindOne(Level level, TransportedItemStack transported, Recipe<?> recipe,
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
     * The items one strike produces.
     *
     * <p>{@link RecipeApplier} is asked to apply the recipe to a single item, which is Create's own
     * application of a processing recipe and the place a chanced output is rolled — one roll, because
     * one item is ground. What the input is owed as a crafting remainder is added after it, exactly as
     * the saw adds it.
     */
    public static List<ItemStack> resultsOf(Level level, ItemStack input, Recipe<?> recipe) {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack stack : RecipeApplier.applyRecipeOn(level, input.copyWithCount(1), recipe, true)) {
            if (!stack.isEmpty())
                ItemHelper.addToList(stack.copy(), list);
        }
        if (input.getItem()
            .hasCraftingRemainingItem())
            ItemHelper.addToList(new ItemStack(input.getItem()
                .getCraftingRemainingItem()), list);
        return list;
    }

    /**
     * The result stacks as depot cargo: one {@link TransportedItemStack} per product, angled the way
     * the depot angles a placed item.
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

    // --- the recipes, as Create's own machines find them -----------------------------------------

    /**
     * The recipes this input has under this grind, in Create's own order and after its own test.
     *
     * <p>The types are asked in the order the machine asks them, and the first that has anything wins: for
     * a crush that means {@code create:crushing} first and {@code create:milling} only when there is no
     * crushing recipe, exactly as {@code CrushingWheelControllerBlockEntity} does it. The search is
     * Create's {@link RecipeFinder}; the test is {@code RecipeConditions.firstIngredientMatches}, which is
     * the same test a saw applies to the item it is about to cut, and it is what keeps a recipe whose
     * ingredient is something else from being run on this item.
     */
    public static List<RecipeHolder<? extends Recipe<?>>> recipesFor(Level level, ItemStack input, Grind grind) {
        if (level == null || input.isEmpty())
            return List.of();
        for (RecipeType<?> type : grind.recipeTypes()) {
            List<RecipeHolder<? extends Recipe<?>>> found =
                RecipeFinder.get(recipesKey(type), level, holder -> holder.value()
                    .getType() == type);
            List<RecipeHolder<? extends Recipe<?>>> matching = found.stream()
                .filter(RecipeConditions.firstIngredientMatches(input))
                .toList();
            if (!matching.isEmpty())
                return matching;
        }
        return List.of();
    }

    /**
     * The key a search of one recipe type is remembered under.
     *
     * <p>Per type rather than per grind, because a crush searches two types and {@link RecipeFinder} caches
     * its result against the key it is handed: one key for both types would hand the milling search's list
     * back to the crushing one.
     */
    private static Object recipesKey(RecipeType<?> type) {
        return type == AllRecipeTypes.MILLING.getType() ? MILLING_RECIPES_KEY : CRUSHING_RECIPES_KEY;
    }
}
