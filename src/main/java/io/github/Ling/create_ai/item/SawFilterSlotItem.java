package io.github.Ling.create_ai.item;

import io.github.Ling.create_ai.Create_ai;

import com.mojang.serialization.DataResult;
import com.simibubi.create.content.logistics.filter.FilterItemStack;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * What is installed in a handheld mechanical saw's filter slot.
 *
 * <p>The slot works exactly the way Create's saw's slot does — an item in it selects among the recipes
 * the saw could run, and an empty slot means "any of them" — so the installed thing is handed to
 * Create's own {@link FilterItemStack} to judge. That is what makes a single item and a
 * {@code create:attribute_filter} both work without any special case here: {@code FilterItemStack.of}
 * returns a different subclass per filter item, and each of them answers
 * {@link FilterItemStack#test(Level, ItemStack)} its own way.
 *
 * <p><b>Why the stack is stored as NBT.</b> A filter item carries its contents in data components —
 * {@code create:attribute_filter} holds the attributes it matches and whether each is inverted, and a
 * list filter holds the items it lists. Rebuilding the filter from its item id, which is what the
 * handheld fan does, would throw all of that away and leave a filter that matches everything. The
 * whole stack therefore has to be kept, and it cannot be kept as a component value: a component's
 * value has to implement equals and hashCode, {@link ItemStack} compares by identity, and NeoForge
 * rejects it outright ("Data components must implement equals and hashCode"). {@link CompoundTag} is
 * the form that keeps everything <em>and</em> implements both, so the stack is serialized into one and
 * the component holds that.
 *
 * <p>Two components, both on the saw itself: the filter, and where the recipe list got to (see
 * {@link #advanceRecipeIndex}). A third says whether the filter is being obeyed at all (see
 * {@link #filterOff}) — the saw carries that one because a sneaking right-click is meant to be a quick way
 * to bypass filtering without taking the filter out of the slot.
 */
public final class SawFilterSlotItem {

    // The components this slot is made of are declared in Create_ai, beside the mod's other components,
    // and not here: a component registered by the class that reads it is registered the first time that
    // class loads — when a tooltip is drawn or the item is used, long after the register event has fired —
    // and NeoForge refuses a registration that late. What happened instead was
    // "NoClassDefFoundError: Could not initialize class SawFilterSlotItem" the moment the saw was hovered.
    // They are Create_ai.SAW_FILTER (the installed stack, as NBT), Create_ai.SAW_RECIPE_INDEX (where a
    // run through the matching recipes got to) and Create_ai.SAW_FILTER_OFF (whether the filter is bypassed).

    private SawFilterSlotItem() {
    }

    // --- the installed filter --------------------------------------------------------------------

    /**
     * The installed filter as a stack, or {@link ItemStack#EMPTY} when the slot is empty.
     *
     * <p>The registries are needed to read the stored NBT back into an item stack — an item is named by
     * id — and they come from whichever level the caller is on.
     */
    public static ItemStack installed(ItemStack saw, HolderLookup.Provider registries) {
        CompoundTag tag = saw.get(Create_ai.SAW_FILTER.get());
        if (tag == null || tag.isEmpty())
            return ItemStack.EMPTY;
        return ItemStack.parseOptional(registries, tag);
    }

    /**
     * Puts one item in the slot, handing back whatever was there.
     *
     * <p>A one-item copy is what is stored: a filter is one item's worth of meaning, and a stack of
     * twenty filters in the slot would only be a bigger number. Everything else about the stack — the
     * components a filter keeps its contents in — is written out with {@code ItemStack.CODEC} and read
     * back by {@link #installed}, so it survives.
     *
     * <p>A stack that cannot be written out is not installed at all, and the slot keeps what it had. What
     * comes back is always a stack — {@link ItemStack#EMPTY} when there was nothing in the slot — so the
     * caller has no null to test for.
     */
    public static ItemStack install(ItemStack saw, ItemStack filter, HolderLookup.Provider registries) {
        if (filter.isEmpty())
            return installed(saw, registries);
        ItemStack previous = installed(saw, registries);
        DataResult<Tag> encoded = ItemStack.STRICT_SINGLE_ITEM_CODEC.encodeStart(registryOps(registries),
            filter.copyWithCount(1));
        Tag tag = encoded.result()
            .orElse(null);
        if (tag instanceof CompoundTag compound)
            saw.set(Create_ai.SAW_FILTER.get(), compound);
        return previous;
    }

    /** Empties the slot, handing back what was in it, or {@link ItemStack#EMPTY} when there was nothing. */
    public static ItemStack uninstall(ItemStack saw, HolderLookup.Provider registries) {
        ItemStack previous = installed(saw, registries);
        if (!previous.isEmpty())
            saw.remove(Create_ai.SAW_FILTER.get());
        return previous;
    }

    /**
     * The installed filter in the form Create's own recipe filtering uses.
     *
     * <p>An empty slot is {@link FilterItemStack#empty()}, whose {@code test} answers true for anything —
     * which is exactly what "no filter" has to mean to a recipe search, both here and on Create's saw.
     *
     * <p>A filter that has been toggled off is handed over as the same empty filter, while the stack itself
     * stays where it is: bypassing the filter is meant to answer "any of them" without the player losing
     * what they put in, so {@link #installed} still finds it and only the judgment changes.
     */
    public static FilterItemStack filterOf(ItemStack saw, HolderLookup.Provider registries) {
        if (filterOff(saw))
            return FilterItemStack.empty();
        ItemStack installed = installed(saw, registries);
        return installed.isEmpty() ? FilterItemStack.empty() : FilterItemStack.of(installed);
    }

    /**
     * Whether the installed filter accepts an item, asked the way Create's saw asks it: the filter's own
     * {@code test}, with the level the recipes are being looked up in.
     *
     * <p>The saw reaches this through {@code RecipeConditions.outputMatchesFilter} and
     * {@code FilteringBehaviour.test}, both of which end in {@code FilterItemStack.test(level, stack)}.
     * There is no filter behaviour here — the filter lives on the item, not in a block — so the same call
     * is made directly, and the answer is the same. An empty filter matches everything, as it does there.
     */
    public static boolean accepts(Level level, ItemStack saw, ItemStack candidate) {
        if (level == null)
            return true;
        FilterItemStack filter = filterOf(saw, level.registryAccess());
        return filter.isEmpty() || filter.test(level, candidate);
    }

    // --- bypassing the filter --------------------------------------------------------------------

    /**
     * Whether the installed filter is being ignored. Absent means no, which is the state a saw is in until
     * someone sneaks and right-clicks it.
     */
    public static boolean filterOff(ItemStack saw) {
        return saw.getOrDefault(Create_ai.SAW_FILTER_OFF.get(), false);
    }

    /**
     * Turns the filter's authority off, or back on, and answers the state it is now in.
     *
     * <p>Turning it back on removes the component rather than writing {@code false} into it, so a saw that
     * has been toggled off and on again is carrying exactly what it carried before — the comparison a
     * component map makes when the stack is sent to a client is then the same comparison as always.
     */
    public static boolean toggleFilter(ItemStack saw) {
        boolean off = !filterOff(saw);
        if (off)
            saw.set(Create_ai.SAW_FILTER_OFF.get(), true);
        else
            saw.remove(Create_ai.SAW_FILTER_OFF.get());
        return off;
    }

    // --- the recipe index ------------------------------------------------------------------------

    /** Which recipe the next run takes. */
    public static int recipeIndex(ItemStack saw) {
        return saw.getOrDefault(Create_ai.SAW_RECIPE_INDEX.get(), 0);
    }

    /**
     * Advances the slot the way {@code SawBlockEntity#start} does before a recipe is applied: the index
     * moves on by one and wraps at the end of the list.
     *
     * <p>This is the whole of the multi-output cycling. The saw runs the recipe its index points at and
     * then steps the index, so two runs of a two-output recipe give the two outputs in turn; the tool does
     * the same, and the index lives on the item so it survives between holds.
     */
    public static void advanceRecipeIndex(ItemStack saw, int recipeCount) {
        int next = recipeIndex(saw) + 1;
        if (recipeCount > 0 && next >= recipeCount)
            next = 0;
        saw.set(Create_ai.SAW_RECIPE_INDEX.get(), next);
    }

    /**
     * The ops an item stack is written with: the registries of whichever level the caller is in, over NBT.
     * The same pair vanilla uses to save an item into block entity NBT.
     */
    private static RegistryOps<Tag> registryOps(HolderLookup.Provider registries) {
        return RegistryOps.create(NbtOps.INSTANCE, registries);
    }
}
