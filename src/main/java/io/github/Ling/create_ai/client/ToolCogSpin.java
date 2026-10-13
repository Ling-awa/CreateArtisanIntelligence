package io.github.Ling.create_ai.client;

import net.minecraft.util.Mth;

/**
 * How fast a tool's cog is turning, and how far it has turned: the idle drift of a tool that is merely
 * held, or the faster turn it keeps up for as long as the use key is down.
 *
 * <p>This is a rate, integrated into an angle, and that is the whole point. Create's potato cannon maps
 * its recoil onto the angle directly — {@code angle += 360 * clamp(speed * 5, 0, 1)} — which works for a
 * cannon because the value behind it decays every tick, so the added term is always <em>changing</em>. A
 * tool here is held rather than clicked, so that value settles at its maximum within a few ticks, and a
 * constant added to the angle and then taken modulo 360 adds nothing at all: the cog would sit there
 * looking idle for the entire hold. Holding a rate instead means the cog turns for the whole hold, which
 * is what the hold looks like it should do.
 *
 * <p>What is shared is the arithmetic, not the state: one instance is one cog, with its own rates and its
 * own angle, so the {@link SpoutGunItemRenderer spout gun}'s cog and the
 * {@link FanCogSpinItemRenderer handheld fan}'s cog each turn on their own axis — same speeds, same ramp,
 * no coupling. {@link SpoutGunSpin} is what reads the player and hands each instance its answer once per
 * client tick, which is also what keeps this class free of any client class of its own: nothing here
 * knows what a player is or what item is being used.
 *
 * <p>Client-only by construction: the only things that build an instance are the two renderers and the
 * client subscriber, all of which load on the client alone.
 */
public final class ToolCogSpin {

    /**
     * Degrees per tick at rest: the potato cannon's own idle speed, kept so a cog still drifts while the
     * tool is doing nothing.
     */
    public static final float IDLE_RATE = -2.5f;

    /**
     * Degrees per tick while the use key is held.
     *
     * <p>Not the cannon's own speed: its cog flashes a full turn per tick for a handful of ticks, and
     * that is a blur — and sampled at 60 frames against 20 ticks, a full turn every tick reads as a cog
     * that is barely moving at all. This has to look like a machine running for as long as the tool is
     * held, so it is a turn per second: unmistakably faster than idle, and still followable by eye.
     */
    public static final float USE_RATE = -20f;

    /**
     * How much the rate itself may change in one tick, so winding up and winding down take a few ticks
     * instead of snapping.
     */
    public static final float RATE_CHANGE = 2.5f;

    /** Degrees turned so far. Kept inside one turn, and interpolated across the current tick. */
    private float angle;
    private float lastAngle;

    private float rate = IDLE_RATE;

    /**
     * Wraps up one client tick: the rate walks towards the one the tool's state asks for, and the angle
     * takes this tick's step. Winding down is deliberately the same shape as winding up, because the rate
     * is what is clamped against and not the angle.
     *
     * @param using whether the tool whose cog this is is being used right now
     */
    public void tick(boolean using) {
        lastAngle = angle;

        float target = using ? USE_RATE : IDLE_RATE;
        rate += Mth.clamp(target - rate, -RATE_CHANGE, RATE_CHANGE);

        angle += rate;
        if (angle <= -360f) {
            // Shift both ends of the frame together, so the wrap cannot show up as a jump.
            angle += 360f;
            lastAngle += 360f;
        }
    }

    /** The cog's rotation for this frame, in degrees, interpolated between the last two ticks. */
    public float angle(float partialTicks) {
        return Mth.lerp(partialTicks, lastAngle, angle);
    }
}
