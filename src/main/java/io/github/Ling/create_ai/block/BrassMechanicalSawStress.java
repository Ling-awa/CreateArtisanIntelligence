package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;

import com.simibubi.create.api.stress.BlockStressValues;

/**
 * The brass powered saw's stress impact, declared where Create declares its own.
 *
 * <p>Create keeps the stress impact of every kinetic block in
 * {@link BlockStressValues#IMPACTS}, a registry keyed by block. Everything that reports stress reads
 * it from there and nowhere else: the kinetic network charges it through
 * {@code KineticBlockEntity.calculateStressApplied}, the engineer's goggles print it, and the item
 * tooltip's stress line comes from Create's {@code KineticStats} modifier, which looks the block up in
 * this registry. A kinetic block with no entry here therefore draws nothing at all.
 *
 * <p>Create registers its own entries through {@code CStress.setImpact} while building the block, but
 * that helper refuses blocks from other mods ({@code assertFromCreate}) and keys its config by
 * {@code create:<name>}. Registering directly is the same thing for a block Create does not own, and
 * this is the API entry point Create publishes for it.
 *
 * <p>The value is written out rather than derived from Create's saw on purpose. Reading
 * {@code BlockStressValues.getImpact(AllBlocks.MECHANICAL_SAW)} would look tidy but breaks as soon as a
 * pack edits stress values, because Create's config can override the base entry while a snapshot taken
 * earlier keeps the old number. 4.0 SU/RPM is the mechanical saw's documented impact; doubling it is
 * what this machine is.
 */
public final class BrassMechanicalSawStress {

    /** Create's mechanical saw declares 4.0 SU/RPM; the brass one is twice that. */
    private static final double BRASS_MECHANICAL_SAW_IMPACT = 8.0;

    private BrassMechanicalSawStress() {
    }

    /** Called once, after the blocks are registered and before any level reads a stress value. */
    public static void register() {
        BlockStressValues.IMPACTS.register(Create_ai.BRASS_MECHANICAL_SAW.get(),
            () -> BRASS_MECHANICAL_SAW_IMPACT);
    }
}
