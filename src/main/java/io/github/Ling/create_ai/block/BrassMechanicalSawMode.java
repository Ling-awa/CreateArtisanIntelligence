package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.Create_ai;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringRepresentable;

/**
 * What a horizontally placed brass powered saw does with what it cuts.
 *
 * <p>Only meaningful while the blade lies horizontal — that is the orientation a saw spends breaking
 * blocks in, both standing in the world and mounted on a contraption. A saw facing up is a cutting
 * table with a recipe filter, and its mode has no effect.
 *
 * <p>The enum is what Create's scroll-option UI iterates over: {@code ScrollOptionBehaviour} reads
 * the constants in declaration order, so the ordinal of each constant <em>is</em> the value stored in
 * the blockstate and in the scroll value. {@link #FAST_TREE_FELLING} must therefore stay first, which
 * is also the default a freshly placed saw gets.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public enum BrassMechanicalSawMode implements INamedIconOptions, StringRepresentable {

    /**
     * Twice the breaking speed of a mechanical saw, dropping what the tree would normally drop. This
     * is the default, and the mode whose behaviour matches the originally requested machine.
     *
     * <p>The double chevron is the same glyph Create uses for "faster" on the train display, which is
     * exactly what this mode does — it reads as a speed step rather than as "run".
     */
    FAST_TREE_FELLING("fast_tree_felling", AllIcons.I_MTD_RIGHT),

    /**
     * Stock mechanical saw speed, but every block in a felled tree drops as itself — leaves come off
     * as leaf blocks rather than saplings and sticks, because the trees are cut as though by a tool
     * with Silk Touch.
     */
    PRECISION("precision", AllIcons.I_TARGET);

    private final String name;
    private final AllIcons icon;

    BrassMechanicalSawMode(String name, AllIcons icon) {
        this.name = name;
        this.icon = icon;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    @Override
    public AllIcons getIcon() {
        return icon;
    }

    @Override
    public String getTranslationKey() {
        return "create_ai.brass_mechanical_saw.mode." + name;
    }

    /**
     * The mode's name as a component — used by the goggle overlay, which prints it beside its label.
     *
     * <p>Kept here rather than built from {@link #getTranslationKey()} at each call site so the lang key
     * is spelled in exactly one place.
     */
    public MutableComponent getLabel() {
        return Component.translatable(getTranslationKey());
    }

    /** The constant at {@code ordinal}, clamped: a scroll value or blockstate can never overrun. */
    public static BrassMechanicalSawMode byIndex(int index) {
        BrassMechanicalSawMode[] values = values();
        if (index < 0)
            return values[0];
        if (index >= values.length)
            return values[values.length - 1];
        return values[index];
    }
}
