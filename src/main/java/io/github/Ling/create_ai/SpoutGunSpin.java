package io.github.Ling.create_ai;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * How fast the spout gun's cog is turning: its idle drift, or the faster turn it keeps up for as long
 * as the trigger is held.
 *
 * <p>This is a rate, integrated into an angle, and that is the whole point. Create's potato cannon
 * maps its recoil onto the angle directly — {@code angle += 360 * clamp(speed * 5, 0, 1)} — which
 * works for a cannon because the value behind it decays every tick, so the added term is always
 * <em>changing</em>. A spout gun is held rather than clicked, so that value settles at its maximum
 * within a few ticks, and a constant added to the angle and then taken modulo 360 adds nothing at all:
 * the cog would sit there looking idle for the entire hold. Holding a rate instead means the cog turns
 * for the whole hold, which is what the hold looks like it should do.
 *
 * <p>Client-only, and on the client bus by way of {@code value = Dist.CLIENT} — which is also what
 * keeps it from being loaded on a dedicated server.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class SpoutGunSpin {

    /** Degrees per tick at rest: the potato cannon's own idle speed, kept so the cog still drifts. */
    private static final float IDLE_RATE = -2.5f;

    /**
     * Degrees per tick while the trigger is held.
     *
     * <p>Not the cannon's own speed: its cog flashes a full turn per tick for a handful of ticks, and
     * that is a blur — and sampled at 60 frames against 20 ticks, a full turn every tick reads as a
     * cog that is barely moving at all. This one has to look like a machine running for the second the
     * trigger is held, so it is a turn per second: unmistakably faster than idle, and still followable
     * by eye.
     */
    private static final float USE_RATE = -20f;

    /**
     * How much the rate itself may change in one tick, so winding up and winding down take a few ticks
     * instead of snapping.
     */
    private static final float RATE_CHANGE = 2.5f;

    /** Degrees turned so far. Kept inside one turn, and interpolated across the current tick. */
    private static float angle;
    private static float lastAngle;

    private static float rate = IDLE_RATE;

    private SpoutGunSpin() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        lastAngle = angle;

        LocalPlayer player = Minecraft.getInstance().player;
        boolean using = player != null && player.isUsingItem()
            && player.getUseItem()
                .getItem() instanceof SpoutGunItem;

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
    public static float angle(float partialTicks) {
        return Mth.lerp(partialTicks, lastAngle, angle);
    }
}
