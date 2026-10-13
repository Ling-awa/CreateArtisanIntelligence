package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.compat.ToolProcess;
import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.config.RecipeFilter;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.fluids.potion.PotionMixingRecipes;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.kinetics.mixer.CompactingRecipe;
import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.recipe.RecipeFinder;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/**
 * What a tool does to a basin: what Create's own mechanical press does to one.
 *
 * <p>A press over a basin runs <em>compacting</em> recipes, and also crushes a crafting recipe whose
 * ingredients are all the same (four or nine of them) into its result. Both of those live in Create:
 * the candidate recipes come from its own filter ({@link MechanicalPressBlockEntity#matchStaticFilters}
 * is not public, so the filter below is that method line for line, using Create's own
 * {@link MechanicalPressBlockEntity#canCompress} and {@link AllRecipeTypes#shouldIgnoreInAutomation}),
 * the match and the application are {@link BasinRecipe}, and the recipe the press would pick is the
 * one using the most ingredients, which is how {@code BasinOperatingBlockEntity} sorts its list.
 *
 * <p>So a hammer held against a basin is the press's basin mode without the press: the same
 * judgment, the same recipe, the same sound, and the same item particles rising off what is in it.
 *
 * <p>Stirring is the same idea for the mixer: {@link #cannotMix} and {@link #stirTick} run the mixer's
 * recipes on the mixer's rhythm, and the basin's contents are stirred by the basin itself (see
 * {@link MixingCycle}).
 *
 * <p>Which recipes a tool runs is not worked out here. A basin machine's list is whatever its own
 * {@code getMatchingRecipes} answers, and an addon adds to that answer with a mixin rather than
 * declaring anything a search could find: the liquid-dye mixing of Create: Dragons Plus exists only
 * inside that method, its recipes built on the spot and registered nowhere. So both tools ask the
 * machine itself, through {@link BasinMachineRecipes}, and what is left here is reading the answer:
 * the match and the application are Create's own {@link BasinRecipe}, and the two by-hand fallbacks
 * ({@link #pressRecipesByHand} and {@link #mixerRecipesByHand}) cover the case where a machine cannot
 * be asked at all.
 */
public final class BasinToolActions {

    /** Our own cache key, so this lookup never shares entries with a real press's. */
    private static final Object COMPRESSING_RECIPES_KEY = new Object();

    /** And another for the mixer's, which are a different set of recipes entirely. */
    private static final Object MIXING_RECIPES_KEY = new Object();

    private BasinToolActions() {
    }

    @Nullable
    public static BasinBlockEntity basinAt(Level level, BlockPos pos) {
        return ToolTargets.at(level, pos, BasinBlockEntity.class);
    }

    @Nullable
    public static BasinBlockEntity targetOf(Player player) {
        return ToolTargets.lookingAt(player, BasinBlockEntity.class);
    }

    /**
     * Whether a press would do something here: a recipe the press can run matches what is in the
     * basin. Asking this before a hold starts means a hold that cannot succeed never begins.
     *
     * <p>Null-tolerant on purpose: "there is no basin" is a perfectly ordinary answer to this
     * question, and asking it about a basin that is not there must not throw.
     */
    public static boolean canPress(@Nullable BasinBlockEntity basin) {
        return basin != null && findRecipe(basin) != null;
    }

    /**
     * Applies what Create's press would apply to this basin.
     *
     * @return whether a recipe was applied
     */
    public static boolean pressWith(@Nullable BasinBlockEntity basin) {
        if (basin == null)
            return false;
        Level level = basin.getLevel();
        if (level == null || level.isClientSide || !basin.canContinueProcessing())
            return false;

        Recipe<?> recipe = findRecipe(basin);
        if (recipe == null || !RecipeFilter.allows(level, recipe, ToolProcess.COMPACTING))
            return false;
        if (!BasinRecipe.apply(basin, recipe))
            return false;

        // Create's press strike, at the volume and pitch a press on a belt would use.
        AllSoundEvents.MECHANICAL_PRESS_ACTIVATION.playOnServer(level, basin.getBlockPos(), .5f,
            .75f + (Config.pressStrikeSpeed() / 1024f));
        basin.notifyChangeOfContents();
        return true;
    }

