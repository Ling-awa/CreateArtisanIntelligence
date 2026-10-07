package io.github.Ling.create_ai.config;

import io.github.Ling.create_ai.compat.ToolProcess;
import io.github.Ling.create_ai.Create_ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

/**
 * The recipe blacklist and whitelist: which processing this mod's tools are allowed to be shown as a catalyst
 * for, and which they are kept out of.
 *
 * <p><b>An entry.</b> Each entry is one rule, written as a small object with up to five fields — {@code input},
 * {@code output}, {@code id}, {@code mod} and {@code type}:
 *
 * <pre>{@code
 * recipeCatalystBlacklist = ["{mod: \"some_mod\"}", "{output: \"/minecraft:.+/\"}"]
 * }</pre>
 *
 * A field that is not written places no condition, and the fields that are written must <em>all</em> hold —
 * which is what makes an entry a rule rather than five separate ones. Several entries are alternatives: the
 * list matches a recipe if any one entry does.
 *
 * <p><b>Regular expressions.</b> Every field may be a plain string, matched exactly, or a regular expression
 * written between slashes: {@code /minecraft:.+/} matches any id that starts with vanilla's namespace (and
 * {@code /.+minecraft:.+/} any id that merely contains it). A pattern that is not wrapped in slashes is
 * quoted, so an id containing regex punctuation is matched literally rather than exploding or matching
 * something else.
 *
 * <p><b>What each field is matched against.</b> {@code input} is every item id the recipe accepts and
 * {@code output} the item id it produces; {@code id} is the recipe's own id; {@code mod} is that id's
 * namespace; {@code type} is the kind of processing — either the name from {@link ToolProcess} or the id of one
 * of the recipe types that kind covers, so {@code type: "mixing"} and {@code type: "create:mixing"} are the
 * same rule. Fluid inputs and outputs are not matched: a recipe's items are what a filter entry is about, and
 * a fluid ingredient has no item id to name.
 *
 * <p><b>The mode</b> decides what a match means. {@code OFF} ignores both lists; {@code BLACKLIST} keeps the
 * recipes the list matches <em>out</em>, and {@code WHITELIST} keeps everything except the recipes it matches
 * <em>in</em>.
 */
public final class RecipeFilter {

    /** What the two lists mean. */
    public enum Mode {
        /** Neither list is read, and every recipe is allowed. */
        OFF,
        /** A recipe the list matches is refused; everything else is allowed. */
        BLACKLIST,
        /** A recipe the list matches is allowed; everything else is refused. */
        WHITELIST
    }

    private RecipeFilter() {
    }

    /**
     * One rule: the fields that were written, each already compiled.
     *
     * <p>A field is null when it was not written, and an entry with no fields at all matches nothing — an empty
     * entry is a mistake, and a mistake that silently matched every recipe in the game would be a bad one.
     */
    private record Entry(@Nullable Pattern input, @Nullable Pattern output, @Nullable Pattern id,
                 @Nullable Pattern mod, @Nullable Pattern type, @Nullable List<Pattern> tool) {

        boolean hasAnyField() {
            return input != null || output != null || id != null || mod != null || type != null || tool != null;
        }
    }

    /** Whether a recipe is allowed through the filter, for the kind of processing it is being considered as. */
    public static boolean allows(RecipeManager recipes, RegistryAccess registries, RecipeHolder<?> holder,
                                 ToolProcess process) {
        Mode mode = Config.recipeCatalystFilterMode();
        if (mode == Mode.OFF)
            return true;
        boolean listed = matches(holders(mode), recipes, registries, holder, process);
        return mode == Mode.WHITELIST ? listed : !listed;
    }

