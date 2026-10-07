package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.Create_ai;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.simibubi.create.content.kinetics.saw.TreeCutter;
import com.simibubi.create.foundation.utility.AbstractBlockBreakQueue;
import com.simibubi.create.foundation.utility.BlockHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Precision cutting, shared by a saw standing in the world and a saw mounted on a contraption.
 *
 * <p>Both paths cut the same tree and must drop the same things, so the tool and the felling are
 * defined once here rather than duplicated. The only difference between the two callers is where the
 * drops go afterwards: a placed saw drops them, a contraption puts them in its storage.
 */
public final class BrassSawPrecisionCutting {

    /**
     * The tool precision mode cuts with. Silk Touch, and unbreakable.
     *
     * <p>Unbreakable because Create's destruction path hands this stack to {@code ItemStack#mineBlock}
     * and, if it were consumed, to the "player destroyed item" hook. It is never intended to take wear —
     * it exists only so {@code Block.getDrops} is asked what a silk-touched tool would produce — and
     * marking it so keeps a stack that lives for the lifetime of the process from being whittled down by
     * a machine.
     */
    private static ItemStack silkTouchTool;

    private BrassSawPrecisionCutting() {
    }

    /** The mode selected, read from the blockstate so it survives a saw being assembled. */
    public static BrassMechanicalSawMode modeOf(BlockState state) {
        if (!state.hasProperty(BrassMechanicalSawBlock.MODE))
            return BrassMechanicalSawMode.FAST_TREE_FELLING;
        return BrassMechanicalSawMode.byIndex(state.getValue(BrassMechanicalSawBlock.MODE));
    }

    public static boolean isPrecision(BlockState state) {
        return modeOf(state) == BrassMechanicalSawMode.PRECISION;
    }

    /** Lazily built: the enchantment registry is not reachable before a level exists. */
    public static ItemStack silkTouchTool(Level level) {
        if (silkTouchTool == null) {
            ItemStack tool = new ItemStack(Items.NETHERITE_PICKAXE);
            tool.enchant(level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.SILK_TOUCH), 1);
            tool.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
            silkTouchTool = tool;
        }
        return silkTouchTool;
    }

    /**
     * Fells the tree at {@code pos} the way Create's saw does, but with a silk-touched tool, so every
     * block in the trunk and canopy drops as itself — leaves come off as leaf blocks rather than
     * saplings and sticks.
     *
     * <p>{@code drop} receives each stack and the position it came from; the caller decides whether that
     * means dropping it on the ground or inserting it into a contraption's storage.
     */
    public static void fellWithSilkTouch(Level level, BlockPos pos, BlockState brokenState,
                                         BiConsumer<BlockPos, ItemStack> drop) {
        ItemStack tool = silkTouchTool(level);
        Optional<AbstractBlockBreakQueue> dynamicTree = TreeCutter.findDynamicTree(brokenState.getBlock(), pos);
        if (dynamicTree.isPresent()) {
            dynamicTree.get()
                .destroyBlocks(level, tool, null, drop);
            return;
        }
        TreeCutter.findTree(level, pos, brokenState)
            .destroyBlocks(level, tool, null, drop);
    }

    /** One block, cut with the silk-touched tool. Used for the block the blade came to rest on. */
    public static void destroyOneWithSilkTouch(Level level, BlockPos pos, Consumer<ItemStack> drop) {
        BlockHelper.destroyBlockAs(level, pos, null, silkTouchTool(level), 1f, drop);
    }
}
