package io.github.Ling.create_ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessing;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The air a {@link HandheldFanItem} is blowing, as one of Create's own {@code AirCurrent}s.
 *
 * <p>Everything about how a fan's air behaves is Create's, and this class mostly just answers the
 * questions the current asks its source:
 *
 * <ul>
 * <li>where the flow starts (the holder's eyes), in which direction (the axis they are looking down) and
 *     how fast (a fan's speed, which is what scales both the push and the air-flow particles);</li>
 * <li>how far it reaches — {@link #getMaxDistance} is overridden, because Create's default lerps up to
 *     sixteen blocks of push distance from config and this fan is meant to reach three;</li>
 * <li>and that is all. The processing is not decided here: {@code AirCurrent#rebuild} walks the flow,
 *     asks {@code FanProcessingType.getAt} about every block it passes and records which processing each
 *     stretch of air carries, so fire, soul fire, lava and water in front of the fan work exactly as they
 *     do in front of an encased one.</li>
 * </ul>
 *
 * <p>The one thing that is not Create's behaviour is who the air acts on. A real fan blows its own owner
 * as readily as anyone else; a hand-held one should not, so {@link BlowingCurrent} subclasses the current
 * and leaves the holder out of it. That is also why the entity scan is repeated every tick instead of
 * using Create's private cache of caught entities — a cache that cannot be filtered from outside.
 *
 * <p>State lives per side and per player, in two maps: a client and its integrated server share one JVM,
 * so a single map would let one side's current be stepped by the other's ticks.
 *
 * <p>This is now the fan's <em>other</em> airflow: what it blows along is one of the six axes, snapped from
 * the look vector, which is the one thing {@link OmniFanCurrent} was written to do better. It is left whole
 * on purpose — see {@link #MODE} for the switch between the two, and for which one the fan uses. Nothing here
 * is dead: flipping that single constant puts the fan back on this flow.
 */
public final class FanCurrent implements IAirCurrentSource, AirFlowSource {

    /**
     * Which of the fan's two airflows a held fan drives.
     *
     * <p>The fan was first built as a copy of Create's own air — an {@code AirCurrent}, aimed along the axis
     * the player was looking down — and this class is still exactly that. The omnidirectional flow that
     * replaced it as the default is {@link OmniFanCurrent}: the same air and the same processing, along the
     * direction the player is actually aiming.
     */
    public enum Mode {

        /** The flow follows the look vector, diagonals included. {@link OmniFanCurrent}. */
        OMNIDIRECTIONAL,

        /** The original flow, snapped to the nearest of the six axes. This class. */
        AXIS_ALIGNED
    }

    /**
     * The switch between the two flows: the one line to edit to go back to the axis-aligned fan.
     *
     * <p>Nothing else has to change. Both flows are driven from the same place — {@link #tick(Player)} and
     * {@link #stop(Player)}, which the fan item already calls on every tick of its use — and both are compiled
     * into the jar, so flipping this constant is the whole rollback.
     */
    public static final Mode MODE = Mode.OMNIDIRECTIONAL;

    /** How far the fan reaches, in blocks. The reason {@link #getMaxDistance} exists here at all. */
    public static final int RANGE = 4;

    /**
     * Create's nozzle, the item that spreads a fan's air in all directions. Named by id because the nozzle
     * is Create's own block, registered under Create's namespace and not ours — the same way
     * {@link SocketCatalyst} reads the catalysts it is asked about, and for the same reason: what travels
     * on a stack is an id.
     */
    private static final ResourceLocation NOZZLE_ID = ResourceLocation.fromNamespaceAndPath("create", "nozzle");

    /**
     * The speed the current reports; a fan's worth, and what the air-flow particles are scaled by. The
     * omnidirectional flow reports the same speed, so flipping {@link #MODE} changes the aim and nothing else.
     */
    static final float SPEED = 256f;

    private static final Map<UUID, FanCurrent> CLIENT = new HashMap<>();
    private static final Map<UUID, FanCurrent> SERVER = new HashMap<>();

    private final Player owner;
    private final AirCurrent current = new BlowingCurrent();

    @Nullable
    private Direction direction;
    @Nullable
    private BlockPos origin;

    private FanCurrent(Player owner) {
        this.owner = owner;
    }

    // --- driving it, from the fan item's use tick -------------------------------------------------

    /**
     * Steps the fan this player is holding, creating it on first use.
     *
     * <p>One thing is decided before the mode is looked at: a fan with Create's nozzle in its socket does
     * not blow down a line at all, whichever flow {@link #MODE} selects — the nozzle spreads the air in
     * every direction, and that is {@link NozzleFanCurrent}. A machine's nozzle is fitted at the fan's
     * output and is the last thing the air passes; a fan held in a hand has one socket, so the same item
     * is read the same way here.
     *
     * <p>The owner check is not paranoia: a player who leaves and comes back is a new {@code Player}
     * instance with the same id, and the old current would carry on blowing from wherever they used to
     * stand.
     */
    public static void tick(Player player) {
        if (hasNozzle(player)) {
            NozzleFanCurrent.tick(player);
            return;
        }
        if (MODE == Mode.OMNIDIRECTIONAL) {
            OmniFanCurrent.tick(player);
            return;
        }
        Map<UUID, FanCurrent> active = player.level().isClientSide ? CLIENT : SERVER;
        FanCurrent fan = active.get(player.getUUID());
        if (fan == null || fan.owner != player)
            active.put(player.getUUID(), fan = new FanCurrent(player));
        fan.tick();
    }

    /**
     * The use ended: stop blowing, and forget the current so the next one is built fresh.
     *
     * <p>Every flow is asked to forget, not just the one the last tick used: the socket can have been
     * filled or emptied mid-hold, and a flow left behind by the other branch would keep ticking from a
     * stale aim the next time it was reached.
     */
    public static void stop(Player player) {
        NozzleFanCurrent.stop(player);
        if (MODE == Mode.OMNIDIRECTIONAL) {
            OmniFanCurrent.stop(player);
            return;
        }
        (player.level().isClientSide ? CLIENT : SERVER).remove(player.getUUID());
    }

    /**
     * Whether the fan this player is holding has Create's nozzle in its socket.
     *
     * <p>Asked of the item id and nothing else. The nozzle's own behaviour is a block entity that reads the
     * fan it is attached to, which a hand tool has no analogue of, so what the item means here is decided by
     * this mod ({@link NozzleFanCurrent}) — and the id is the one fact that travels with the stack. A fan
     * with something else in the socket, or with nothing, blows down a line as it always did.
     */
    public static boolean hasNozzle(Player player) {
        ItemStack held = player.getUseItem();
        if (held.isEmpty() || !held.is(Create_ai.HANDHELD_FAN.get()))
            return false;
        return isNozzle(HandheldFanItem.installed(held));
    }

    /**
     * Whether what is installed in a socket is Create's nozzle, by id.
     *
     * <p>The stack's item is read out of the item registry rather than tested with {@code is}, because in
     * this version an {@code ItemStack} cannot be asked about an id: the overloads take an item, a tag or a
     * holder, and there is no id among them. The lookup is the same one {@link HandheldFanItem#installed}
     * makes to turn an id back into a stack, read the other way round.
     *
     * <p>An item that does not exist in the registry resolves to air and compares false, so a stack whose
     * id belongs to no item is not mistaken for a nozzle.
     */
    public static boolean isNozzle(@Nullable ItemStack installed) {
        if (installed == null || installed.isEmpty())
            return false;
        return NOZZLE_ID.equals(BuiltInRegistries.ITEM.getKey(installed.getItem()));
    }

    /**
     * The current this player is blowing with, whichever kind it is, or null. Package-private: for tests
     * and probes, which need one entry point rather than three.
     */
    @Nullable
    static Object activeOf(Player player) {
        Object nozzle = NozzleFanCurrent.active(player);
        if (nozzle != null)
            return nozzle;
        if (MODE == Mode.OMNIDIRECTIONAL)
            return OmniFanCurrent.active(player);
        return active(player);
    }

    /** The current this player is blowing with, or null. Package-private: for tests and probes. */
    @Nullable
    static FanCurrent active(Player player) {
        return (player.level().isClientSide ? CLIENT : SERVER).get(player.getUUID());
    }

    private void tick() {
        if (isSourceRemoved()) {
            stop(owner);
            return;
        }

        // Aim and position are read fresh: the fan follows the eyes, so the flow is rebuilt whenever
        // either changed. Rebuilding is what walks the flow and picks up the catalyst blocks in it.
        Direction facing = facing();
        BlockPos eyes = BlockPos.containing(owner.getEyePosition());
        if (facing != direction || !eyes.equals(origin)) {
            direction = facing;
            origin = eyes;
            current.direction = facing;
            current.rebuild();
        }
        current.tick();
        if (owner.level().isClientSide)
            showFlow();
    }

    /**
     * The air the fan is blowing, drawn with Create's own air-flow particle.
     *
     * <p>One particle, at the source, exactly as an encased fan's air does it: the particle travels the flow
     * itself — wide and soft beside the fan, tapering as it goes, taking the colour of whatever processing
     * the air carries — so the trail is its doing, not ours. Spawning along the flow instead, as an earlier
     * attempt did, is what made a curtain of puffs that no amount of density tuning could make look like air.
     */
    private void showFlow() {
        Level level = owner.level();
        if (direction == null || origin == null || current.maxDistance < 0.5f)
            return;
        // Half the ticks: Create's own density is a client config, and this is roughly its default.
        if (level.random.nextFloat() > 0.5f)
            return;
        Vec3 at = VecHelper.getCenterOf(origin)
            .add(Vec3.atLowerCornerOf(direction.getNormal())
                .scale(0.5));
        level.addParticle(Create_ai.FAN_AIR.get(), at.x, at.y, at.z, 0, 0, 0);
    }

    /**
     * The fan whose air covers a point, of this axis-aligned sort. The particle only has a position to go on —
     * its factory is handed coordinates, not a source — so the live currents are searched for one whose bounds
     * contain it.
     */
    @Nullable
    static FanCurrent sourceAt(Level level, double x, double y, double z) {
        for (FanCurrent fan : (level.isClientSide ? CLIENT : SERVER).values()) {
            if (fan.current.bounds.inflate(.25f)
                .contains(x, y, z))
                return fan;
        }
        return null;
    }

    /**
     * The air a particle at a point is in, whichever flow is blowing there.
     *
     * <p>Only one of the two is ever being ticked for a player — the fan routes to one flow per tick — so in
     * the ordinary case only one of the maps holds a flow. It can matter for one tick's worth of edge case: a
     * socket changed while the key is still held leaves the flow that was blowing until the use ends, since
     * nothing calls {@link #stop(Player)} on the way.
     *
     * <p>A nozzle-fitted fan has no air-flow particle to place: its air is drawn with Create's own POOF puffs,
     * the way Create's nozzle draws it (see {@code NozzleFanCurrent#showFlow}), and not with this mod's
     * air-flow particle, so there is no field of ours to look up for one.
     *
     * <p>Package-private, and common code: the factory that calls it is client-only, and only the values it is
     * handed back are ever used there.
     */
    @Nullable
    static AirFlowSource flowAt(Level level, double x, double y, double z) {
        if (MODE == Mode.OMNIDIRECTIONAL)
            return OmniFanCurrent.sourceAt(level, x, y, z);
        return sourceAt(level, x, y, z);
    }

    /** The axis the player is looking down, which is as close as a one-directional flow can get to aim. */
    private Direction facing() {
        Vec3 look = owner.getLookAngle();
        return Direction.getNearest(look.x, look.y, look.z);
    }

    private boolean isHeld() {
        return owner.isUsingItem() && owner.getUseItem()
            .is(Create_ai.HANDHELD_FAN.get());
    }

    // --- IAirCurrentSource ------------------------------------------------------------------------

    @Override
    public AirCurrent getAirCurrent() {
        return current;
    }

    @Override
    public Level getAirCurrentWorld() {
        return owner.level();
    }

    @Override
    public BlockPos getAirCurrentPos() {
        // The flow starts at the face of the block the eyes are in, so the air reaches three blocks of
        // open space in front of the player.
        return origin == null ? owner.blockPosition() : origin;
    }

    @Override
    public float getSpeed() {
        return SPEED;
    }

    @Override
    public Direction getAirflowOriginSide() {
        // Create's convention, read off AirCurrent#rebuild: the current's direction *is* this value
        // (there is no inversion anywhere), and the current is a pushing one when getAirFlowDirection
        // matches it. Returning the opposite here is what a first attempt did, and it made the fan blow
        // backwards: no catalyst ahead of the player was ever found.
        return facing();
    }

    @Override
    public Direction getAirFlowDirection() {
        // Identical to the flow's own direction, which is what makes the current a pushing one: Create
        // compares the two to decide.
        return facing();
    }

    /** Overridden: Create's default reaches up to sixteen blocks, and this fan reaches {@value #RANGE}. */
    @Override
    public float getMaxDistance() {
        return RANGE;
    }

    @Override
    public boolean isSourceRemoved() {
        return !isHeld();
    }

    // --- AirFlowSource: this current as the air-flow particle sees it -------------------------------

    /**
     * The same handful of facts, with the direction as a vector instead of an axis, so that one particle class
     * can draw either flow (see {@link AirFlowSource}). For an axis-aligned current the vector is simply the
     * normal of the {@code Direction} the current is aimed down.
     */
    @Override
    public boolean isBlowing() {
        return !isSourceRemoved();
    }

    @Override
    public Vec3 flowOrigin() {
        return origin == null ? Vec3.ZERO : VecHelper.getCenterOf(origin);
    }

    @Override
    public Vec3 flowDirection() {
        return direction == null ? Vec3.ZERO : Vec3.atLowerCornerOf(direction.getNormal());
    }

    @Override
    public float flowMaxDistance() {
        return current.maxDistance;
    }

    /** Create's own test for a point being in a current, which is what its particle asks. */
    @Override
    public boolean flowContains(Vec3 at) {
        return current.bounds.inflate(.25f)
            .contains(at);
    }

    /**
     * What the air carries at a distance along the flow, the socket included: {@link BlowingCurrent} adds the
     * installed item's processing wherever the flow itself carries none, and the omnidirectional flow does the
     * same.
     */
    @Override
    @Nullable
    public FanProcessingType flowTypeAt(float offset) {
        return current.getTypeAt(offset);
    }

    // --- the current itself -----------------------------------------------------------------------

    /**
     * Create's air current, with its holder left out of the entities it acts on.
     *
     * <p>{@code AirCurrent#tickAffectedEntities} is the whole of what a current does to entities —
     * acceleration, fall distance, processing — and it is {@code protected} for exactly this kind of
     * adjustment, so this is the same body with one line added at the top. The instance caches it keeps
     * are private, hence the fresh scan; three blocks in front of one player costs nothing.
     *
     * <p>What is lost with the override is Create's private ambient-wind sound for entities standing in a
     * current, which cannot be called from here. The air-flow particles are not affected: those come from
     * {@code AirCurrent#tick}, which still runs.
     */
    private final class BlowingCurrent extends AirCurrent {

        /**
         * Create's tick, without its particle: {@code AirCurrent#tick} also spawns an
         * {@code AirFlowParticleData} beside the source every tick, which is a second set of particles in a
         * second place. Its factory looks that coordinate up as a block entity and requires an
         * {@code IAirCurrentSource}, so for a fan whose source is a player those puffs are removed on their
         * first tick — a flicker at the fan and nothing more. The flow is drawn by {@link #showFlow} instead.
         */
        @Override
        public void tick() {
            if (direction == null)
                rebuild();
            Level world = source.getAirCurrentWorld();
            if (world == null)
                return;
            tickAffectedEntities(world);
            tickAffectedHandlers();
        }

        BlowingCurrent() {
            super(FanCurrent.this);
        }

        /**
         * What the air carries here: whatever catalyst the flow itself passes through, and otherwise
         * whatever is installed in the fan — the air leaving a fan with a soul campfire in its socket
         * behaves as though it had already been through one. The level is the one the fan is being used in,
         * which is what lets the socket resolve a catalyst that only a level can answer about.
         */
        @Override
        @Nullable
        public FanProcessingType getTypeAt(float offset) {
            FanProcessingType fromWorld = super.getTypeAt(offset);
            if (fromWorld != null)
                return fromWorld;
            return HandheldFanItem.installedProcessing(owner.getUseItem(), owner.level());
        }

        @Override
        protected void tickAffectedEntities(Level world) {
            for (Entity entity : world.getEntities(null, bounds)) {
                // The holder is not blown by the fan they are holding. Everything below is Create's.
                if (entity == owner)
                    continue;
                if (!entity.isAlive() || !entity.getBoundingBox()
                    .intersects(bounds) || isPlayerCreativeFlying(entity))
                    continue;

                Vec3i flow = (pushing ? direction : direction.getOpposite()).getNormal();
                float speed = Math.abs(source.getSpeed());
                float sneakModifier = entity.isShiftKeyDown() ? 4096f : 512f;
                double entityDistance =
                    VecHelper.alignedDistanceToFace(entity.position(), source.getAirCurrentPos(), direction);
                double entityDistanceOld = entity.position()
                    .distanceTo(VecHelper.getCenterOf(source.getAirCurrentPos()));
                float acceleration = (float) (speed / sneakModifier / (entityDistanceOld / maxDistance));
                Vec3 previousMotion = entity.getDeltaMovement();
                float maxAcceleration = 5;

                double xIn = Mth.clamp(flow.getX() * acceleration - previousMotion.x, -maxAcceleration, maxAcceleration);
                double yIn = Mth.clamp(flow.getY() * acceleration - previousMotion.y, -maxAcceleration, maxAcceleration);
                double zIn = Mth.clamp(flow.getZ() * acceleration - previousMotion.z, -maxAcceleration, maxAcceleration);

                entity.setDeltaMovement(previousMotion.add(new Vec3(xIn, yIn, zIn).scale(1 / 8f)));
                entity.fallDistance = 0;
                // Create also clears ServerPlayer.connection.aboveGroundTickCount here, so that a player
                // held up by the wind is not kicked for flying. That field is private outside Create, which
                // reaches it through its own access transformer; the holder of the fan is left out of this
                // current anyway, so the case it guards barely arises.

                FanProcessingType processingType = getTypeAt((float) entityDistance);
                if (processingType == null)
                    continue;

                if (entity instanceof ItemEntity itemEntity) {
                    if (world.isClientSide) {
                        processingType.spawnProcessingParticles(world, entity.position());
                        continue;
                    }
                    if (FanProcessing.canProcess(itemEntity, processingType))
                        FanProcessing.applyProcessing(itemEntity, processingType);
                    continue;
                }
                processingType.affectEntity(entity, world);
            }
        }
    }
}
