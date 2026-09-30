package io.github.Ling.create_ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllTags;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The air a {@link HandheldFanItem} blows when Create's nozzle — {@code create:nozzle}, the 分散网 — is
 * installed in its socket: the fan's air spread in every direction around the player instead of down the
 * single line the player is looking along.
 *
 * <p><b>What is being mirrored.</b> Create's {@code NozzleBlockEntity} does not blow along six axes. It
 * sits at the output of a fan, takes that fan's own {@code getMaxDistance} as its range, and every tick
 * pushes everything within that range <em>away from its own center</em> — one radial push field, built from
 * the vector from the nozzle to each entity, not six separate currents. It does nothing else: that class
 * holds no processing code at all, and a catalyst standing in the field is not reacted with. That is
 * exactly the shape this class has: one field, centered on the point the fan's air leaves the player,
 * reaching {@link FanCurrent#RANGE} blocks in all directions, pushing outward and doing nothing else.
 *
 * <p>So there is deliberately no "six currents" here. Six axis-aligned currents would each need their own
 * entity scan and would each push the same dropped item, three of them adding to its motion at once.
 * Create's nozzle avoids that by being one field, and so does this.
 *
 * <p><b>What is Create's.</b> Only two things are this class's own, and both are consequences of the
 * source being a hand rather than a block: where the center is (a point just in front of the player's eyes,
 * instead of the nozzle block's own center), and the holder (who is not pushed by the fan they are
 * holding). Everything else is {@code NozzleBlockEntity#tick}: the same outward vector, the same
 * item-or-not push factor, the same skipping of an entity that is sneaking or flying in creative, and the
 * same absence of processing.
 *
 * <p><b>The socket holds the nozzle, and while it does, nothing is processed.</b> The nozzle occupies the
 * socket, and with it fitted this flow asks {@link HandheldFanItem#installedProcessing} for nothing and
 * looks for no catalyst in the world either: {@link #flowTypeAt} answers null at every distance, so a
 * campfire, a lava pool or fire inside the field is not reacted with. That is Create's rule, and it is why
 * the socket costs nothing while the nozzle is in it — the fan is not processing at all. A player who wants
 * haunting or blasting takes the nozzle out and puts a soul campfire or a lava bucket in the socket.
 *
 * <p>State lives per side and per player, in two maps, for the same reason every other flow here does: a
 * client and its integrated server share one JVM, so a single map would let one side's field be stepped by
 * the other's ticks.
 */
public final class NozzleFanCurrent implements AirFlowSource {

    /** How far the field reaches, in blocks, in every direction — the fan's own reach. */
    public static final int RANGE = FanCurrent.RANGE;

    /**
     * How often the field is walked again while the player stays put. The same interval the single-direction
     * flow uses: without it, a catalyst placed in a field that is already blowing would not be noticed.
     */
    private static final int REBUILD_INTERVAL = 5;

    /**
     * How far in front of the eyes the field is centered: the fan's output, which is where a nozzle would
     * sit if this fan were a machine. Half a block keeps the player's own body out of the near half of the
     * sphere without moving the field anywhere they cannot see.
     */
    private static final double CENTER_OFFSET = 0.5;

    /**
     * The answer to the one-directional question, which a field does not have: air leaving a point in every
     * direction has no single vector that is its direction everywhere. Nothing reads it — the field is drawn
     * with Create's own POOF puffs and not with this mod's air-flow particle, which is the only thing that
     * ever asked — but the interface still wants an answer, and zero is the honest one.
     */
    private static final Vec3 NO_AIM = Vec3.ZERO;

    private static final Map<UUID, NozzleFanCurrent> CLIENT = new HashMap<>();
    private static final Map<UUID, NozzleFanCurrent> SERVER = new HashMap<>();

    /**
     * Create's own push arithmetic, taken from {@code NozzleBlockEntity#tick}: a dropped item is pushed at
     * this fraction of the outward vector, anything else at four times it.
     */
    private static final float ITEM_PUSH_FACTOR = 1 / 128f;
    private static final float OTHER_PUSH_FACTOR = 1 / 32f;

    private final Player owner;

    @Nullable
    private Vec3 center;
    /** How far the field actually got, which a wall in one direction can shorten. */
    private float reach = RANGE;
    private AABB bounds = new AABB(0, 0, 0, 0, 0, 0);
    private int rebuildCooldown;

    private NozzleFanCurrent(Player owner) {
        this.owner = owner;
    }

    // --- driving it, from the fan item's use tick -------------------------------------------------

    /**
     * Steps the field this player's fan is blowing, creating it on first use.
     *
     * <p>The owner check is not paranoia: a player who leaves and comes back is a new {@code Player}
     * instance with the same id, and the old field would carry on pushing from wherever they used to stand.
     */
    public static void tick(Player player) {
        Map<UUID, NozzleFanCurrent> active = player.level().isClientSide() ? CLIENT : SERVER;
        NozzleFanCurrent nozzle = active.get(player.getUUID());
        if (nozzle == null || nozzle.owner != player)
            active.put(player.getUUID(), nozzle = new NozzleFanCurrent(player));
        nozzle.tick();
    }

    /** The use ended: stop blowing, and forget the field so the next one is built fresh. */
    public static void stop(Player player) {
        (player.level().isClientSide() ? CLIENT : SERVER).remove(player.getUUID());
    }

    /** The field this player is blowing with, or null. Package-private: for tests and probes. */
    @Nullable
    static NozzleFanCurrent active(Player player) {
        return (player.level().isClientSide() ? CLIENT : SERVER).get(player.getUUID());
    }

    private void tick() {
        if (isSourceRemoved()) {
            stop(owner);
            return;
        }

        // The center follows the eyes, so a player who walks or turns carries the field with them. Turning
        // moves it by the half block the offset is worth, which is enough to rebuild a field whose middle
        // has moved inside a different block; the interval below catches everything smaller.
        Vec3 at = owner.getEyePosition()
            .add(owner.getLookAngle()
                .normalize()
                .scale(CENTER_OFFSET));
        if (center == null || center.distanceToSqr(at) > 1.0E-4 || rebuildCooldown-- <= 0) {
            center = at;
            rebuild();
            rebuildCooldown = REBUILD_INTERVAL;
        }

        tickAffectedEntities(owner.level());
        if (owner.level().isClientSide())
            showFlow();
    }

    // --- building the field -----------------------------------------------------------------------

    /**
     * Settles the field's range and the box the entity scan goes through. Before this it was a walk over
     * the blocks the air could pass, which is gone with the last of the processing: a nozzle's air carries
     * nothing, and nothing about the blocks around it changes what its range is.
     */
    private void rebuild() {
        Vec3 center = this.center;
        if (center == null)
            return;

        // The field reaches its full range, walls or not. That is Create's own rule for a nozzle: its range
        // is the fan's own max distance, taken straight off the fan, and no block is consulted at all — what
        // a wall does is hide an entity from the field, and that is checked per entity instead (see
        // tickAffectedEntities).
        //
        // Measuring the range by walking the six axes was a mistake worth naming, because it is what made
        // the nozzle do nothing at all in play: standing on the ground stopped the downward walk at the
        // first block, the shortest of the six came out as zero, and a zero range killed the whole field —
        // no push and no particles. The probe that cleared it ran in open air five blocks above the
        // surface, where all six walks are clear, which is exactly the case that hides this.
        this.reach = RANGE;
        bounds = new AABB(center, center).inflate(RANGE);
    }

    /**
     * The air the fan is blowing, drawn with the particle Create's own nozzle uses: a puff of
     * {@code ParticleTypes.POOF} somewhere around the fan, travelling outward from it.
     *
     * <p>This is {@code NozzleBlockEntity#tick}'s client block, line for line: the same chance per tick,
     * taken against Create's own fan push distance config, the same random start within a block of the
     * centre, and the same outward motion scaled by a twentieth of the range. It is deliberately not this
     * mod's air-flow particle — that one belongs to a fan's directional current, which a nozzle does not
     * have, and Create draws a nozzle with POOF for the same reason.
     */
    private void showFlow() {
        Vec3 center = this.center;
        if (center == null)
            return;
        Level level = owner.level();
        int pushDistance = AllConfigs.server().kinetics.fanPushDistance.get();
        if (level.random.nextInt(Mth.clamp(pushDistance - (int) reach, 1, 10)) != 0)
            return;
        Vec3 start = VecHelper.offsetRandomly(center, level.random, 1);
        // Outward, which is Create's own sign: their line is
        // {@code center.subtract(start).normalize().scale(... * (pushing ? -1 : 1))}, so what leaves the
        // centre is the vector <em>away</em> from it. Leaving that factor out put every puff's velocity
        // towards the middle instead, which is what a puff flying into the player looked like.
        Vec3 motion = start.subtract(center)
            .normalize()
            .scale(Mth.clamp(reach * .025f, 0, .5f));
        level.addParticle(ParticleTypes.POOF, start.x, start.y, start.z, motion.x, motion.y, motion.z);
    }

    // --- what the air does ------------------------------------------------------------------------

    /**
     * What the field does to what is in it: Create's nozzle push, and nothing else.
     *
     * <p>The push is {@code NozzleBlockEntity#tick} line for line — the vector from the center to the
     * entity, scaled by how much of the range is left to it, and then by the item-or-not factor — with the
     * guards a machine with a fixed position does not need: an entity outside the reach is left alone, the
     * holder of the fan is not pushed by it, and a sneaking entity is passed over, which is Create's own
     * guard. No processing is applied to anything: a nozzle's air carries none (see {@link #flowTypeAt}).
     */
    private void tickAffectedEntities(Level level) {
        Vec3 center = this.center;
        if (center == null)
            return;

        for (Entity entity : level.getEntities(null, bounds)) {
            // The holder is not blown by the fan they are holding. Everything below is Create's.
            if (entity == owner)
                continue;
            if (!entity.isAlive() || AirCurrent.isPlayerCreativeFlying(entity))
                continue;
            // Create's own guard: a sneaking entity is not pushed.
            if (entity.isShiftKeyDown())
                continue;

            Vec3 diff = entity.position()
                .subtract(center);
            double distance = diff.length();
            if (distance > reach || distance < 1.0E-6)
                continue;
            // What a wall does to a nozzle's field, in Create's own terms: it hides what is behind it. The
            // range itself is not shortened by anything.
            if (!canSee(level, entity, center))
                continue;

            // Create's own outward push, with its own factor for a dropped item and for anything else,
            // scaled by the mod's one configurable number so the strength can be tuned without touching the
            // range (see Config#nozzlePushStrength).
            float factor = (entity instanceof ItemEntity ? ITEM_PUSH_FACTOR : OTHER_PUSH_FACTOR)
                * (float) Config.nozzlePushStrength();
            Vec3 push = diff.normalize()
                .scale(reach - distance);
            entity.setDeltaMovement(entity.getDeltaMovement()
                .add(push.scale(factor)));
            entity.fallDistance = 0;
            entity.hurtMarked = true;
        }
    }

    /**
     * Whether the field reaches the entity at all: Create's own {@code NozzleBlockEntity#canSee}, a clip
     * from the entity to the centre that has to arrive without a block in the way. Create compares the
     * block its clip landed on with the nozzle's own block; a field's centre is a point in the air rather
     * than a block, so what is asked for here is a clip that misses everything.
     */
    private static boolean canSee(Level level, Entity entity, Vec3 center) {
        ClipContext context = new ClipContext(entity.position(), center, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, entity);
        return level.clip(context)
            .getType() == HitResult.Type.MISS;
    }

    private boolean isHeld() {
        return owner.isUsingItem() && owner.getUseItem()
            .is(Create_ai.HANDHELD_FAN.get());
    }

    // --- AirFlowSource ----------------------------------------------------------------------------

    @Override
    public boolean isBlowing() {
        return !isSourceRemoved();
    }

    @Override
    public Vec3 flowOrigin() {
        return center == null ? Vec3.ZERO : center;
    }

    /**
     * The one direction a field does not have, and the only thing this implementation is asked for: a field
     * is radial, so there is nothing that is the air's direction at every point of it. See {@link #NO_AIM}.
     */
    @Override
    public Vec3 flowDirection() {
        return NO_AIM;
    }

    @Override
    public float flowMaxDistance() {
        return reach;
    }

    /** Inside the field: within the reach of the center, in any direction. */
    @Override
    public boolean flowContains(Vec3 at) {
        Vec3 center = this.center;
        return center != null && at.distanceTo(center) <= reach + 1;
    }

    /**
     * Nothing, ever. A nozzle's air carries no processing: the field pushes and does not react, which is
     * Create's own rule — its {@code NozzleBlockEntity} holds no processing code at all — and it is why a
     * catalyst standing inside the field does nothing. A player who wants haunting or blasting takes the
     * nozzle out of the socket and puts a soul campfire or a lava bucket there instead.
     */
    @Override
    @Nullable
    public FanProcessingType flowTypeAt(float offset) {
        return null;
    }

    /** The name Create's sources answer this question by: the air is gone when the fan is no longer held. */
    public boolean isSourceRemoved() {
        return !isHeld();
    }

}
