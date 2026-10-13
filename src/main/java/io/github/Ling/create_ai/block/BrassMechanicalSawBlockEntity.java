package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.CreateAI;

import java.util.List;
import java.util.Objects;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.content.kinetics.drill.DrillBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The brass powered saw's block entity: Create's saw block entity, plus a mode selector, with the
 * processing speed and stress scaled by how it is set.
 *
 * <p>Everything else is inherited rather than reimplemented, which is the point. Storage and syncing,
 * the recipe filter and its slot, belt input, the eight output slots, the tree felling, the blade
 * sounds and particles, the block-breaking progress, the goggle tooltip, the comparator output and the
 * contents dropping out on break are all Create's own code running on Create's own fields. The blade
 * is still drawn by Create's {@code SawVisual} / {@code SawRenderer}, registered for this block entity
 * type in {@link CreateAI.ClientModEvents}.
 *
 * <p>What this class changes:
 * <ul>
 * <li><b>Processing speed</b> — {@link #getSpeed()} reports double while a cut is running in fast mode,
 *     and the true network speed in precision mode. Create's own tick asks that one accessor for the
 *     number it divides by 24, so nothing else has to know.</li>
 * </ul>
 *
 * <p>The stress impact is not touched here. It is declared in {@link BrassMechanicalSawStress} through Create's
 * own registry, which is where the network, the goggles and the item tooltip all read it from.</p>
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class BrassMechanicalSawBlockEntity extends SawBlockEntity {

    /** How much faster a cut runs than Create's saw: Create divides by 24, this by 12. */
    private static final float SPEED_MULTIPLIER = 2f;

    /** How much harder a brass saw bites than a mechanical one. */
    private static final double DAMAGE_MULTIPLIER = 2.0;

    public BrassMechanicalSawBlockEntity(BlockPos pos, BlockState state) {
        // Create's constructor is public and takes the type as a parameter, so it is reused as-is:
        // the processing inventory, its slot limit, remainingTime and playEvent all come from Create.
        super(CreateAI.BRASS_MECHANICAL_SAW_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        // No field kept: the blockstate is the single source of truth for the mode, so nothing needs to
        // reach back into the behaviour afterwards.
        behaviours.add(new BrassMechanicalSawModeBehaviour(this));
    }

    // --- the mode ---------------------------------------------------------------------------------

    /**
     * The mode currently selected, read from the blockstate.
     *
     * <p>The blockstate rather than the behaviour on purpose: a contraption actor has no block entity,
     * so the state is the only copy that survives a saw being assembled, moved and disassembled.
     * {@link BrassMechanicalSawModeBehaviour} keeps the two in step.
     */
    public BrassMechanicalSawMode getMode() {
        BlockState state = getBlockState();
        if (!state.hasProperty(BrassMechanicalSawBlock.MODE))
            return BrassMechanicalSawMode.FAST_TREE_FELLING;
        return BrassMechanicalSawMode.byIndex(state.getValue(BrassMechanicalSawBlock.MODE));
    }

    /** Whether the saw is set to cut with Silk Touch instead of at double speed. */
    public boolean isPrecisionMode() {
        return getMode() == BrassMechanicalSawMode.PRECISION;
    }

    // --- engineer's goggles -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Adds the cutting mode <em>below</em> the kinetic stats, rather than replacing them. The parent
     * implementation is what prints speed, stress and overstress — overriding without calling {@code super}
     * left the overlay showing the mode alone and no kinetic data at all.
     *
     * <p>{@code forGoggles} appends to the tooltip list, so calling the parent first and then this puts the
     * mode last, under the base attributes.
     *
     * <p>The mode is read from the blockstate, so the overlay shows exactly what a contraption will use
     * when the saw is assembled.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);

        // CreateLang prefixes everything it looks up with "create." - CreateLang.builder() is
        // new LangBuilder(Create.ID), and Create.ID is "create". So the key handed to it here is the
        // version without the "create_ai." namespace, and the matching lang entries carry the "create."
        // form. The mode name is a plain Component, and Component.translatable does NOT prefix, which is
        // why the mode-name keys below exist in both spellings.
        CreateLang.translate(GOGGLES_MODE_KEY, getMode().getLabel())
            .style(ChatFormatting.GRAY)
            .forGoggles(tooltip);

        // The mode is always shown, so the overlay is worth displaying even when the parent had nothing to
        // say — a stopped saw off the network reports no kinetic stats, but its mode is still meaningful.
        return true;
    }

    /**
     * Goggle overlay label, e.g. "Cutting Mode: Fast Tree Felling".
     *
     * <p>Spelled without the mod namespace on purpose: {@code CreateLang} adds {@code create.} in front of
     * it, so the key that ends up in the language file is
     * {@code create.brass_mechanical_saw.goggles.mode}.
     */
    private static final String GOGGLES_MODE_KEY = "brass_mechanical_saw.goggles.mode";

    // --- deviation 1: twice the speed, in fast mode only ------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>The whole speed deviation, in one hook. Create's {@code SawBlockEntity.tick()} calls
     * {@code getSpeed()} to compute {@code Mth.clamp(Math.abs(getSpeed()) / 24, 1, 128)} and subtracts
     * the result from the running cut's remaining time. That call is the only place processing speed is
     * decided — and it is public, unlike the tick body, whose recipe step and blade sound field are
     * private and therefore unreachable from a subclass. Overriding the accessor is what makes the faster
     * cut possible without copying Create's tick or touching its privates.
     *
     * <p>This one override also covers <em>breaking blocks</em>, which is the other thing the saw's speed
     * feeds. {@code BlockBreakingKineticBlockEntity.getBreakSpeed()} is
     * {@code Math.abs(getSpeed() / 100f)}, so reporting double here doubles how fast the blade chews
     * through a trunk as well — a placed saw and a mounted one move at the same rate, which is the point.
     *
     * <p>The clamp bounds stay Create's, applied after this returns: the floor of 1 still stops a barely
     * turning saw from taking forever over one item, and the ceiling of 128 still stops even a 256 RPM saw
     * from finishing a cut inside a single tick.
     *
     * <p>In precision mode the true network speed is returned — that mode trades speed for quality, so a
     * cut that drops leaves as leaves takes as long as Create's saw would, and the blade breaks blocks at
     * Create's rate too.
     */
    @Override
    public float getSpeed() {
        if (isPrecisionMode())
            return super.getSpeed();
        return super.getSpeed() * SPEED_MULTIPLIER;
    }

    /**
     * Hurts an entity touching the spinning blade, with the brass saw's doubled damage.
     *
     * <p>Called by the block's {@code entityInside}, which cannot work this out for itself: it only gets
     * an {@link SawBlockEntity}, not this subclass, so it cannot see past the fast-mode bonus that
     * {@link #getSpeed()} applies.
     *
     * <p>Damage is a property of the machine rather than of the setting, so this deliberately uses
     * {@code super.getSpeed()} — the speed the network actually supplies — and not {@link #getSpeed()}.
     * Reading the latter would have made a precision-mode saw bite half as hard as a fast-mode one, which
     * is not what the mode is for.
     */
    public void hurtWithBlade(net.minecraft.world.entity.Entity entity) {
        double base = DrillBlock.getDamage(super.getSpeed());
        // The damage source needs a level that exists, and the block entity's own is the one it was hurt in:
        // this is only reached from the block's entityInside, so the entity is in a world and so is the saw.
        entity.hurt(CreateDamageSources.saw(Objects.requireNonNull(this.level)),
            (float) Mth.clamp(base * DAMAGE_MULTIPLIER, 1, 10));
    }

    // --- deviation 2: precision mode fells with Silk Touch -----------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>In precision mode, the tree the blade is working through comes down with a silk-touched tool:
     * every block in the trunk and canopy drops as itself, leaves included. In fast mode this is Create's
     * own behaviour, untouched.
     *
     * <p>This is the placed saw's counterpart to the contraption logic in
     * {@link BrassMechanicalSawMovementBehaviour} — same felling, same tool, shared through
     * {@link BrassSawPrecisionCutting} so the two can never drift apart. Only the destination of the drops
     * differs: a placed saw throws them on the ground here, a contraption puts them in its storage.
     */
    @Override
    public void onBlockBroken(BlockState stateToBreak) {
        if (!isPrecisionMode()) {
            super.onBlockBroken(stateToBreak);
            return;
        }

        // The block the blade came to rest on, cut the same way.
        BrassSawPrecisionCutting.destroyOneWithSilkTouch(level, breakingPos,
            stack -> dropItemFromCutTree(breakingPos, stack));

        // Then the rest of the tree.
        BrassSawPrecisionCutting.fellWithSilkTouch(level, breakingPos, stateToBreak,
            this::dropItemFromCutTree);
    }

    // --- the stress impact ------------------------------------------------------------------------

    // The impact itself is declared in BrassMechanicalSawStress, through Create's own registry, and inherited
    // calculateStressApplied reads it from there. Nothing is overridden here on purpose: doubling the
    // value at this point would charge the network twice over, and it would leave every other reader of
    // the registry - the goggles, and the item tooltip's stress line - still reporting the base figure.

    // --- the item handler capability, on our own block entity type --------------------------------

    /** The same capability, and the same rule, Create exposes on its saw: nothing may be pulled from below. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            CreateAI.BRASS_MECHANICAL_SAW_BE.get(),
            (be, context) -> {
                if (context != Direction.DOWN)
                    return be.inventory;
                return null;
            }
        );
    }
}
