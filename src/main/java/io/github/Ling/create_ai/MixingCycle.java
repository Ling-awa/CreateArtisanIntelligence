package io.github.Ling.create_ai;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;

import net.minecraft.util.Mth;
import net.minecraft.world.item.crafting.Recipe;

/**
 * The rhythm of a stirring action, copied from {@code MechanicalMixerBlockEntity}.
 *
 * <p>Create's mixer runs a 40-tick cycle: it travels down for twenty ticks, sits at the bottom while
 * the recipe is being processed, applies the recipe, then travels back up and starts again. How long
 * it sits there is the mixer's own {@code processingTicks} value, derived from the kinetic speed and
 * the recipe's processing duration. While it is down, the basin's contents are stirred — the mixer
 * tells its basin that much with {@code BasinBlockEntity#setAreFluidsMoving}, and that is what makes
 * the items orbit and the fluid surface swirl.
 *
 * <p>A held stirring rod has no block entity to keep that state in, and does not need one: while the
 * use key is held the item knows how long it has been held, so the whole cycle can be computed from
 * that single number. That is also why the client and the server always agree on when the contents
 * are moving and when the recipe lands, with nothing to synchronize.
 */
public final class MixingCycle {

    /** The kinetic speed a tool stands in for: the same 256 rpm the other tool actions use. */
    public static final float TOOL_SPEED = 256f;

    /**
     * Where the mixer splits its cycle: the first twenty of its {@code runningTicks} are the way down,
     * and it holds at twenty while it processes. Its basin is told the contents are moving for exactly
     * that stretch — {@code running && runningTicks <= 20} — and rises without them afterwards.
     */
    public static final int DOWN_TICKS = 20;

    private MixingCycle() {
    }

    /**
     * How long the mixer sits at the bottom before the recipe is applied: {@code processingTicks} from
     * the mixer, verbatim, with the kinetic speed a tool stands in for.
     */
    public static int strikeDelay(@Nullable Recipe<?> recipe) {
        float recipeSpeed = 1;
        if (recipe instanceof StandardProcessingRecipe<?> processing) {
            int duration = processing.getProcessingDuration();
            if (duration != 0)
                recipeSpeed = duration / 100f;
        }
        return Math.max((Mth.log2((int) (512 / TOOL_SPEED))) * Mth.ceil(recipeSpeed * 15) + 1, 1);
    }

    /** How many ticks one full stir takes: down, the processing pause, and back up. */
    public static int cycleLength(int delay) {
        return DOWN_TICKS * 2 + delay;
    }

    /** Where in its cycle an elapsed tick lands. */
    public static int cycleTick(int elapsed, int delay) {
        int length = cycleLength(delay);
        return length <= 0 ? 0 : Math.floorMod(elapsed, length);
    }

    /**
     * The mixer's {@code runningTicks} for a tick of the cycle: counting down to the bottom, held at
     * the bottom while it processes, then counting back up.
     */
    public static int mixerTick(int elapsed, int delay) {
        int tick = cycleTick(elapsed, delay);
        if (tick < DOWN_TICKS)
            return tick;
        if (tick <= DOWN_TICKS + delay)
            return DOWN_TICKS;
        return tick - delay + 1;
    }

    /**
     * Whether the basin's contents are being stirred on this tick, which is the mixer's own
     * {@code running && runningTicks <= 20}.
     */
    public static boolean contentsMoving(int elapsed, int delay) {
        return mixerTick(elapsed, delay) <= DOWN_TICKS;
    }

    /** Whether this tick is the one the recipe lands on: the end of the processing pause. */
    public static boolean strikes(int elapsed, int delay) {
        return cycleTick(elapsed, delay) == DOWN_TICKS + delay;
    }
}