    /**
     * Whether <em>any</em> recipe of a kind of processing survives the filter.
     *
     * <p>This is the question JEI can be asked, and the reason it is asked rather than the per-recipe one: JEI
     * records a catalyst against a recipe <em>type</em>, not against a recipe, so a kind of processing either
     * has this mod's tool shown on its pages or it does not. A kind is registered when at least one of its
     * recipes is allowed — for a blacklist that means the list has not swallowed the whole kind, and for a
     * whitelist that the list has something to say about it.
     */
    public static boolean allowsAny(RecipeManager recipes, RegistryAccess registries, ToolProcess process) {
        Mode mode = Config.recipeCatalystFilterMode();
        if (mode == Mode.OFF)
            return true;

        List<Entry> entries = holders(mode);
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            if (!process.covers(holder.value()
                .getType()))
                continue;
            if (matches(entries, recipes, registries, holder, process))
                return mode == Mode.WHITELIST;
        }
        return mode == Mode.BLACKLIST;
    }

    // --- the same question asked from a hand tool, out in the world ---------------------------------

    /**
     * Whether a hand tool may run this recipe, asked with the level rather than with the recipe manager.
     *
     * <p>This is the gate every one of this mod's manual actions passes through, and it is the point of the
     * lists: a recipe a blacklist names cannot be worked by hand at all, and in whitelist mode only the ones a
     * whitelist names can. The machines are not gated anywhere — the brass powered saw and Create's own blocks
     * go on processing exactly as they did — which is what makes the filter a way to say "this has to be
     * automated" rather than a way to remove a recipe from the game.
     */
    public static boolean allows(Level level, RecipeHolder<?> holder, ToolProcess process) {
        return allows(level.getRecipeManager(), level.registryAccess(), holder, process);
    }

    /**
     * The same question, asked from a recipe a caller already holds rather than from its holder.
     *
     * <p>The recipe is looked up among the manager's to find the id the filter is written against, by identity:
     * the manager hands out the very objects it holds, so a recipe a caller is about to run is one of them. A
     * recipe that cannot be found is allowed — the filter speaks about what a recipe <em>is</em>, and it has
     * nothing to say about one whose id it cannot see.
     */
    public static boolean allows(Level level, @Nullable Recipe<?> recipe, ToolProcess process) {
        if (recipe == null)
            return true;
        for (RecipeHolder<?> holder : level.getRecipeManager()
            .getRecipes())
            if (holder.value() == recipe)
                return allows(level, holder, process);
        return true;
    }

    /**
     * The recipes of a list that may still be worked by hand, in the order they came in.
     *
     * <p>Every manual action in this mod works from a list of candidates and then takes one, so filtering the
     * list is the whole of the gate: what a blacklist names is simply not among the candidates, and the tool
     * then behaves as though the recipe did not exist — its "nothing to press" answer, its "nothing to cut"
     * answer and the feedback that goes with them all come out of the same place.
     */
    public static List<RecipeHolder<? extends Recipe<?>>> allowed(Level level,
                                                                 List<RecipeHolder<? extends Recipe<?>>> recipes,
                                                                 ToolProcess process) {
        if (Config.recipeCatalystFilterMode() == Mode.OFF)
            return recipes;
        List<RecipeHolder<? extends Recipe<?>>> kept = new ArrayList<>(recipes.size());
        for (RecipeHolder<? extends Recipe<?>> holder : recipes)
            if (allows(level, holder, process))
                kept.add(holder);
        return kept;
    }

    /**
     * Whether an item may still be worked by hand in this kind of processing.
     *
     * <p>Two of the actions — pouring fluid with the spout gun, and blowing an item with the handheld fan —
     * are handed to Create's own helpers, which look the recipe up inside themselves and hand back only an
     * answer. The recipe is still what the filter is about, so it is found here instead: the recipes of the
     * kind are walked and the ones whose ingredients take this item are asked. The item may be worked if any
     * of them survives, which is the answer the caller would have got by filtering the recipe itself.
     */
    public static boolean allowsItem(Level level, ToolProcess process, ItemStack item) {
        if (Config.recipeCatalystFilterMode() == Mode.OFF || item.isEmpty())
            return true;

        for (RecipeHolder<?> holder : level.getRecipeManager()
            .getRecipes()) {
            if (!process.covers(holder.value()
                .getType()))
                continue;
            if (!takesItem(holder, item))
                continue;
            if (allows(level, holder, process))
                return true;
        }
        return false;
    }

    /** Whether a recipe accepts an item, by any of its ingredients. */
    private static boolean takesItem(RecipeHolder<?> holder, ItemStack item) {
        for (Ingredient ingredient : holder.value()
            .getIngredients())
            if (!ingredient.isEmpty() && ingredient.test(item))
                return true;
        return false;
    }

    /** The list the current mode reads: the whitelist in whitelist mode, the blacklist otherwise. */
    private static List<Entry> holders(Mode mode) {
        List<String> raw = mode == Mode.WHITELIST ? Config.recipeCatalystWhitelist()
            : Config.recipeCatalystBlacklist();
        List<Entry> entries = new ArrayList<>(raw.size());
        for (String line : raw) {
            Entry entry = parse(line);
            if (entry != null && entry.hasAnyField())
                entries.add(entry);
        }
        return entries;
    }

    /** Whether any entry matches this recipe. */
    private static boolean matches(List<Entry> entries, RecipeManager recipes, RegistryAccess registries,
                                   RecipeHolder<?> holder, ToolProcess process) {
        for (Entry entry : entries)
            if (matches(entry, recipes, registries, holder, process))
                return true;
        return false;
    }

    /** Whether every field this entry wrote holds for this recipe. */
    private static boolean matches(Entry entry, RecipeManager recipes, RegistryAccess registries,
                           RecipeHolder<?> holder, ToolProcess process) {
        if (entry.id() != null && !entry.id()
            .matcher(holder.id()
                .toString())
            .matches())
            return false;

        if (entry.mod() != null && !entry.mod()
            .matcher(holder.id()
                .getNamespace())
            .matches())
            return false;

        if (entry.type() != null && !matchesType(entry.type(), process, holder))
            return false;

        if (entry.tool() != null && !matchesTool(entry.tool(), process))
            return false;

        if (entry.input() != null && !anyInputMatches(entry.input(), holder, registries))
            return false;

        if (entry.output() != null) {
            ItemStack result = holder.value()
                .getResultItem(registries);
            if (result.isEmpty() || !entry.output()
                .matcher(BuiltInRegistries.ITEM.getKey(result.getItem())
                    .toString())
                .matches())
                return false;
        }

        return true;
    }

    /** Whether the type field holds: the kind's own name, or the id of a recipe type the kind covers. */
    private static boolean matchesType(Pattern type, ToolProcess process, RecipeHolder<?> holder) {
        if (type.matcher(process.id())
            .matches())
            return true;

        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value()
            .getType());
        if (typeId == null)
            return false;
        // Both the whole id and its path, so `create:mixing` and `mixing` are the same rule and a pattern can
        // be written against whichever of the two a pack finds natural.
        return type.matcher(typeId.toString())
            .matches() || type.matcher(typeId.getPath())
                .matches();
    }


    /**
     * Whether the tool field holds: which tools this rule is about, named either by the function they would be
     * on the page for or by the tool itself.
     *
     * <p>Both ends name the same thing — {@code tool: "crushing"} is the obsidian hammer's crushing function,
     * {@code tool: "create_ai:obsidian_hammer"} is the hammer — and a rule holds if <em>any</em> of its names
     * do, so one line can cover several tools at once: milling is both hammers' function, and
     * {@code tool: ["milling", "crushing"]} is both hammers put together.
     */
    private static boolean matchesTool(List<Pattern> tools, ToolProcess process) {
        for (Pattern tool : tools) {
            if (tool.matcher(process.id())
                .matches())
                return true;
            for (Supplier<? extends Item> catalyst : process.catalysts()) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(catalyst.get());
                if (id != null && tool.matcher(id.toString())
                    .matches())
                    return true;
            }
        }
        return false;
    }

    /** Whether any item the recipe accepts has an id the pattern matches. */
    private static boolean anyInputMatches(Pattern input, RecipeHolder<?> holder, RegistryAccess registries) {
        for (Ingredient ingredient : holder.value()
            .getIngredients()) {
            if (ingredient.isEmpty())
                continue;
            for (ItemStack stack : ingredient.getItems()) {
                if (stack.isEmpty())
                    continue;
                if (input.matcher(BuiltInRegistries.ITEM.getKey(stack.getItem())
                    .toString())
                    .matches())
                    return true;
            }
        }
        return false;
    }

    // --- reading one entry -------------------------------------------------------------------------

    /**
     * One configured entry as a rule, or null when it cannot be read at all.
     *
     * <p>Two spellings are accepted, because both are natural to write and neither is worth arguing about: the
     * object form the config's comment shows, {@code {output: "/foo/"}}, and a bare list of pairs,
     * {@code output=/foo/}. Anything unreadable is dropped rather than throwing: a config typo should cost the
     * rule it is in, not the game's startup.
     */
    @Nullable
    static Entry parse(String line) {
        String text = line.trim();
        if (text.isEmpty())
            return null;

        if (text.startsWith("{")) {
            CompoundTag tag;
            try {
                tag = TagParser.parseTag(text);
            } catch (Exception e) {
                return null;
            }
            return new Entry(field(tag, "input"), field(tag, "output"), field(tag, "id"), field(tag, "mod"),
                field(tag, "type"), fields(tag, "tool"));
        }

        Pattern[] fields = new Pattern[5];
        String[] names = {"input", "output", "id", "mod", "type"};
        List<Pattern> tool = null;
        for (String pair : text.split(",")) {
            int equals = pair.indexOf('=');
            if (equals < 0)
                continue;
            String key = pair.substring(0, equals)
                .trim()
                .toLowerCase();
            String value = pair.substring(equals + 1)
                .trim();
            if (key.equals("tool")) {
                // Pipes rather than commas, because a comma is what separates the fields of a bare entry.
                tool = new ArrayList<>();
                for (String name : value.split("\\|")) {
                    Pattern pattern = compile(name);
                    if (pattern != null)
                        tool.add(pattern);
                }
                if (tool.isEmpty())
                    tool = null;
                continue;
            }
            for (int i = 0; i < names.length; i++)
                if (names[i].equals(key))
                    fields[i] = compile(value);
        }
        return new Entry(fields[0], fields[1], fields[2], fields[3], fields[4], tool);
    }

    @Nullable
    private static Pattern field(CompoundTag tag, String name) {
        if (!tag.contains(name))
            return null;
        String value = tag.getString(name);
        return value.isEmpty() ? null : compile(value);
    }

    /**
     * A field that may be written as one value or as a list of them.
     *
     * <p>{@code tool: "crushing"} and {@code tool: ["milling", "crushing"]} are both what a pack author would
     * reach for, so both are read; a single value is kept as a one-element list, which is what the matching
     * wants anyway.
     */
    @Nullable
    private static List<Pattern> fields(CompoundTag tag, String name) {
        if (!tag.contains(name))
            return null;

        List<Pattern> patterns = new ArrayList<>();
        if (tag.get(name) instanceof net.minecraft.nbt.ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Pattern pattern = compile(list.getString(i));
                if (pattern != null)
                    patterns.add(pattern);
            }
        } else {
            Pattern pattern = compile(tag.getString(name));
            if (pattern != null)
                patterns.add(pattern);
        }
        return patterns.isEmpty() ? null : patterns;
    }

    /**
     * A field as a pattern: the inside of {@code /.../} as a regular expression, and anything else quoted so it
     * matches itself.
     *
     * <p>A broken regular expression is compiled as a literal instead — the rule stops being what its author
     * meant, but it stops being nothing at all, and a half-written pattern in a config is not worth a crash.
     */
    @Nullable
    static Pattern compile(String value) {
        String text = value.trim();
        if (text.isEmpty())
            return null;
        if (text.length() > 1 && text.startsWith("/") && text.endsWith("/")) {
            try {
                return Pattern.compile(text.substring(1, text.length() - 1));
            } catch (PatternSyntaxException e) {
                return Pattern.compile(Pattern.quote(text));
            }
        }
        return Pattern.compile(Pattern.quote(text));
    }
}
