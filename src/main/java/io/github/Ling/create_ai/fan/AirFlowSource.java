package io.github.Ling.create_ai.fan;

import io.github.Ling.create_ai.Create_ai;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;

import net.minecraft.world.phys.Vec3;

/**
 * What an air-flow particle needs to know about the air it is in: the few facts either of the fan's two flows
 * can answer.
 *
 * <p>Create's {@code AirFlowParticle} reads all of this off an {@code IAirCurrentSource} and its
 * {@code AirCurrent} — but an {@code AirCurrent} carries its direction as a {@code Direction}, one of the six
 * axes, and the handheld fan's air does not go along an axis. This is the same handful of facts with the
 * direction as a plain vector, which is what lets one particle class draw both flows: the axis-aligned current
 * hands over the normal of its {@code Direction}, and the omnidirectional one the player's look vector.
 *
 * <p>Common code only. Both implementations are classes a dedicated server loads as well, so nothing here may
 * name a client type.
 */
public interface AirFlowSource {

    /** Whether the air is still being blown. A particle whose air has gone removes itself. */
    boolean isBlowing();

    /** Where the air leaves the fan, at the middle of the block the eyes are in. */
    Vec3 flowOrigin();

    /** Which way it goes, as a unit vector: any direction, not just an axis. */
    Vec3 flowDirection();

    /**
     * How far it reaches, in blocks, along {@link #flowDirection()} from {@link #flowOrigin()}.
     */
    float flowMaxDistance();

    /** Whether a point is inside the blown corridor, which is what keeps a particle alive. */
    boolean flowContains(Vec3 at);

    /** What the air carries at a distance along the flow: Create's processing type, or null for plain air. */
    @Nullable
    FanProcessingType flowTypeAt(float offset);
}