    /**
     * The basin's input items, which a press throws as particles while its head comes down.
     */
    public static List<ItemStack> particleItems(@Nullable BasinBlockEntity basin) {
        List<ItemStack> items = new ArrayList<>();
        if (basin == null || basin.isEmpty())
            return items;
        for (int slot = 0; slot < basin.getInputInventory()
            .getSlots(); slot++) {
            ItemStack in = basin.getInputInventory()
                .getItem(slot);
            if (!in.isEmpty())
                items.add(in);
        }
        return items;
    }

    /**
     * The recipe the press would run on this basin, or null when none applies.
     *
     * <p>The press's own answer, asked of a press that is not there ({@link BasinMachineRecipes}) and
     * then taken in the order the press takes it: it sorts its list by ingredient count and processes
     * whatever ends up first. {@link #pressRecipesByHand} is the fallback for the case where the press
     * could not be asked.
     */
    @Nullable
    private static Recipe<?> findRecipe(BasinBlockEntity basin) {
        Level level = basin.getLevel();
        if (level == null || !basin.canContinueProcessing())
            return null;

        List<Recipe<?>> candidates = BasinMachineRecipes.pressOver(basin);
        if (candidates == null)
            candidates = pressRecipesByHand(level);

        for (Recipe<?> recipe : candidates)
            // Asked of the machine, the list has already been matched; read by hand, it has not. Testing
            // again costs one match and means one loop for both.
            if (BasinRecipe.match(basin, recipe))
                return recipe;
        return null;
    }

    /**
     * The press's candidate recipes, worked out by hand for the case where the press itself could not be
     * asked: {@code MechanicalPressBlockEntity#matchStaticFilters}, line for line, each tested with
     * {@link BasinRecipe#match}. Unlike the mixer's, this list needs the sort that the machine's own
     * lookup does, because the caller takes the first entry.
     */
    private static List<Recipe<?>> pressRecipesByHand(Level level) {
        List<Recipe<?>> recipes = new ArrayList<>();
        for (RecipeHolder<? extends Recipe<?>> holder : RecipeFinder.get(COMPRESSING_RECIPES_KEY, level,
            BasinToolActions::matchesPressFilters))
            recipes.add(holder.value());
        recipes.sort((first, second) -> second.getIngredients()
            .size()
            - first.getIngredients()
                .size());
        return recipes;
    }

    /**
     * {@code MechanicalPressBlockEntity#matchStaticFilters} — the filter behind
     * {@link #pressRecipesByHand}, which is only reached when the press itself could not be asked — plus
     * the class test that method's type test stands in for.
     *
     * <p>The order is the point. A recipe registered as {@code create:compacting} is taken exactly as
     * the press takes it. A {@link CompactingRecipe} under any other id is an addon's, which the press
     * would not have looked at at all, so the opt-out Create publishes for machines is honoured there.
     * The crafting branch is the press's own square-of-same-ingredients rule.
     */
    private static boolean matchesPressFilters(RecipeHolder<? extends Recipe<?>> recipe) {
        Recipe<?> r = recipe.value();
        if (r.getType() == AllRecipeTypes.COMPACTING.getType())
            return true;
        if (r instanceof CompactingRecipe)
            return !AllRecipeTypes.shouldIgnoreInAutomation(recipe);
        return r instanceof CraftingRecipe && !(r instanceof MechanicalCraftingRecipe)
            && MechanicalPressBlockEntity.canCompress(r)
            && !AllRecipeTypes.shouldIgnoreInAutomation(recipe);
    }

    // --- stirring, with Create's mixer's judgment -------------------------------------------------

    /**
     * Whether stirring would do nothing here: no basin, or nothing in it a mixing recipe applies to.
     * Null-tolerant, like the press's check, and phrased as the failure for the same reason the depot's
     * checks are.
     */
    public static boolean cannotMix(@Nullable BasinBlockEntity basin) {
        if (basin == null)
            return true;
        Level level = basin.getLevel();
        Recipe<?> recipe = mixingRecipe(basin);
        return level == null || recipe == null || !RecipeFilter.allows(level, recipe, ToolProcess.MIXING);
    }

