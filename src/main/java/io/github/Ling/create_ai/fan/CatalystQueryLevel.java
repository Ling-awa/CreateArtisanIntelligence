package io.github.Ling.create_ai.fan;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.Nullable;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * A level that is not a world: the one position it knows about answers with a block state and a fluid
 * somebody set on it, and every other position answers with air and with nothing.
 *
 * <p><b>Why this exists.</b> Create decides whether a block is a fan-processing catalyst with
 * {@code FanProcessingType#isValidAt(level, pos)} — a question about a position in a level, which the world
 * answers from a catalyst tag, from a block state property, from the fluid the block holds, from a NeoForge
 * data map keyed by the block or the fluid, or from code an addon wrote around any of those. The handheld
 * fan's socket holds an <em>item</em>, which has no position. What the item does have is the block it would
 * place and the fluid it carries, so the socket asks each type the very question Create asks, in a level
 * where that block and that fluid are the only thing there is. A type that reads a data map, or that calls
 * into its own code, then answers about the item exactly as it would answer about a block in the world, and
 * this mod never has to know which types those are.
 *
 * <p><b>What it answers.</b> Exactly one position — {@link #SENTINEL} — carries the block state and the
 * fluid state set by {@link #ask}; every other position is air and empty fluid. That is deliberate and
 * deterministic: the question is what the installed item is, not what happens to surround it, so a check
 * that looks at a neighbor, at a position above, or at the block a check invents for itself must not be able
 * to see a real world at coordinate zero and get an answer that depends on where the player is standing.
 * The biome is the same for every position and is a real, temperate one ({@link Biomes#PLAINS}), so a check
 * that asks the temperature has a defined answer instead of an exception.
 *
 * <p><b>Everything else is a stub.</b> This is a level in name only: it owns no chunk source, schedules no
 * ticks into a world it does not have, plays no sound, spawns no particle and writes no score. The handful
 * of questions that are about the level's <em>identity</em> rather than about its blocks — the recipe
 * manager, the scoreboard, the tick rate manager, the tick access and the player list — are borrowed from
 * the real level this was built from, because they are truthful there and free to reach; everything that
 * reads the world answers about the item instead. A member nobody overrode would reach the chunk source
 * this level deliberately does not have and throw, which is why the caller wraps each validity check:
 * a type that needs more of a world than an item can describe is a type the socket cannot judge, and it is
 * skipped rather than allowed to break the fan.
 *
 * <p><b>Construction and threading.</b> It is built from a real level and takes its identity from it:
 * {@link Level#dimension()}, {@link Level#dimensionTypeRegistration()} (which is what gives the level its
 * build height, so it must be the real one), {@link Level#registryAccess()}, {@link Level#getProfilerSupplier()},
 * {@link Level#isClientSide()} and {@link Level#isDebug()}. Two numbers have no accessor to borrow and are
 * passed as zeroes: the biome zoom seed, which only ever scales a biome lookup this level does not make,
 * and the maximum number of chained neighbor updates, which only sizes an updater this level never runs.
 * The mutable block state and fluid are read by a validity check on whatever thread called the socket, so
 * {@link SocketCatalyst} builds one of these per real level, caches it, and holds its own monitor across
 * the moment the state is set and the check is made; an integrated server has a client and a server side in
 * one JVM, and both may be asking at once.
 *
 * <p>Common code only, and deliberately so: nothing here names a client class, and a dedicated server loads
 * it like any other class of this mod.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class CatalystQueryLevel extends Level {

    /**
     * The one position a query is asked at. Any fixed value would do — what matters is that the socket and
     * this class agree on it, so that a check reading a position gets the item's own state there and air
     * everywhere else.
     */
    static final BlockPos SENTINEL = new BlockPos(0, 64, 0);

    /** What every position other than {@link #SENTINEL} answers: nothing at all. */
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** ...and no fluid. Both are the states vanilla itself hands out for a position with nothing in it. */
    private static final FluidState EMPTY_FLUID = Fluids.EMPTY.defaultFluidState();

    /** The level this was built from, for the few questions that are about identity rather than blocks. */
    private final Level real;

    /** The one biome every position of this level is in, so that a check asking has a defined answer. */
    private final Holder<Biome> biome;

    /** The installed item's block, set before each query. Air while no query is running. */
    private BlockState state = AIR;

    /** The installed item's fluid, set before each query. Empty while no query is running. */
    private FluidState fluid = EMPTY_FLUID;

    CatalystQueryLevel(Level real) {
        // The identity is the real level's, so that a check branching on side, dimension or registry sees
        // the truth. Both zeroes are documented above: there is no accessor for either.
        super((WritableLevelData) real.getLevelData(), real.dimension(), real.registryAccess(),
            real.dimensionTypeRegistration(), real.getProfilerSupplier(), real.isClientSide(), real.isDebug(),
            0L, 0);
        this.real = real;
        this.biome = real.registryAccess()
            .registryOrThrow(Registries.BIOME)
            .getHolderOrThrow(Biomes.PLAINS);
    }

    /**
     * What the item is, for the next query: the block it would place and the fluid it carries. Called under
     * the socket's monitor and read by the check that immediately follows, which is the whole of the
     * synchronization story.
     */
    void ask(BlockState state, FluidState fluid) {
        this.state = state;
        this.fluid = fluid;
    }

    // --- the two questions this level exists to answer --------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return SENTINEL.equals(pos) ? this.state : AIR;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return SENTINEL.equals(pos) ? this.fluid : EMPTY_FLUID;
    }

    /**
     * No block entity, anywhere. The concrete implementation would go through the chunk source this level
     * does not have, and an item in an inventory has no block entity to offer in any case.
     */
    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    /** The same biome everywhere: a real one, so that a check asking the temperature gets an answer. */
    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        return this.biome;
    }

    /**
     * The same answer at noise scale. {@code getNoiseBiome} is a default that would look a chunk up for the
     * biome of a position, and a query about an item must not read — or load — one.
     */
    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return this.biome;
    }

    /** How dark the sky is, and where the sea is: the real level's answers, for a check that asks. */
    @Override
    public int getSkyDarken() {
        return this.real.getSkyDarken();
    }

    @Override
    // The accessor is deprecated in Level, but it is still part of the contract a level has to answer, and
    // this level answers everything by forwarding to the real one: the base implementation would read state
    // this query level deliberately does not carry.
    @SuppressWarnings("deprecation")
    public int getSeaLevel() {
        return this.real.getSeaLevel();
    }

    /**
     * Lighting, answered as "there is nothing here to shade". This level has no light engine and no chunks,
     * so a check that asks how well lit the item's position is gets a defined answer instead of one read out
     * of a real world at a coordinate that means nothing.
     */
    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1;
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        return 0;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return amount;
    }

    // --- the questions that are the real level's --------------------------------------------------

    /** The real level's recipes: a validity check could consult them, and they are free to reach. */
    @Override
    public RecipeManager getRecipeManager() {
        return this.real.getRecipeManager();
    }

    @Override
    public Scoreboard getScoreboard() {
        return this.real.getScoreboard();
    }

    @Override
    public TickRateManager tickRateManager() {
        return this.real.tickRateManager();
    }

    @Override
    public PotionBrewing potionBrewing() {
        return this.real.potionBrewing();
    }

    /** Who is in the real level. Nothing here reads it; a check that does gets the truth. */
    @Override
    public List<? extends Player> players() {
        return this.real.players();
    }

    /** The flags the real level was loaded with, so a lookup through the registry sees the same set. */
    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.real.enabledFeatures();
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return this.real.getBlockTicks();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return this.real.getFluidTicks();
    }

    // --- the stubs --------------------------------------------------------------------------------

    /**
     * No chunk source. This is the one stub that keeps the level honest: every concrete method this class
     * did not override and that used to read the world through chunks now throws, and the socket's own
     * try/catch turns that into "this type cannot judge the item".
     */
    @Override
    // The null is the mechanism, not an oversight: it is what makes a world-reading check throw.
    @SuppressWarnings("DataFlowIssue")
    public ChunkSource getChunkSource() {
        return null;
    }

    /**
     * A difficulty for the sentinel, built rather than asked of the real level: the real one would have to
     * look a chunk up, and a query about an item must not load one.
     */
    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return new DifficultyInstance(this.getDifficulty(), this.getGameTime(), 0L, 0.0F);
    }

    /** Nothing in this level is ever ticked, so nothing is ever scheduled into it... */
    @Override
    @Nullable
    public Entity getEntity(int id) {
        return null;
    }

    /** ...and there is nobody to look up by id either. Both are unreachable through the borrowed ticks. */
    @Override
    // Again the null is the mechanism: a query level has no entities, and asking it for one has to fail.
    @SuppressWarnings("DataFlowIssue")
    protected LevelEntityGetter<Entity> getEntities() {
        return null;
    }

    @Override
    public String gatherChunkSourceStats() {
        return "";
    }

    /** Nothing is sent anywhere: this level is not a world two sides share. */
    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound,
                                SoundSource category, float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound,
                                SoundSource category, float volume, float pitch, long seed) {
    }

    @Override
    public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source,
                          float volume, float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particleData, double x, double y, double z, double xSpeed,
                            double ySpeed, double zSpeed) {
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    /** No map lives here; a check that asks for one is told there is none. */
    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId mapId) {
        return null;
    }

    @Override
    public void setMapData(MapId mapId, MapItemSavedData mapData) {
    }

    @Override
    public MapId getFreeMapId() {
        return new MapId(0);
    }

    /** The day of a level nobody ticks: zero, and writes to it are dropped. */
    @Override
    public void setDayTimeFraction(float dayTimeFraction) {
    }

    @Override
    public float getDayTimeFraction() {
        return 0;
    }

    @Override
    public float getDayTimePerTick() {
        return 0;
    }

    @Override
    public void setDayTimePerTick(float dayTimePerTick) {
    }
}
