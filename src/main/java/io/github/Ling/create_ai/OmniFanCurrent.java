package io.github.Ling.create_ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllTags;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessing;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The air a {@link HandheldFanItem} is blowing, along the direction the player is actually looking: the
 * omnidirectional flow, and the one the fan uses by default (see {@link FanCurrent#MODE}).
 *
 * <p>Create's air is axis-aligned by construction. {@code AirCurrent#direction} is a {@code Direction} — one of
 * the six axes — and everything downstream of it is written against that single vector: the box it builds its
 * bounds from, the blocks it walks, the distance it measures an entity at, and the motion and sprite morph of
 * its air-flow particle. A player aiming between two axes cannot be expressed in any of that, so this class
 * keeps none of it. The flow is a ray: its segments are distances along the ray, its reach is where the ray is
 * blocked, and an entity is in it when it is close enough to the ray, not when it is inside an axis-aligned box.
 *
 * <p>Everything else about the air is Create's own, and is deliberately not reimplemented:
 *
 * <ul>
 * <li>which processing the air carries comes from {@link FanProcessingType#getAt(Level, BlockPos)}, the same
 *     lookup {@code AirCurrent#rebuild} makes while it walks its flow, over Create's registry of processing
 *     types. That is what makes a catalyst added by Create or by another mod work in front of this fan exactly
 *     as it does in front of an encased one, with Create's own priority order and its own validity rules (a
 *     campfire has to be lit, a blaze burner hot enough, a lit burner's flame of the right color);</li>
 * <li>what the air does to an item — the processing time, the conversion, the extra stacks, the particles —
 *     is {@link FanProcessing#canProcess} and {@link FanProcessing#applyProcessing}, the methods Create's own
 *     current calls;</li>
 * <li>what it does to anything else is the processing type's {@code affectEntity};</li>
 * <li>the push is Create's arithmetic, with the axis replaced by the look vector and the axis-aligned box by a
 *     distance from the ray, and the holder of the fan is left out of it;</li>
 * <li>items sitting on a depot or a belt the air crosses are processed through Create's
 *     {@code TransportedItemStackHandlerBehaviour}, the same way {@code AirCurrent#tickAffectedHandlers}
 *     does it.</li>
 * </ul>
 *
 * <p>What the axis-aligned current gives that this one does not: Create's ambient wind sound for an entity in
 * a current (the sound of a player riding the wind is client code, reached through Create's own platform
 * abstraction and its own client class), and the {@code ServerPlayer#connection.aboveGroundTickCount} reset,
 * which is a field Create reaches through an access transformer. Neither is reachable from outside Create; the
 * holder is not affected by their own fan in any case, so the flying-kick concern applies only to other
 * players standing in the flow.
 *
 * <p>State lives per side and per player, in two maps: a client and its integrated server share one JVM, so a
 * single map would let one side's current be stepped by the other's ticks.
 */
// A Level is AutoCloseable, and a level read through here is never this mod's to close.
@SuppressWarnings("resource")
public final class OmniFanCurrent implements AirFlowSource {

    /** How far the fan reaches, in blocks — the same reach the axis-aligned flow has. */
    public static final int RANGE = FanCurrent.RANGE;

    /** How finely the ray is walked. A quarter of a block, so a diagonal ray crosses no block unseen. */
    private static final float STEP = 0.25f;

    /** How far off the ray the air still acts: a fan's one-block corridor, with a little room for aim. */
    private static final double CORRIDOR = 0.75;

    /**
     * How often the world is walked again while the player keeps the same aim. An encased fan does the same,
     * every {@code fanBlockCheckRate} ticks: without it, a catalyst placed in front of a fan that is already
     * blowing would not be noticed until the player looked somewhere else.
     */
    private static final int REBUILD_INTERVAL = 5;

    private static final Map<UUID, OmniFanCurrent> CLIENT = new HashMap<>();
    private static final Map<UUID, OmniFanCurrent> SERVER = new HashMap<>();

    private final Player owner;
    /** The stretches of air that carry a processing, from a distance along the ray onward. */
    private final List<Segment> segments = new ArrayList<>();
    /** The depots and belts the air crosses, with what the air carries where they sit. */
    private final List<AffectedHandler> handlers = new ArrayList<>();

    @Nullable
    private Vec3 direction;
    @Nullable
    private Vec3 origin;
    private float maxDistance;
    private AABB bounds = new AABB(0, 0, 0, 0, 0, 0);
    private int rebuildCooldown;

    private OmniFanCurrent(Player owner) {
        this.owner = owner;
    }

    // --- driving it, from the fan item's use tick -------------------------------------------------

    /**
     * Steps the fan this player is holding, creating it on first use.
     *
     * <p>The owner check is not paranoia: a player who leaves and comes back is a new {@code Player} instance
     * with the same id, and the old flow would carry on blowing from wherever they used to stand.
     */
    public static void tick(Player player) {
        Map<UUID, OmniFanCurrent> active = player.level().isClientSide() ? CLIENT : SERVER;
        OmniFanCurrent fan = active.get(player.getUUID());
        if (fan == null || fan.owner != player)
            active.put(player.getUUID(), fan = new OmniFanCurrent(player));
        fan.tick();
    }

    /** The use ended: stop blowing, and forget the flow so the next one is built fresh. */
    public static void stop(Player player) {
        (player.level().isClientSide() ? CLIENT : SERVER).remove(player.getUUID());
    }

    /** The flow this player is blowing with, or null. Package-private: for tests and probes. */
    @Nullable
    static OmniFanCurrent active(Player player) {
        return (player.level().isClientSide() ? CLIENT : SERVER).get(player.getUUID());
    }

    private void tick() {
        if (isSourceRemoved()) {
            stop(owner);
            return;
        }

        Vec3 look = owner.getLookAngle();
        // A look vector of no length is not a direction; there is nothing to blow along.
        if (look.lengthSqr() < 1.0E-6)
            return;
        look = look.normalize();
        Vec3 eyes = owner.getEyePosition();

        // Aim and position are read fresh: the fan follows the eyes, so the flow is rebuilt whenever either
        // moved, and every few ticks besides. Rebuilding is what walks the ray and picks up the catalyst
        // blocks the air passes through.
        if (direction == null || origin == null || !sameAim(look, eyes) || rebuildCooldown-- <= 0) {
            direction = look;
            origin = eyes;
            rebuild();
            rebuildCooldown = REBUILD_INTERVAL;
        }

        tickAffectedEntities(owner.level());
        tickAffectedHandlers();
        if (owner.level().isClientSide())
            showFlow();
    }

    /** Whether the player is still aiming where they were, from where they were, to within a fraction. */
    private boolean sameAim(Vec3 look, Vec3 eyes) {
        return direction != null && origin != null && eyes.distanceToSqr(origin) < 0.01
            && direction.distanceToSqr(look) < 1.0E-4;
    }

    // --- building the flow ------------------------------------------------------------------------

    /**
     * Walks the ray from the eyes, once: what the air reaches, what it carries where, and what it crosses.
     *
     * <p>Two things end the walk early. An unloaded block, because there is nothing to judge there yet, and a
     * block the air cannot pass — anything with a collision shape that Create does not list as transparent to a
     * fan's air ({@code create:fan_transparent} holds bars, grates, fences, campfires and leaves). The reach is
     * then the last distance the air got to, which is {@value #RANGE} blocks along an open ray. The first
     * sample sits a quarter of a block from the eyes, inside the player's own head block: that block is air in
     * every ordinary case, so the flow starts at the player.
     */
    private void rebuild() {
        segments.clear();
        handlers.clear();

        Vec3 direction = this.direction;
        Vec3 origin = this.origin;
        if (direction == null || origin == null)
            return;

        Level level = owner.level();
        FanProcessingType carried = null;
        float reach = 0;
        for (float at = STEP; at <= RANGE + 1.0E-4f; at += STEP) {
            BlockPos pos = BlockPos.containing(origin.add(direction.scale(at)));
            if (!level.isLoaded(pos))
                break;
            BlockState state = level.getBlockState(pos);
            if (!AllTags.AllBlockTags.FAN_TRANSPARENT.matches(state)
                && !state.getCollisionShape(level, pos)
                    .isEmpty())
                break;

            // The catalyst the air is passing through here — Create's own lookup, so every registered
            // processing type answers, with Create's priority order and its own rules about what counts.
            FanProcessingType found = FanProcessingType.getAt(level, pos);
            if (found != null && found != carried) {
                carried = found;
                // Create starts a stretch one step before the catalyst it found; one step here is one sample.
                segments.add(new Segment(reach, carried));
            }
            reach = at;
        }

        maxDistance = reach;
        findAffectedHandlers(level, direction, origin);
        findBounds(direction, origin);
    }

    /**
     * An axis-aligned box around the whole ray, wide enough to hold anything the air can act on.
     *
     * <p>This is only what the entity scan is broad-phased with — the air itself is a corridor around the ray,
     * not a box, and every candidate this returns is judged against the ray again. A diagonal ray's box is
     * generous; a box that is one block wide is not something a diagonal direction has.
     */
    private void findBounds(Vec3 direction, Vec3 origin) {
        Vec3 end = origin.add(direction.scale(maxDistance));
        bounds = new AABB(Math.min(origin.x, end.x) - CORRIDOR, Math.min(origin.y, end.y) - CORRIDOR,
            Math.min(origin.z, end.z) - CORRIDOR, Math.max(origin.x, end.x) + CORRIDOR,
            Math.max(origin.y, end.y) + CORRIDOR, Math.max(origin.z, end.z) + CORRIDOR);
    }

    /**
     * The depots and belts the air crosses, and what the items on them are processed by.
     *
     * <p>Create looks one block below the flow as well as at it, because the stack on a depot sits above the
     * depot's own block, and does not do that where the flow is vertical. The blocks are remembered so that a
     * block sampled several times along a diagonal ray adds its handler once: a handler told twice a tick would
     * process its items at twice the speed.
     */
    private void findAffectedHandlers(Level level, Vec3 direction, Vec3 origin) {
        if (maxDistance < STEP)
            return;
        boolean vertical = Math.abs(direction.y) > 0.99;
        Set<BlockPos> seen = new HashSet<>();
        for (float at = STEP; at <= maxDistance + 1.0E-4f; at += STEP) {
            BlockPos pos = BlockPos.containing(origin.add(direction.scale(at)));
            if (seen.add(pos))
                addHandler(level, pos, at);
            if (!vertical && seen.add(pos.below()))
                addHandler(level, pos.below(), at);
        }
    }

    private void addHandler(Level level, BlockPos pos, float offset) {
        TransportedItemStackHandlerBehaviour handler =
            BlockEntityBehaviour.get(level, pos, TransportedItemStackHandlerBehaviour.TYPE);
        if (handler == null)
            return;
        // What the items there are processed by: the catalyst in that very block if it is one, else whatever
        // the air carries at that distance, which is Create's own order in findAffectedHandlers.
        FanProcessingType type = FanProcessingType.getAt(level, pos);
        if (type == null)
            type = flowTypeAt(offset);
        handlers.add(new AffectedHandler(handler, type));
    }

    /**
     * The air the fan is blowing, drawn with Create's own air-flow particle.
     *
     * <p>One particle, at the source, as an encased fan's air does it: the particle travels the flow itself —
     * wide and soft at the fan, tapering as it goes, taking the appearance of whatever processing the air
     * carries — so the trail is its doing, not ours. Create's own {@code AirCurrent#tick} spawns its particle
     * here too, but through {@code AirFlowParticleData}, whose factory looks that coordinate up as a block
     * entity and needs an {@code IAirCurrentSource}: a fan whose source is a player has none, and those puffs
     * would remove themselves on their first tick.
     */
    private void showFlow() {
        Level level = owner.level();
        Vec3 origin = this.origin;
        Vec3 direction = this.direction;
        if (origin == null || direction == null || maxDistance < 0.5f)
            return;
        // Half the ticks: Create's own density is a client config, and this is roughly its default.
        if (level.random.nextFloat() > 0.5f)
            return;
        Vec3 at = origin.add(direction.scale(0.5));
        level.addParticle(Create_ai.FAN_AIR.get(), at.x, at.y, at.z, 0, 0, 0);
    }

    // --- what the air does ------------------------------------------------------------------------

    /**
     * What the air does to what is in it: Create's own arithmetic, with the flow as a vector.
     *
     * <p>{@code AirCurrent#tickAffectedEntities} reads its entities out of a cache it refills every five ticks
     * and cannot be filtered from outside, which is why the scan is repeated here every tick instead — that is
     * also what lets the holder be left out of it. A fan's reach in front of one player costs nothing.
     *
     * <p>The air acts on an entity whose center is within {@link #CORRIDOR} of the ray and within
     * {@link #maxDistance} along it. Create's test is the entity's bounding box against a box one block wide
     * around the axis; this is the same idea with the width measured as a distance, which is what an arbitrary
     * direction can be measured by.
     */
    private void tickAffectedEntities(Level level) {
        Vec3 direction = this.direction;
        Vec3 origin = this.origin;
        if (direction == null || origin == null || maxDistance < STEP)
            return;

        for (Entity entity : level.getEntities(null, bounds)) {
            // The holder is not blown by the fan they are holding. Everything below is Create's.
            if (entity == owner)
                continue;
            if (!entity.isAlive() || AirCurrent.isPlayerCreativeFlying(entity))
                continue;

            Vec3 relative = entity.getBoundingBox()
                .getCenter()
                .subtract(origin);
            double along = relative.dot(direction);
            if (along < 0 || along > maxDistance)
                continue;
            if (relative.subtract(direction.scale(along))
                .lengthSqr() > CORRIDOR * CORRIDOR)
                continue;

            // A fan held in a hand only ever pushes. Create's current is a pushing one when its flow direction
            // matches its origin side, which for us it always does, so there is no pull to reverse here and
            // the push is along the flow's own direction.
            float speed = Math.abs(FanCurrent.SPEED);
            float sneakModifier = entity.isShiftKeyDown() ? 4096f : 512f;
            // Create has this same division by the distance from the source; the guard is only for the case of
            // an entity exactly at the eyes, where it would be a division by zero.
            double entityDistanceOld = Math.max(entity.position()
                .distanceTo(origin), 1 / 1024d);
            float acceleration = (float) (speed / sneakModifier / (entityDistanceOld / maxDistance));
            Vec3 previousMotion = entity.getDeltaMovement();
            float maxAcceleration = 5;

            double xIn =
                Mth.clamp(direction.x * acceleration - previousMotion.x, -maxAcceleration, maxAcceleration);
            double yIn =
                Mth.clamp(direction.y * acceleration - previousMotion.y, -maxAcceleration, maxAcceleration);
            double zIn =
                Mth.clamp(direction.z * acceleration - previousMotion.z, -maxAcceleration, maxAcceleration);

            entity.setDeltaMovement(previousMotion.add(new Vec3(xIn, yIn, zIn).scale(1 / 8f)));
            entity.fallDistance = 0;

            FanProcessingType processingType = flowTypeAt((float) along);
            if (processingType == null)
                continue;

            if (entity instanceof ItemEntity itemEntity) {
                if (level.isClientSide()) {
                    processingType.spawnProcessingParticles(level, entity.position());
                    continue;
                }
                if (FanProcessing.canProcess(itemEntity, processingType))
                    FanProcessing.applyProcessing(itemEntity, processingType);
                continue;
            }
            processingType.affectEntity(entity, level);
        }
    }

    /**
     * Create's processing of the items on a depot or a belt the air crosses, with the processing type that
     * belongs to that block. A straightforward transcription of {@code AirCurrent#tickAffectedHandlers}, minus
     * the advancement Create awards an encased fan for it.
     */
    private void tickAffectedHandlers() {
        for (AffectedHandler affected : handlers) {
            TransportedItemStackHandlerBehaviour handler = affected.handler;
            FanProcessingType processingType = affected.type;
            if (processingType == null)
                continue;

            Level level = handler.getWorld();
            handler.handleProcessingOnAllItems(transported -> {
                if (level.isClientSide()) {
                    processingType.spawnProcessingParticles(level, handler.getWorldPositionOf(transported));
                    return TransportedResult.doNothing();
                }
                return FanProcessing.applyProcessing(transported, level, processingType);
            });
        }
    }

    /**
     * The fan whose air covers a point. The particle only has a position to go on — its factory is handed
     * coordinates, not a flow — so the live currents are searched for one whose corridor contains it.
     */
    @Nullable
    static OmniFanCurrent sourceAt(Level level, double x, double y, double z) {
        Vec3 at = new Vec3(x, y, z);
        for (OmniFanCurrent fan : (level.isClientSide() ? CLIENT : SERVER).values()) {
            if (fan.flowContains(at))
                return fan;
        }
        return null;
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
        return origin == null ? Vec3.ZERO : origin;
    }

    @Override
    public Vec3 flowDirection() {
        return direction == null ? Vec3.ZERO : direction;
    }

    @Override
    public float flowMaxDistance() {
        return maxDistance;
    }

    /** Inside the corridor: within {@link #CORRIDOR} of the ray, and along it between its ends. */
    @Override
    public boolean flowContains(Vec3 at) {
        Vec3 direction = this.direction;
        Vec3 origin = this.origin;
        if (direction == null || origin == null)
            return false;
        Vec3 relative = at.subtract(origin);
        double along = relative.dot(direction);
        if (along < -0.25 || along > maxDistance + 1)
            return false;
        return relative.subtract(direction.scale(along))
            .lengthSqr() <= (CORRIDOR + 0.25) * (CORRIDOR + 0.25);
    }

    /**
     * What the air carries at a distance along the ray: the catalyst the flow passes through there, and
     * otherwise whatever is installed in the fan — the air leaving a fan with a soul campfire in its socket
     * behaves as though it had already been through one. The level is the one the fan is being used in, which
     * is what lets the socket resolve a catalyst that only a level can answer about.
     */
    @Override
    @Nullable
    public FanProcessingType flowTypeAt(float offset) {
        FanProcessingType fromWorld = null;
        for (Segment segment : segments) {
            if (offset < segment.start)
                break;
            fromWorld = segment.type;
        }
        if (fromWorld != null)
            return fromWorld;
        return HandheldFanItem.installedProcessing(owner.getUseItem(), owner.level());
    }

    /** The name Create's sources answer this question by: the air is gone when the fan is no longer held. */
    public boolean isSourceRemoved() {
        return !isHeld();
    }

    // --- the pieces of a flow ---------------------------------------------------------------------

    /** A stretch of air carrying one processing, from a distance along the ray onward. */
    private static final class Segment {

        final float start;
        @Nullable
        final FanProcessingType type;

        Segment(float start, @Nullable FanProcessingType type) {
            this.start = start;
            this.type = type;
        }
    }

    /** A depot or a belt the air crosses, with the processing the air carries where it sits. */
    private static final class AffectedHandler {

        final TransportedItemStackHandlerBehaviour handler;
        @Nullable
        final FanProcessingType type;

        AffectedHandler(TransportedItemStackHandlerBehaviour handler, @Nullable FanProcessingType type) {
            this.handler = handler;
            this.type = type;
        }
    }
}