    /**
     * One tick of stirring, as far as the basin is concerned: when the mixer's head reaches the bottom
     * it starts processing (and whistles if there is fluid in the tanks), and every processing pause
     * after that ends with the recipe landing.
     *
     * <p>Everything the mixer keeps in block-entity state — its cycle position and its processing
     * countdown — is a pure function of how long the stirring rod has been held, which is what the
     * caller passes in. See {@link MixingCycle}.
     *
     * @return whether a mixing operation landed on this tick, which is what the rod's own wear is charged
     *         on: a hold whose basin has nothing a recipe takes costs the rod nothing
     */
    public static boolean stirTick(Level level, @Nullable BasinBlockEntity basin, int elapsedTicks) {
        if (level == null || level.isClientSide || basin == null)
            return false;
        Recipe<?> recipe = mixingRecipe(basin);
        if (recipe == null || !RecipeFilter.allows(level, recipe, ToolProcess.MIXING))
            return false;

        int delay = MixingCycle.strikeDelay(recipe);
        if (MixingCycle.pauseStarts(elapsedTicks, delay) && hasFluid(basin))
            // Create's mixer, on noticing fluid as it starts a processing pause.
            level.playSound(null, basin.getBlockPos(), SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundSource.BLOCKS,
                .75f, 1.5f);

        return MixingCycle.strikes(elapsedTicks, delay) && mixWith(basin, recipe);
    }

    /**
     * Applies one mixing operation, the way the mixer's own {@code applyBasinRecipe} does.
     *
     * @return whether the basin took the recipe, which it will not if it cannot hold the products yet
     */
    private static boolean mixWith(BasinBlockEntity basin, Recipe<?> recipe) {
        if (!BasinRecipe.apply(basin, recipe))
            return false;
        basin.notifyChangeOfContents();
        return true;
    }

    /**
     * How long the mixer's head would hold at the bottom for what is in this basin right now, or 0
     * when nothing matches. The animation needs it, and it costs a recipe lookup, so callers cache it.
     */
    public static int mixingDelay(@Nullable BasinBlockEntity basin) {
        Recipe<?> recipe = basin == null ? null : mixingRecipe(basin);
        return recipe == null ? 0 : MixingCycle.strikeDelay(recipe);
    }

    private static boolean hasFluid(BasinBlockEntity basin) {
        for (SmartFluidTankBehaviour behavior : basin.getTanks())
            if (!behavior.getPrimaryHandler()
                .getFluid()
                .isEmpty())
                return true;
        return false;
    }

    /**
     * The recipe the mixer would run on this basin, or null when none applies.
     *
     * <p>The mixer's own answer, asked of a mixer that is not there ({@link BasinMachineRecipes}), taken
     * in the order the mixer takes it: it sorts its list by ingredient count, appends the potion brewing
     * it generated, and processes whatever ends up first. Asking is also what covers other mods, since
     * their recipes are reached through the mixer's own method and not through any recipe manager.
     * {@link #mixerRecipesByHand} is the fallback for the case where the mixer could not be asked.
     */
    @Nullable
    private static Recipe<?> mixingRecipe(BasinBlockEntity basin) {
        Level level = basin.getLevel();
        if (level == null || !basin.canContinueProcessing())
            return null;

        List<Recipe<?>> candidates = BasinMachineRecipes.mixerOver(basin);
        if (candidates == null)
            candidates = mixerRecipesByHand(basin, level);

        for (Recipe<?> recipe : candidates)
            // Asked of the machine, the list has already been matched; read by hand, it has not. Testing
            // again costs one match and means one loop for both.
            if (BasinRecipe.match(basin, recipe))
                return recipe;
        return null;
    }

    /**
     * The mixer's candidate recipes, worked out by hand for the case where the mixer itself could not be
     * asked: the recipes its own filter accepts, sorted by ingredient count the way the mixer sorts, and
     * with the potion brewing of {@link #potionRecipe} appended at the back — a registered recipe beats
     * a brewed one, which is what the mixer's own {@code recipes.get(0)} comes to.
     *
     * <p>What this cannot cover, and why asking the machine is the real path: recipes an addon appends
     * to {@code getMatchingRecipes} itself. Create: Dragons Plus's liquid-dye mixing is one of those.
     */
    private static List<Recipe<?>> mixerRecipesByHand(BasinBlockEntity basin, Level level) {
        List<Recipe<?>> recipes = new ArrayList<>();
        for (RecipeHolder<? extends Recipe<?>> holder : RecipeFinder.get(MIXING_RECIPES_KEY, level,
            BasinToolActions::matchesMixerFilters))
            recipes.add(holder.value());
        recipes.sort((first, second) -> second.getIngredients()
            .size()
            - first.getIngredients()
                .size());

        Recipe<?> potion = potionRecipe(basin, level);
        if (potion != null)
            recipes.add(potion);
        return recipes;
    }

