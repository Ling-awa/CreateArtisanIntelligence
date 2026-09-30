package io.github.Ling.create_ai;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.fan.AirFlowParticle;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Create's own air-flow particle, driven by this mod's fan.
 *
 * <p>Nothing about the look is reimplemented here. This is {@code AirFlowParticle}, whose constructor is
 * {@code protected} precisely so it can be given another source: the sprite, the size, the lifetime, the
 * translucency, the drift of the spawn, and the color and alpha of plain air all come from Create's class, and
 * so does everything a processing type does on top of it — {@code morphAirFlow} takes an access object, and
 * what is handed to it is Create's own, three delegates to this particle.
 *
 * <p>The one method that cannot be Create's is {@link #tick()}. Create's reads the flow off an
 * {@code IAirCurrentSource} and an {@code AirCurrent}, whose direction is a {@code Direction}: the motion is
 * that axis scaled by an eighth, the distance along the flow is the component of the position along that same
 * axis, and the sprite is chosen from that distance. A diagonal flow has no such axis, so the body below is
 * that body with the axis replaced by {@link AirFlowSource#flowDirectionAt(Vec3)} — the direction the flow has
 * at the particle's own position — and the component by a dot product. A radial flow (the nozzle, see
 * {@link NozzleFanCurrent}) has no single direction to project onto at all, so there the direction is the
 * outward one at the position and the distance along the flow is the distance from the centre; the motion
 * magnitude, the sprite choice and the lifetime are Create's in all three cases.
 *
 * <p>Why there is a particle of our own at all: {@code create:air_flow}'s factory looks the coordinate in its
 * data up as a block entity and requires it to be an {@code IAirCurrentSource}, so a fan whose source is a
 * player produces puffs that remove themselves on their first tick. This one is handed the flow by the
 * factory, which finds it by position.
 *
 * <p>Client-only, and reached only from the particle registration, which is client-only as well.
 */
public class FanAirParticle extends AirFlowParticle {

    private final AirFlowSource flow;
    private final Access access = new Access();

    /**
     * @param flow the air this particle is travelling in, found by the factory from where it was spawned.
     */
    private FanAirParticle(ClientLevel level, AirFlowSource flow, double x, double y, double z, SpriteSet sprites) {
        // Create's constructor takes a source and keeps it for its own tick to read — the tick replaced below.
        // No block entity stands behind a flow whose source is a player's hand, and a diagonal flow has none
        // at any position, so what is passed here is null: nothing else in Create's class touches it.
        super(level, null, x, y, z, sprites);
        this.flow = flow;
    }

    @Override
    public void tick() {
        if (!flow.isBlowing()) {
            remove();
            return;
        }
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }

        Vec3 position = new Vec3(x, y, z);
        Vec3 direction = flow.flowDirection();
        if (direction.lengthSqr() < 1.0E-8) {
            remove();
            return;
        }
        direction = direction.normalize();

        if (!flow.flowContains(position)) {
            remove();
            return;
        }

        // Where along the flow this particle is: the component of the position along the flow's direction,
        // from the face of the source, as Create measures it.
        float maxDistance = flow.flowMaxDistance();
        double distance = position.subtract(flow.flowOrigin())
            .dot(direction) - .5f;
        if (distance > maxDistance + 1 || distance < -.25f) {
            remove();
            return;
        }

        // Plain air: Create's own white, its own fade, and its sprite picked from how far along the flow the
        // particle is — wide at the fan, narrow at the end. Processed air: the type's own morph, and the first
        // three sprites, which are the ones that carry its color.
        FanProcessingType type = flow.flowTypeAt((float) distance);
        if (type == null) {
            setColor(0xEEEEEE);
            setAlpha(.25f);
            setSprite(sprites.get((int) Mth.clamp((distance / maxDistance) * 8 + random.nextInt(4), 0, 7), 8));
        } else {
            type.morphAirFlow(access, random);
            setSprite(sprites.get(random.nextInt(3), 8));
        }

        Vec3 motion = direction.scale(1 / 8f)
            .scale(maxDistance - (distance - 1f))
            .scale(.5f);
        xd = motion.x;
        yd = motion.y;
        zd = motion.z;

        if (onGround) {
            this.xd *= 0.7;
            this.zd *= 0.7;
        }
        move(xd, yd, zd);
    }

    /** Create's own access object, for a processing type to recolor and embellish the particle with. */
    private final class Access implements FanProcessingType.AirFlowParticleAccess {

        @Override
        public void setColor(int color) {
            FanAirParticle.this.setColor(color);
        }

        @Override
        public void setAlpha(float alpha) {
            FanAirParticle.this.setAlpha(alpha);
        }

        @Override
        public void spawnExtraParticle(ParticleOptions options, float speedMultiplier) {
            level.addParticle(options, x, y, z, xd * speedMultiplier, yd * speedMultiplier, zd * speedMultiplier);
        }
    }

    /** Fed the sprite set by the particle engine, and the flow by where the particle was spawned. */
    public static class Factory implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;

        public Factory(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        @Nullable
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            AirFlowSource flow = FanCurrent.flowAt(level, x, y, z);
            // No air here: no particle. Anything else would be a puff with nothing blowing it.
            return flow == null ? null : new FanAirParticle(level, flow, x, y, z, sprites);
        }
    }
}
