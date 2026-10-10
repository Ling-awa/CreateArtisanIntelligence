package io.github.Ling.create_ai.table;

import io.github.Ling.create_ai.block.ProcessingTableBlockEntity;
import io.github.Ling.create_ai.compat.ToolProcess;
import io.github.Ling.create_ai.config.RecipeFilter;

import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingInput;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.kinetics.crafter.RecipeGridHandler;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * What the workbench's layout adds up to: the recipe it matches, the product, and how many ingredients the
 * wrench has to install.
 *
 * <p>Nothing about the matching is re-implemented. The layout is turned into Create's own sparse grid
 * ({@code RecipeGridHandler.GroupedItems}) and then into Create's own crafting input
 * ({@link MechanicalCraftingInput}), which is the thing a mechanical-crafting recipe insists on — its
 * {@code matches} refuses any other {@code CraftingInput} outright. The lookups that follow are the two
 * {@code RecipeGridHandler.tryToApplyRecipe} makes, in the same order and with the same filter: the vanilla
 * crafting recipe table first, if the crafter is allowed to use it, and then
 * {@code create:mechanical_crafting}. So a three-by-three recipe and a nine-by-nine one are the same
 * question here, exactly as they are to a real mechanical crafter.
 *
 * <p>The one thing this asks that the machine does not is the <em>count</em>: the number of non-empty
 * ingredients in the pattern, which is how many wrench clicks the assembly takes. Read from the recipe's own
 * ingredient list, so a pattern with holes in it counts only the cells it actually fills, and the order a
 * player would read it in is the order that list is in.
 *
 * <p>{@code GroupedItems}' own grid field is package-private, so the grid does not go in directly: it goes in
 * as the tag Create writes and reads ({@link ProcessingTableBlockEntity#gridTag}), through
 * {@code GroupedItems.read}, which is public. That is the one door into the class and it is the one used.
 */
public final class TableCrafting {

    /**
     * A layout that matches something.
     *
     * @param recipe          what matched, for the record and for anything that wants to name it
     * @param result          what it produces, rolled as the recipe rolls it
     * @param ingredientCount how many cells of the layout the pattern fills, which is how many wrench
     *                        clicks the assembly takes
     */
    public record Match(Recipe<?> recipe, ItemStack result, int ingredientCount) {
    }

    private TableCrafting() {
    }

    /**
     * The recipe this table's layout matches, or null when it matches nothing.
     *
     * <p>Null is an ordinary answer here — an empty table, a half-built layout and a layout nothing matches
     * are all the same thing to the caller, which shows the warning rather than assembling.
     */
    @Nullable
    public static Match match(ProcessingTableBlockEntity table) {
        Level level = table.getLevel();
        if (level == null || table.cells()
            .isEmpty())
            return null;

        MechanicalCraftingInput input = inputOf(table, level.registryAccess());
        if (input == null)
            return null;
        RegistryAccess access = level.registryAccess();

        // The vanilla table first, if this pack lets a crafter use it — Create's own order and Create's own
        // filter, so a layout that a mechanical crafter would refuse is refused here too.
        if (AllConfigs.server().recipes.allowRegularCraftingInCrafter.get()) {
            Optional<RecipeHolder<CraftingRecipe>> crafting =
                level.getRecipeManager()
                    .getRecipeFor(RecipeType.CRAFTING, input, level);
            if (crafting.isPresent() && RecipeGridHandler.isRecipeAllowed(crafting.get(), input)
                && RecipeFilter.allows(level, crafting.get(), ToolProcess.MECHANICAL_CRAFTING))
                return toMatch(crafting.get()
                    .value(), input, access);
        }

        Optional<RecipeHolder<MechanicalCraftingRecipe>> mechanical =
            AllRecipeTypes.MECHANICAL_CRAFTING.find(input, level);
        return mechanical.filter(holder -> RecipeFilter.allows(level, holder, ToolProcess.MECHANICAL_CRAFTING))
            .map(holder -> toMatch(holder.value(), input, access))
            .orElse(null);
    }

    /** The table's grid, as the crafting input every basin-style matcher wants. */
    @Nullable
    private static MechanicalCraftingInput inputOf(ProcessingTableBlockEntity table, HolderLookup.Provider registries) {
        RecipeGridHandler.GroupedItems items =
            RecipeGridHandler.GroupedItems.read(table.gridTag(registries), registries);
        // Create's own grid has to be measured before it can be turned into an input: the conversion reads
        // the bounding box the statistics hold, and an unmeasured grid has a width and height of zero.
        items.calcStats();
        if (items.onlyEmptyItems())
            return null;
        return MechanicalCraftingInput.of(items);
    }

    private static <I extends CraftingInput> Match toMatch(Recipe<I> recipe, I input, RegistryAccess access) {
        return new Match(recipe, recipe.assemble(input, access), ingredientCount(recipe));
    }

    /**
     * How many cells of the pattern hold something: the number of clicks a wrench takes.
     *
     * <p>Counted over the recipe's own ingredients, in the order the pattern lists them — top row first, left
     * to right — so a pattern's holes are not clicks and its filled cells are, in the order a player reading
     * the recipe would touch them.
     */
    private static int ingredientCount(Recipe<?> recipe) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int count = 0;
        for (Ingredient ingredient : ingredients)
            if (!ingredient.isEmpty())
                count++;
        return count;
    }

    /** The cells a layout holds, for a caller that wants to walk them in a stable order. */
    public static List<Pair<Integer, Integer>> occupied(ProcessingTableBlockEntity table) {
        return List.copyOf(table.cells()
            .keySet());
    }
}
