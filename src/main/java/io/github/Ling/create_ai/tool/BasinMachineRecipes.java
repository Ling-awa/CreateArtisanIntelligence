package io.github.Ling.create_ai.tool;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A basin machine's own recipe lookup, asked without the machine.
 *
 * <p>There is no way to work out from the outside which recipes a basin device would process. The
 * answer is whatever that device's {@code getMatchingRecipes} produces, and it is not a set of recipe
 * types or of recipe classes: Create answers with its own registered recipes plus the potion brewing
 * it generates at runtime, and an addon answers with whatever it appends to that same method with a
 * mixin. Create: Dragons Plus, in this pack, does exactly that for its liquid-dye mixing — the recipes
 * are built on the spot out of the basin's contents and registered nowhere, so no search, however
 * general, can see them.
 *
 * <p>So instead of reproducing the answer, this asks the machine. A real {@link
 * MechanicalMixerBlockEntity} is built at the spot a mixer would occupy over the basin, handed the
 * basin's level, and asked for its recipes. It is never added to the level, never ticked, never saved
 * and its behaviours are never created, so the world never sees it. What makes it valid is that it is
 * the very same class: every mixin that has been applied to Create's mixer runs for this instance too.
 * Extending the class here is only about reach — {@code getMatchingRecipes} is {@code protected}, and
 * a subclass is what can call it.
 *
 * <p>Its kinetic speed reads as zero, which is the one thing an addon's code could notice. Create's
 * lookup does not read the speed and neither addon in this pack does; if asking ever does fail, the
 * failure is logged once and {@code null} comes back, so the caller can fall back to working the
 * answer out by hand.
 */
public final class BasinMachineRecipes {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One warning per machine is enough to make a broken lookup diagnosable without flooding the log. */
    private static boolean mixerWarned;
    private static boolean pressWarned;

    private BasinMachineRecipes() {
    }

    /**
     * What a mechanical mixer over this basin would process right now, in the mixer's own order — the
     * order it takes its recipe from — or an empty list when it would process nothing, or null when the
     * mixer could not be asked.
     */
    @Nullable
    public static List<Recipe<?>> mixerOver(BasinBlockEntity basin) {
        Level level = basin.getLevel();
        if (level == null)
            return List.of();
        try {
            GhostMixer mixer = new GhostMixer(type("mechanical_mixer"), mixerSpot(basin), state("mechanical_mixer"));
            mixer.setLevel(level);
            return mixer.recipes();
        } catch (Throwable t) {
            if (!mixerWarned) {
                mixerWarned = true;
                LOGGER.error("create_ai: could not ask a mechanical mixer for its recipes; stirring falls"
                    + " back to reading mixing recipes by hand, which does not cover what addons add to"
                    + " the mixer itself", t);
            }
            return null;
        }
    }

    /**
     * What a mechanical press over this basin would process right now, in the press's own order, or an
     * empty list when it would process nothing, or null when the press could not be asked.
     */
    @Nullable
    public static List<Recipe<?>> pressOver(BasinBlockEntity basin) {
        Level level = basin.getLevel();
        if (level == null)
            return List.of();
        try {
            GhostPress press = new GhostPress(type("mechanical_press"), mixerSpot(basin), state("mechanical_press"));
            press.setLevel(level);
            return press.recipes();
        } catch (Throwable t) {
            if (!pressWarned) {
                pressWarned = true;
                LOGGER.error("create_ai: could not ask a mechanical press for its recipes; pressing falls"
                    + " back to reading compacting recipes by hand", t);
            }
            return null;
        }
    }

    /** Where a basin device sits to reach the basin below it: {@code getBasin} looks two blocks down. */
    private static BlockPos mixerSpot(BasinBlockEntity basin) {
        return basin.getBlockPos()
            .above(2);
    }

    private static BlockEntityType<?> type(String path) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("create", path));
    }

    private static BlockState state(String path) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", path));
        return block.defaultBlockState();
    }

    /** Create's mixer, with its protected lookup made reachable, and its mixins along for the ride. */
    private static final class GhostMixer extends MechanicalMixerBlockEntity {

        private GhostMixer(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        private List<Recipe<?>> recipes() {
            return getMatchingRecipes();
        }
    }

    /** Create's press, likewise. */
    private static final class GhostPress extends MechanicalPressBlockEntity {

        private GhostPress(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        private List<Recipe<?>> recipes() {
            return getMatchingRecipes();
        }
    }
}
