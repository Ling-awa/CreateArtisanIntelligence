package io.github.Ling.create_ai;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
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
 * <p>Stirring is the same idea for the mixer: {@link #canMix} and {@link #stirTick} run the mixer's
 * recipes on the mixer's rhythm, and the basin's contents are stirred by the basin itself (see
 * {@link MixingCycle#contentsMoving}).
 */
public final class BasinToolActions {

    /** Our own cache key, so this lookup never shares entries with a real press's. */
    private static final Object COMPRESSING_RECIPES_KEY = new Object();

    /** And another for the mixer's, which are a different set of recipes entirely. */
    private static final Object MIXING_RECIPES_KEY = new Object();

    /** The pitch a press running at the speed a tool drives it would strike at. */
    private static final float TOOL_PRESS_SPEED = 256f;

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
        if (recipe == null)
            return false;
        if (!BasinRecipe.apply(basin, recipe))
            return false;

        // Create's press strike, at the volume and pitch a press on a belt would use.
        AllSoundEvents.MECHANICAL_PRESS_ACTIVATION.playOnServer(level, basin.getBlockPos(), .5f,
            .75f + (TOOL_PRESS_SPEED / 1024f));
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
     * <p>Mirrors {@code BasinOperatingBlockEntity#getMatchingRecipes}: the press's candidate recipes,
     * each tested with {@link BasinRecipe#match}, preferring the one with the most ingredients.
     */
    @Nullable
    private static Recipe<?> findRecipe(BasinBlockEntity basin) {
        Level level = basin.getLevel();
        if (level == null || !basin.canContinueProcessing())
            return null;

        List<RecipeHolder<? extends Recipe<?>>> candidates =
            RecipeFinder.get(COMPRESSING_RECIPES_KEY, level, BasinToolActions::matchesPressFilters);

        Recipe<?> best = null;
        for (RecipeHolder<? extends Recipe<?>> holder : candidates) {
            Recipe<?> recipe = holder.value();
            if (!BasinRecipe.match(basin, recipe))
                continue;
            if (best == null || recipe.getIngredients()
                .size() > best.getIngredients()
                    .size())
                best = recipe;
        }
        return best;
    }

    /** {@code MechanicalPressBlockEntity#matchStaticFilters}, line for line. */
    private static boolean matchesPressFilters(RecipeHolder<? extends Recipe<?>> recipe) {
        return (recipe.value() instanceof CraftingRecipe && !(recipe.value() instanceof MechanicalCraftingRecipe)
            && MechanicalPressBlockEntity.canCompress(recipe.value())
            && !AllRecipeTypes.shouldIgnoreInAutomation(recipe))
            || recipe.value()
                .getType() == AllRecipeTypes.COMPACTING.getType();
    }

    // --- stirring, with Create's mixer's judgment -------------------------------------------------

    /**
     * Whether stirring would do something here: a recipe the mixer can run matches what is in the
     * basin. Null-tolerant, like the press's check.
     */
    public static boolean canMix(@Nullable BasinBlockEntity basin) {
        return basin != null && mixingRecipe(basin) != null;
    }

    /**
     * One tick of stirring, as far as the basin is concerned: at the bottom of every cycle the mixer
     * starts processing (and whistles if there is fluid in the tanks), and once its processing pause
     * has run out the recipe lands.
     *
     * <p>Everything the mixer keeps in block-entity state — its cycle position and its processing
     * countdown — is a pure function of how long the stirring rod has been held, which is what the
     * caller passes in. See {@link MixingCycle}.
     */
    public static void stirTick(Level level, @Nullable BasinBlockEntity basin, int elapsedTicks) {
        if (level == null || level.isClientSide || basin == null)
            return;
        Recipe<?> recipe = mixingRecipe(basin);
        if (recipe == null)
            return;

        int delay = MixingCycle.strikeDelay(recipe);
        if (MixingCycle.cycleTick(elapsedTicks, delay) == MixingCycle.DOWN_TICKS && hasFluid(basin))
            // Create's mixer, on noticing fluid while it starts processing.
            level.playSound(null, basin.getBlockPos(), SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundSource.BLOCKS,
                .75f, 1.5f);

        if (MixingCycle.strikes(elapsedTicks, delay))
            mixWith(basin, recipe);
    }

    /** Applies one mixing operation, the way the mixer's own {@code applyBasinRecipe} does. */
    private static void mixWith(BasinBlockEntity basin, Recipe<?> recipe) {
        if (!BasinRecipe.apply(basin, recipe))
            return;
        basin.notifyChangeOfContents();
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
     * <p>Mirrors {@code MechanicalMixerBlockEntity}'s recipe lookup: the mixer's candidate recipes,
     * each tested with {@link BasinRecipe#match}, preferring the one with the most ingredients.
     *
     * <p>Deliberately <em>not</em> mirrored: the mixer's extra potion-brewing recipes
     * ({@code PotionMixingRecipes}, the ones Create generates from the vanilla brewing tree rather
     * than registering). No fixture built here could make even one of them match through Create's own
     * {@link BasinRecipe#match} — plain water with nether wart, which is exactly what the generated
     * ingredient asks for, still returns false — so the branch could not be verified, and an
     * unverified branch is worse than a documented gap. Stirring therefore covers the mixer's
     * registered recipes, which is everything in the recipe manager.
     */
    @Nullable
    private static Recipe<?> mixingRecipe(BasinBlockEntity basin) {
        Level level = basin.getLevel();
        if (level == null || !basin.canContinueProcessing())
            return null;

        Recipe<?> best = null;
        for (RecipeHolder<? extends Recipe<?>> holder : RecipeFinder.get(MIXING_RECIPES_KEY, level,
            BasinToolActions::matchesMixerFilters)) {
            Recipe<?> recipe = holder.value();
            if (!BasinRecipe.match(basin, recipe))
                continue;
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

    /** {@code MechanicalMixerBlockEntity#matchStaticFilters}, line for line. */
    private static boolean matchesMixerFilters(RecipeHolder<? extends Recipe<?>> recipe) {
        Recipe<?> r = recipe.value();
        return ((r instanceof CraftingRecipe && !(r instanceof ShapedRecipe)
            && AllConfigs.server().recipes.allowShapelessInMixer.get() && r.getIngredients()
                .size() > 1
            && !MechanicalPressBlockEntity.canCompress(r)) && !AllRecipeTypes.shouldIgnoreInAutomation(recipe)
            || r.getType() == AllRecipeTypes.MIXING.getType());
    }
}
