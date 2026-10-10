package io.github.Ling.create_ai.compat;

import io.github.Ling.create_ai.CreateAI;

import java.util.List;
import java.util.function.Supplier;

import com.simibubi.create.AllRecipeTypes;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * The kinds of processing one of this mod's hand tools stands in for.
 *
 * <p>This is the table the whole JEI compatibility hangs off. Each kind carries three things: the name a
 * filter entry's {@code tool} or {@code type} can use, the JEI categories this mod's items are added to, and
 * the items of this mod that belong there — a catalyst, in JEI's sense, meaning "show this item on the
 * recipe's page as one of the things that can make it".
 *
 * <p><b>Why the categories are named by id rather than by recipe type.</b> A catalyst is recorded against a
 * JEI recipe type, and Create's categories do not use vanilla's: its fan pages are keyed by Create's own
 * {@code create:fan_blasting} and friends rather than by {@code minecraft:blasting}, and its saw has a page
 * of its own rather than borrowing the vanilla stonecutter. Registering against the vanilla types therefore
 * puts this mod's tools on <em>vanilla's</em> pages and leaves Create's pages empty — which is exactly what
 * the first attempt at this did. Both sets are registered now: Create's pages, which is what a player looking
 * up a Create machine expects to find, and the plain recipe types, which is what the pages belong to when a
 * pack has turned Create's off. {@link io.github.Ling.create_ai.compat.CreateAiJeiPlugin} merges the two
 * before registering, so a recipe type whose registry id is a page's own id is named once rather than twice.
 *
 * <p>The recipe types are held as suppliers and the items as registry objects, so nothing here touches a
 * registry or a recipe type until it is asked. That matters because JEI's plugin is built long after the mod's
 * own registration, and a table built eagerly at class-init time would be reading Create's registries during
 * mod construction, which is the trap this mod has already been bitten by once.
 */
public enum ToolProcess {

    /** The fan's bulk processing: what an encased fan does to items blown past a catalyst block. */
    FAN("fan", List.of("create:fan_blasting", "create:fan_smoking", "create:fan_washing", "create:fan_haunting"),
        List.of(() -> RecipeType.BLASTING, () -> RecipeType.SMELTING, () -> RecipeType.SMOKING,
            () -> AllRecipeTypes.SPLASHING.getType(), () -> AllRecipeTypes.HAUNTING.getType()),
        List.of(CreateAI.HANDHELD_FAN)),

    /**
     * A basin being mixed. Its two derived pages are the mixer's as well: a basin being stirred through a
     * shapeless crafting recipe, and a heated basin brewing a potion. Both are recipes the rod runs
     * (see {@code BasinToolActions}, which reads the mixer's own shapeless rule and Create's potion
     * generator), so both pages carry the rod.
     */
    MIXING("mixing",
        List.of("create:mixing", "create:automatic_shapeless", "create:automatic_brewing"),
        List.of(() -> AllRecipeTypes.MIXING.getType()),
        List.of(CreateAI.STIRRING_ROD)),

    /** A belt or depot being cut, and the saw's own block-cutting page. */
    SAWING("sawing", List.of("create:sawing", "create:block_cutting"),
        List.of(() -> AllRecipeTypes.CUTTING.getType(), () -> RecipeType.STONECUTTING),
        List.of(CreateAI.HANDHELD_MECHANICAL_SAW)),

    /** A depot being pressed. */
    PRESSING("pressing", List.of("create:pressing"), List.of(() -> AllRecipeTypes.PRESSING.getType()),
        List.of(CreateAI.HAMMER, CreateAI.OBSIDIAN_HAMMER)),

    /**
     * A basin being compressed: what a press does to a basin's contents, which a hammer does by hand.
     *
     * <p>Two pages, because a press over a basin has two: {@code create:packing} for the compacting
     * recipes themselves, and {@code create:automatic_packing} for the crafting recipes whose ingredients
     * are all the same, which Create's press compresses as well. The hammer reads both — that square rule
     * is in {@code BasinToolActions}'s candidate filter — so both pages carry it. Note that the type's own
     * id is {@code create:compacting}, which is neither page: naming it registers nothing a player can see.
     */
    COMPACTING("compacting", List.of("create:packing", "create:automatic_packing"),
        List.of(() -> AllRecipeTypes.COMPACTING.getType()),
        List.of(CreateAI.HAMMER, CreateAI.OBSIDIAN_HAMMER)),

    /** A depot being filled by a spout, and drained by one. */
    FILLING("filling", List.of("create:spout_filling", "create:draining"),
        List.of(() -> AllRecipeTypes.FILLING.getType()), List.of(CreateAI.SPOUT_GUN)),

    /** An item being applied to another by a deployer — the goggles' own half of the mod. */
    DEPLOYING("deploying", List.of("create:deploying", "create:item_application"),
        List.of(() -> AllRecipeTypes.DEPLOYING.getType()), List.of(CreateAI.GOGGLES)),

    /**
     * A grid of items assembled into one. Two pages again, and for the same reason the crafter has two: the
     * {@code create:mechanical_crafting} recipes themselves, and {@code create:automatic_shaped}, which is
     * where a plain shaped crafting recipe is shown as something a crafter can make. The table looks its
     * layout up in the vanilla crafting table before it looks in Create's own type — see
     * {@code TableCrafting} — so the second page is one it fills too.
     */
    MECHANICAL_CRAFTING("mechanical_crafting",
        List.of("create:mechanical_crafting", "create:automatic_shaped"),
        List.of(() -> AllRecipeTypes.MECHANICAL_CRAFTING.getType()), List.of(CreateAI.PROCESSING_TABLE_ITEM)),

    /** A millstone grinding an item. */
    MILLING("milling", List.of("create:milling"), List.of(() -> AllRecipeTypes.MILLING.getType()),
        List.of(CreateAI.HAMMER)),

    /** Crushing wheels grinding an item to nothing. */
    CRUSHING("crushing", List.of("create:crushing"), List.of(() -> AllRecipeTypes.CRUSHING.getType()),
        List.of(CreateAI.OBSIDIAN_HAMMER));

    private final String id;
    private final List<String> jeiCategories;
    private final List<Supplier<RecipeType<?>>> recipeTypes;
    private final List<Supplier<? extends Item>> catalysts;

    ToolProcess(String id, List<String> jeiCategories, List<Supplier<RecipeType<?>>> recipeTypes,
                List<Supplier<? extends Item>> catalysts) {
        this.id = id;
        this.jeiCategories = jeiCategories;
        this.recipeTypes = recipeTypes;
        this.catalysts = catalysts;
    }

    /** The name a filter entry's {@code tool} or {@code type} can use, without a namespace. */
    public String id() {
        return id;
    }

    /** The JEI categories — Create's own — this kind of processing adds this mod's items to. */
    public List<String> jeiCategories() {
        return jeiCategories;
    }

    /** The recipe types this kind of processing covers, for the pages that are keyed by them. */
    public List<Supplier<RecipeType<?>>> recipeTypes() {
        return recipeTypes;
    }

    /** The items of this mod that are a catalyst for this kind of processing. */
    public List<Supplier<? extends Item>> catalysts() {
        return catalysts;
    }

    /** Whether a recipe type is one this kind of processing covers. */
    public boolean covers(RecipeType<?> type) {
        for (Supplier<RecipeType<?>> candidate : recipeTypes)
            if (candidate.get() == type)
                return true;
        return false;
    }

    /** Whether a name is this kind's id, or a {@code namespace:path} whose path is. */
    public boolean named(String name) {
        return id.equals(name) || id.equals(stripNamespace(name));
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}
