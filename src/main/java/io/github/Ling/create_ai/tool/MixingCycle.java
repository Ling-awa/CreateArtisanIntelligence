package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.config.Config;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;

import net.minecraft.util.Mth;
import net.minecraft.world.item.crafting.Recipe;

/**
 * The rhythm of a stirring action, copied from {@code MechanicalMixerBlockEntity}.
 *
 * <p>Create's mixer descends for twenty ticks, then sits at the bottom and processes. The processing
 * pause is its own {@code processingTicks} value, derived from the kinetic speed and the recipe's
 * processing duration, and the recipe lands when that countdown runs out. While the head is down, the
 * basin's contents are stirred — the mixer tells its basin that much with {@code
 * BasinBlockEntity#setAreFluidsMoving}, and that is what makes the items orbit and the fluid surface
 * swirl.
 *
 * <p>The part that decides how this feels: a mixer that still matches its recipe after applying it
 * keeps its head down and starts another pause instead of rising ({@code
 * continueWithPreviousRecipe} pins {@code runningTicks} at twenty). For a basin with a stack of
 * anything in it that matches, that means the head goes down once and stays there, stirring without
 * pause until there is nothing left to stir — it does not bob up and down once per application. So
 * neither does the rod: the contents move from the start of the hold while something is still there to
 * stir, and settle only when nothing matches any more.
 *
 * <p>A held stirring rod has no block entity to keep that state in, and does not need one: while the
 * use key is held the item knows how long it has been held, so the whole rhythm can be computed from
 * that single number plus whether the basin still has something to stir. That is also why the client
 * and the server always agree on when the contents are moving and when the recipe lands, with nothing
 * to synchronize.
 */
public final class MixingCycle {

    /**
     * How long the mixer's head takes to reach the bottom, where the processing pauses start from. Its
     * basin's contents move the whole way down — {@code running && runningTicks <= 20} — and then for
     * as long as the head stays there, so the caller does not need this to decide whether they move.
     *
     * <p>The twenty is the mixer's own anatomy rather than a rate, so it is not in the config; what a rod
     * stands in for is the speed the mixer runs at, which is ({@link Config#stirringSpeed()}).
     */
    public static final int DOWN_TICKS = 20;

    private MixingCycle() {
    }

    /**
     * How long the mixer sits at the bottom before the recipe is applied: {@code processingTicks} from
     * the mixer, verbatim, with the speed a held rod stands in for.
     */
    public static int strikeDelay(@Nullable Recipe<?> recipe) {
        float recipeSpeed = 1;
        if (recipe instanceof StandardProcessingRecipe<?> processing) {
            int duration = processing.getProcessingDuration();
            if (duration != 0)
                recipeSpeed = duration / 100f;
        }
        return Math.max((Mth.log2((int) (512 / Config.stirringSpeed()))) * Mth.ceil(recipeSpeed * 15) + 1, 1);
    }

    /** Ticks since the head reached the bottom. The pause-and-apply rhythm is counted from there. */
    private static int sinceBottom(int elapsed) {
        return elapsed - DOWN_TICKS;
    }

    /**
     * Whether this tick is one the recipe lands on. Each processing pause is {@code delay} ticks of
     * countdown plus the tick that starts the next one, so applications are {@code delay + 1} ticks
     * apart, the first one {@code delay} ticks after the head arrives.
     */
    public static boolean strikes(int elapsed, int delay) {
        int sinceBottom = sinceBottom(elapsed);
        return sinceBottom >= delay && Math.floorMod(sinceBottom, delay + 1) == delay;
    }

    /**
     * Whether a processing pause starts on this tick, which is when the mixer's countdown goes from
     * unset to set — as it arrives at the bottom, and again right after every application.
     */
    public static boolean pauseStarts(int elapsed, int delay) {
        int sinceBottom = sinceBottom(elapsed);
        return sinceBottom >= 0 && Math.floorMod(sinceBottom, delay + 1) == 0;
    }
}
