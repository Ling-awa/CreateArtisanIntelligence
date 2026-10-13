package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.CreateAI;

import com.simibubi.create.api.contraption.BlockMovementChecks;

import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Keeps a brass powered saw from dragging the block in front of it into a contraption.
 *
 * <p>When a mechanical piston (or a sticky one) pushes a contraption together, Create collects
 * neighbouring blocks outwards from the parts that must move. What stops that collection from walking
 * straight through a saw's blade is the <em>not supportive</em> check: a block that reports itself
 * non-supportive towards a direction cuts the propagation there, so the block in front is left behind
 * unless a Super Glue line says otherwise.
 *
 * <p>Create applies this to its own saw in the fallback of
 * {@code BlockMovementChecks.isNotSupportive}, but that fallback matches with
 * {@code AllBlocks.MECHANICAL_SAW.has(state)} — an identity test against one specific block. Our saw is
 * a different block, so it never matched, fell through to the brittle test, and reported supportive.
 * The result was a saw pushing whatever it was pointed at along with itself. Registering the check here
 * is the supported way to state the same thing for a block Create does not know about; it is the API
 * Create exposes for exactly this, and registrations are queried before the fallback.
 */
public final class BrassMechanicalSawMovementChecks {

    private BrassMechanicalSawMovementChecks() {
    }

    /**
     * Declares the saw non-supportive towards the block its blade faces — the same rule, in the same
     * words, Create applies to {@code create:mechanical_saw}.
     *
     * <p>Returns {@link BlockMovementChecks.CheckResult#PASS} for every other block so that other mods'
     * checks and Create's fallback still get their turn.
     */
    public static void register() {
        BlockMovementChecks.registerNotSupportiveCheck((state, facing) -> {
            if (!state.is(CreateAI.BRASS_MECHANICAL_SAW.get()))
                return BlockMovementChecks.CheckResult.PASS;
            if (!state.hasProperty(BlockStateProperties.FACING))
                return BlockMovementChecks.CheckResult.PASS;
            return BlockMovementChecks.CheckResult.of(state.getValue(BlockStateProperties.FACING) == facing);
        });
    }
}
