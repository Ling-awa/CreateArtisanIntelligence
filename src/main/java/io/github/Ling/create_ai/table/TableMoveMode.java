package io.github.Ling.create_ai.table;

import io.github.Ling.create_ai.Create_ai;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringRepresentable;

/**
 * Which way a sneaking click pushes the layout along the table.
 *
 * <p>The setting a player picks from the button on the table's four sides. It exists because the click that
 * pushes the sheet is read from the face, and a face is not a direction a player can see: standing at the
 * near side and clicking the half in front of them is meant to push the sheet away, and standing at the far
 * side the same gesture means the opposite. Rather than guess, both readings are offered and the player says
 * which one their table uses.
 *
 * <p>The enum is what Create's scroll-option UI iterates over: {@code ScrollOptionBehaviour} reads the
 * constants in declaration order, so the ordinal of each constant <em>is</em> the value stored and sent.
 * {@link #NORMAL} must therefore stay first, which is also what a freshly placed table does.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public enum TableMoveMode implements INamedIconOptions, StringRepresentable {

    /**
     * The lay out is pushed the way the clicked part of the face points: click the half nearest the player and
     * the sheet comes towards them.
     */
    NORMAL("normal", AllIcons.I_MOVE_PLACE),

    /**
     * The same click pushes the sheet the other way: click the half nearest the player and the sheet travels
     * away. This is the reading that makes "click past the layout" mean "make room past the layout", and it is
     * the one to pick when a player works the table from a fixed side.
     */
    REVERSED("reversed", AllIcons.I_MOVE_PLACE_RETURNED);

    private final String name;
    private final AllIcons icon;

    TableMoveMode(String name, AllIcons icon) {
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
        return "create_ai.processing_table.move_mode." + name;
    }

    /** The mode's name as a component: the row the button shows, and the line the overlay would print. */
    public MutableComponent getLabel() {
        return Component.translatable(getTranslationKey());
    }

    /** The constant at {@code ordinal}, clamped: a scroll value can never overrun the list. */
    public static TableMoveMode byIndex(int index) {
        TableMoveMode[] values = values();
        if (index < 0)
            return values[0];
        if (index >= values.length)
            return values[values.length - 1];
        return values[index];
    }
}