    /**
     * The potion the mixer would brew out of this basin, or null when none of its contents is a
     * brewing ingredient that a potion recipe takes.
     *
     * <p>These are a family of mixing recipes no search can find, because Create does not register
     * them: {@link PotionMixingRecipes} builds them at runtime from the level's brewing tree,
     * one per potion mix, container mix and mod brewing recipe, and that — not any recipe in the
     * manager — is what lets a mixer over a heated basin holding a water bottle brew potions. So they
     * are read from that same generator, for the same items the mixer looks up: every stack in the
     * basin. What comes back is an ordinary {@code MixingRecipe} built by Create's own builder, so
     * {@link BasinRecipe#match} and {@link BasinRecipe#apply} handle it like any other: its heat
     * requirement, its fluid ingredient and its fluid result are all part of the recipe.
     *
     * <p>Reading the level's tree rather than a hardcoded potion list is what makes this cover other
     * mods: a mod adds brewing through NeoForge's brewing event, and {@code PotionBrewing} — the thing
     * the generator reads — is built from that event on both sides, so a modded potion is stirrable
     * for free, and the client reaches the same list as the server and so keeps agreeing with it about
     * whether a stir would do anything.
     *
     * <p>Create caches the generated list in a static, so after the first call this is a map lookup per
     * item in the basin plus a match test on the few recipes that item appears in. When the mixer can be
     * asked, its own answer already contains these; this is the by-hand path's way of getting at them.
     */
    @Nullable
    private static Recipe<?> potionRecipe(BasinBlockEntity basin, Level level) {
        if (!AllConfigs.server().recipes.allowBrewingInMixer.get())
            return null;

        Recipe<?> best = null;
        for (int slot = 0; slot < basin.getInputInventory()
            .getSlots(); slot++) {
            ItemStack in = basin.getInputInventory()
                .getItem(slot);
            if (in.isEmpty())
                continue;

            List<MixingRecipe> candidates = PotionMixingRecipes.sortRecipesByItem(level)
                .get(in.getItem());
            if (candidates == null)
                continue;
            for (MixingRecipe recipe : candidates)
                if (BasinRecipe.match(basin, recipe))
                    best = preferLarger(best, recipe);
        }
        return best;
    }

    @Nullable
    private static Recipe<?> preferLarger(@Nullable Recipe<?> best, Recipe<?> candidate) {
        if (best == null || candidate.getIngredients()
            .size() > best.getIngredients()
                .size())
            return candidate;
        return best;
    }

    /**
     * {@code MechanicalMixerBlockEntity#matchStaticFilters} — the filter behind
     * {@link #mixerRecipesByHand}, which is only reached when the mixer itself could not be asked — plus
     * the class test that method's type test stands in for: a {@link MixingRecipe} registered under some
     * other id is an addon's, which the mixer would not have looked at at all, so there the opt-out
     * Create publishes for machines (a serializer tagged {@code create:automation_ignore}, or an id
     * ending in {@code _manual_only}) is honoured.
     */
    private static boolean matchesMixerFilters(RecipeHolder<? extends Recipe<?>> recipe) {
        Recipe<?> r = recipe.value();
        if (r.getType() == AllRecipeTypes.MIXING.getType())
            return true;
        if (r instanceof MixingRecipe)
            return !AllRecipeTypes.shouldIgnoreInAutomation(recipe);
        return r instanceof CraftingRecipe && !(r instanceof ShapedRecipe)
            && AllConfigs.server().recipes.allowShapelessInMixer.get() && r.getIngredients()
                .size() > 1
            && !MechanicalPressBlockEntity.canCompress(r)
            && !AllRecipeTypes.shouldIgnoreInAutomation(recipe);
    }
}
