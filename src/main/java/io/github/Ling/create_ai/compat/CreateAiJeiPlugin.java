package io.github.Ling.create_ai.compat;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.config.RecipeFilter;
import io.github.Ling.create_ai.Create_ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.simibubi.create.AllRecipeTypes;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * What this mod shows in JEI: its hand tools, on the pages of the machines they stand in for.
 *
 * <p>This is JEI's catalyst mechanism and nothing else. A catalyst is the item JEI draws beside a recipe to
 * answer "what can make this?" — Create uses it for its machines, and a player who has this mod's tools but not
 * the machine should see the same recipes with the tool on them. The table of which tool goes with which kind
 * of processing is {@link ToolProcess}; a catalyst is recorded against a recipe <em>type</em>, so each kind
 * hands JEI its recipe types and the items this mod has that can do them.
 *
 * <p>The black and white lists are read here: a kind of processing whose recipes are all filtered out gets no
 * catalyst at all (see {@link RecipeFilter#allowsAny}). JEI has no way to put a catalyst on one recipe and not
 * its neighbour — a catalyst belongs to a type — so that is as finely as a filter can act on what JEI shows.
 * Where a filter entry names an individual recipe, it is the recipe's type that has to survive, which is the
 * distinction the config's comment and the filter's own documentation spell out.
 *
 * <p>Two things are deliberately outside the table. The brass powered saw is a saw in everything but name, so
 * it is registered for cutting alongside the handheld saw <em>unconditionally</em> — a list cannot take it away,
 * which is what "动力锯能进行的配方黄铜动力锯也是对应的配方催化剂" asks for. And nothing here touches Create's
 * own categories: JEI records catalysts by recipe type, so an item registered against a type appears in every
 * category that shows that type, Create's included.
 *
 * <p>Each kind is registered once per recipe type and no more. A type named twice is drawn twice — JEI keeps
 * the catalysts it is handed in a list and appends — so the two ways of naming a type this mod knows about
 * (Create's page, and the plain recipe type behind it) are merged into a set first, which is where they
 * collapse whenever they are really the same type. See {@link #jeiTypesOf}.
 *
 * <p>Client-only, and loaded by JEI rather than by this mod: the class is discovered by its {@code @JeiPlugin}
 * annotation when JEI is present, and never exists on a dedicated server.
 */
@JeiPlugin
public class CreateAiJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(Create_ai.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        // JEI builds its plugin when the client joins a world, so the recipe manager is there to be asked; if
        // it somehow is not, the filter cannot be applied and everything is registered, which is the answer
        // that shows a player something rather than nothing.
        ClientLevel level = Minecraft.getInstance().level;
        RecipeManager recipes = level == null ? null : level.getRecipeManager();
        RegistryAccess registries = level == null ? null : level.registryAccess();

        for (ToolProcess process : ToolProcess.values()) {
            if (recipes != null && !RecipeFilter.allowsAny(recipes, registries, process))
                continue;

            ItemStack[] catalysts = process.catalysts()
                .stream()
                .map(Supplier::get)
                .map(Item::getDefaultInstance)
                .toArray(ItemStack[]::new);

            // Create's own pages first: they are keyed by ids of Create's making, not by the recipe types the
            // recipes happen to be, so this is the registration that puts a tool where a player looks it up —
            // and then the plain recipe types, which is where the same recipes are shown when Create's pages
            // are not the ones on screen: vanilla's own blasting and smoking, the stonecutter, and so on.
            //
            // One registration per JEI recipe type, which is why the two lists are merged before they are
            // used. JEI keys catalysts by its own RecipeType and appends what it is handed (RecipeManagerInternal
            // collects them straight into a multimap), so naming one type twice draws the item twice — and the
            // two lists do name the same type wherever a recipe type's registry id is the page's own id. That
            // is the double hammer a player saw on the milling, pressing and crushing pages.
            for (mezz.jei.api.recipe.RecipeType<?> type : jeiTypesOf(process))
                registration.addRecipeCatalysts(type, catalysts);
        }

        // The brass saw, whatever the lists say: a saw in everything but name, on both of the saw's pages.
        registration.addRecipeCatalysts(createCategory("create:sawing"), brassSaw());
        registration.addRecipeCatalysts(createCategory("create:block_cutting"), brassSaw());
        registration.addRecipeCatalysts(jeiType(AllRecipeTypes.CUTTING.getType()), brassSaw());
        registration.addRecipeCatalysts(jeiType(RecipeType.STONECUTTING), brassSaw());
    }

    /**
     * The JEI recipe types one kind of processing is registered against: Create's own pages, then the plain
     * recipe types the same recipes belong to, with any two that name the same type collapsed into one.
     *
     * <p>A {@link LinkedHashSet} is the whole trick, and it works because of how JEI builds both sides. Its
     * recipe types are compared by id and recipe class, and {@code RecipeType.createFromVanilla} is nothing
     * but {@code createRecipeHolderType} over the vanilla type's registered id — so the type behind
     * {@code create:milling} and the {@code create:milling} page Create builds come out as the same entry
     * here, and only one registration goes to JEI. Where the ids differ there is nothing to collapse and both
     * are kept: Create's saw page and the vanilla stonecutter really are two pages.
     */
    private static List<mezz.jei.api.recipe.RecipeType<?>> jeiTypesOf(ToolProcess process) {
        Set<mezz.jei.api.recipe.RecipeType<?>> types = new LinkedHashSet<>();
        for (String category : process.jeiCategories())
            types.add(createCategory(category));
        for (Supplier<RecipeType<?>> type : process.recipeTypes())
            types.add(jeiType(type.get()));
        return List.copyOf(types);
    }

    private static ItemStack brassSaw() {
        return Create_ai.BRASS_MECHANICAL_SAW_ITEM.get()
            .getDefaultInstance();
    }

    /**
     * One of Create's JEI categories, as a handle to register a catalyst against.
     *
     * <p>Create builds these with {@code RecipeType.createRecipeHolderType(uid)} — a holder of recipes with an
     * id of its own making rather than a vanilla recipe type — and JEI compares two of them by id and recipe
     * class, so one built here is the same one Create's pages are keyed by.
     */
    private static mezz.jei.api.recipe.RecipeType<?> createCategory(String uid) {
        return mezz.jei.api.recipe.RecipeType.createRecipeHolderType(ResourceLocation.parse(uid));
    }

    /**
     * A vanilla recipe type as JEI's own handle on it.
     *
     * <p>JEI keys catalysts by its own {@code RecipeType}, which is a recipe class and an id rather than the
     * registry entry, and Create's categories build theirs from the same vanilla types with the same helper.
     */
    private static mezz.jei.api.recipe.RecipeType<?> jeiType(RecipeType<?> vanilla) {
        return mezz.jei.api.recipe.RecipeType.createFromVanilla(vanilla);
    }
}
