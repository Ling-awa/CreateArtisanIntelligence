package io.github.Ling.create_ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingTypeRegistry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * What the item installed in a fan's socket means to the air leaving that fan: the fan processing it stands
 * for, asked of Create's own processing types in the one way that covers every way they can answer.
 *
 * <p>An installed item is one of two things, and either answers the same question. A block item stands for
 * the block it places, so a soul campfire in the socket means the air haunts; a fluid item stands for the
 * fluid it carries, so a bucket of lava means it blasts, whatever the item around the fluid is.
 *
 * <p><b>The first pass is the world's own answer to the question, with the world taken out of it.</b> In a
 * level, Create decides a catalyst with {@code FanProcessingType#isValidAt(level, pos)}, and what comes back
 * can be built on anything the position offers: a block or fluid catalyst tag, a state property such as a
 * campfire being lit, a NeoForge data map keyed by the block or the fluid, or code the addon wrote around
 * any of those. An item in an inventory has no position — but it does have a block state and a fluid, so
 * this class hands each registered type a level that answers those two questions about one fixed position
 * and nothing else ({@link CatalystQueryLevel}) and asks it the very question Create asks. A type that reads
 * a data map, or that calls into its own code, then answers here exactly as it answers in the world, and
 * nothing in this class has to know which types those are or that they exist.
 *
 * <p><b>The second pass is the tags, and it is kept as a fallback.</b> Create's registered processing types
 * are walked in exactly the order Create resolves them in — {@link FanProcessingTypeRegistry#SORTED_TYPES_VIEW},
 * the same list {@code FanProcessingType.getAt} reads, sorted by descending priority, so a splashing type
 * comes before an adding mod's, and an addon's before haunting, smoking and blasting. Each type is then
 * asked about its own catalyst tags. Which tag belongs to a type is derived from the type's registry id: a
 * type registered as {@code ns:path} is looked for in {@code ns:fan_processing_catalysts/path}, and, when
 * its namespace is not Create's, in Create's {@code create:fan_processing_catalysts/path} as well — an
 * addon's catalyst is commonly tagged in Create's namespace rather than in its own, and both are honored
 * here. This pass costs nothing worth saving and still covers the case where no level is available at all
 * (a tooltip built without one), and the case where a type's validity is expressed in tags only and the
 * level could not be built.
 *
 * <p><b>Order.</b> Each type's world answer is tried before its tag answer, and only then does the walk move
 * to the next type. That is what keeps Create's priority order meaning what it means: a higher-priority
 * type's block or fluid in the socket wins over a lower-priority type's, whether the higher-priority answer
 * came from a level or from a tag. Nothing here knows the name of a block, a fluid or a mod: a mod that adds
 * its catalyst to one of Create's catalyst tags, that declares one in a data map, or that registers a
 * processing type of its own with a validity check of its own, works in a socket the moment its datapack
 * loads, with no code change here.
 *
 * <p><b>What the world pass asks with.</b> Three things, in the order Create's own check asks them for a
 * position: the fluid the item carries, which is what a bucket holds; the fluid the item's block holds,
 * which is what Create sees at the position that block occupies; and the block itself, which is the state
 * the item would place. A check that throws — one that assumes more of a world than a single item can
 * describe — is caught, and that type falls back to its tags for good, with one debug line to say so. An
 * item that stands for neither a block nor a fluid is not asked about at all: it is a catalyst to nothing,
 * and asking anyway would let a check that answers "yes" to empty air claim every socket in the game.
 *
 * <p><b>What is no longer a compromise.</b> An item still has no state a world would have given it — a
 * campfire in a socket is the campfire block's default state, which is unlit — so the world pass on its own
 * would judge a campfire not a catalyst, and it is the tag pass that keeps a campfire smoking in a socket
 * exactly as it did before. The reverse is the point of the change: a catalyst that exists only in a data
 * map, or only in an addon's own code, used not to resolve at all, because no tag could ever name it.
 *
 * <p>Common code only, and deliberately so: no client class is named here, and a dedicated server loads
 * this like any other class of this mod.
 */
final class SocketCatalyst {

    /** Create's prefix for a processing type's catalyst tags; the type's own path follows it. */
    private static final String CATALYST_TAG_PREFIX = "fan_processing_catalysts/";

    /** Create's own namespace, whose tags an addon's catalyst is conventionally declared in. */
    private static final String CREATE_NAMESPACE = "create";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What an item that places no block stands for while a check asks about it. */
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * One query level per real level, and the lock that keeps them honest. A client and an integrated server
     * share one JVM and one copy of this class, so two threads can be asking about two different levels at
     * once; the fake block state and fluid of a query level are mutable, and setting them outside the lock
     * that guards the check that reads them would let one side's item be judged as the other's. The lock is
     * held for a handful of reads, so contention is theoretical, and an integrated server pays it twice a
     * tick at worst.
     */
    private static final Map<Level, CatalystQueryLevel> QUERY_LEVELS = new IdentityHashMap<>();

    /**
     * How many levels the cache may hold before it is dropped. A world change is the only way to reach this,
     * and holding more would mean holding whole worlds alive after they are gone.
     */
    private static final int QUERY_LEVEL_LIMIT = 4;

    /** The types already known to throw when asked about an item, so the debug line is written once. */
    private static final Set<FanProcessingType> UNASKABLE = Collections.newSetFromMap(new IdentityHashMap<>());

    private SocketCatalyst() {
    }

    /**
     * The processing an installed item stands for, or null when it is no catalyst of Create's at all.
     *
     * <p>The item is resolved once — its block state, the fluid it carries, and the fluid that block holds
     * — and then the types are walked. The three are asked in the order Create's own validity check asks
     * them for a position: the fluid it carries is what a bucket is holding, the fluid a block holds is what
     * Create sees at the position that block occupies, and the block itself is last.
     *
     * <p>The level is the one the fan is blowing in, and it may be null, which is the one case where the
     * world pass cannot run and the tags are all there is — a tooltip on a dedicated server has no level to
     * hand over, and neither does a caller that only wants the data-driven answer.
     */
    @Nullable
    static FanProcessingType resolve(ItemStack installed, @Nullable Level level) {
        if (installed.isEmpty())
            return null;

        // A block item stands for the block it places. Its default state is all an item can offer: there is
        // no level to have placed it in, and so no state a world would have given it.
        BlockState state = installed.getItem() instanceof BlockItem blockItem
            ? blockItem.getBlock()
                .defaultBlockState()
            : null;

        // A fluid item stands for the fluid it holds: a bucket, a tank, anything with a fluid handler.
        // FluidUtil is the lookup the rest of this mod uses, and its handler is handed a single item —
        // NeoForge's own javadoc asks for a stack of one, and a vanilla bucket's wrapper will not read a
        // stack of several. Nothing is filled or drained here, only read.
        FluidStack carried = FluidUtil.getFluidHandler(installed.copyWithCount(1))
            .map(handler -> handler.getFluidInTank(0))
            .orElse(FluidStack.EMPTY);

        // A block that holds a fluid stands for that fluid as well. In a level, Create's own check asks the
        // fluid state of the position before it asks the block state, and for a block that is made of a
        // fluid — a vanilla-style one or a mod's — those two are the same thing.
        FluidState held = state == null ? null : state.getFluidState();

        // What a check is shown, in Create's own order: the fluid carried, then the fluid the block holds,
        // and, when the item holds no fluid at all, no fluid — which is what a campfire's position is.
        List<FluidState> fluids = new ArrayList<>(2);
        if (!carried.isEmpty())
            fluids.add(carried.getFluid()
                .defaultFluidState());
        if (held != null && !held.isEmpty())
            fluids.add(held);
        if (fluids.isEmpty())
            fluids.add(Fluids.EMPTY.defaultFluidState());

        // There is something to ask about only when the item stands for a block or for a fluid. A plain item
        // answers no catalyst, and asking about it as air would let a type that accepts air claim the socket.
        //
        // The block's own fluid needs no clause of its own: it is read off the block state, so it can only be
        // there when the block is, and the first clause has already answered for that case.
        boolean askable = state != null || !carried.isEmpty();
        BlockState askedState = state == null ? AIR : state;

        for (FanProcessingType type : FanProcessingTypeRegistry.SORTED_TYPES_VIEW) {
            if (level != null && askable) {
                FanProcessingType fromWorld = askOfWorld(type, level, askedState, fluids);
                if (fromWorld != null)
                    return fromWorld;
            }

            // The tag fallback: this type's own tags, and Create's beside them for an addon's type. A type
            // with no id is one no tag can name, so no tag can hold a catalyst of its kind.
            ResourceLocation id = CreateBuiltInRegistries.FAN_PROCESSING_TYPE.getKey(type);
            if (id == null)
                continue;
            for (ResourceLocation tagId : catalystTags(id)) {
                if (!carried.isEmpty() && carried.is(fluidTag(tagId)))
                    return type;
                if (held != null && !held.isEmpty() && held.is(fluidTag(tagId)))
                    return type;
                if (state != null && state.is(blockTag(tagId)))
                    return type;
            }
        }
        return null;
    }

    /**
     * The type's own answer about the item, asked the way Create asks it in a level: one validity check per
     * fluid the item can offer, at the sentinel position of a query level showing this item's block and that
     * fluid.
     *
     * <p>A check that throws is one this mod cannot ask — it wanted more of a world than an item can describe
     * — and the type is remembered as unaskable so the debug line is written once and not once per tick. The
     * type is not removed from the walk: its tags are still tried by the caller.
     */
    @Nullable
    private static FanProcessingType askOfWorld(FanProcessingType type, Level level, BlockState state,
                                                List<FluidState> fluids) {
        synchronized (QUERY_LEVELS) {
            CatalystQueryLevel query = QUERY_LEVELS.get(level);
            if (query == null) {
                try {
                    query = new CatalystQueryLevel(level);
                } catch (RuntimeException e) {
                    // A level whose identity cannot be borrowed is one this pass cannot use; the tags remain.
                    if (UNASKABLE.add(type))
                        LOGGER.debug("No query level could be built for {}, so fan processing types are read "
                            + "from their catalyst tags only", level.dimension()
                                .location(), e);
                    return null;
                }
                if (QUERY_LEVELS.size() >= QUERY_LEVEL_LIMIT)
                    // A world change: the levels the old entries were built from are gone, and keeping them
                    // here would keep whole worlds alive.
                    QUERY_LEVELS.clear();
                QUERY_LEVELS.put(level, query);
            }

            for (FluidState fluid : fluids) {
                query.ask(state, fluid);
                try {
                    if (type.isValidAt(query, CatalystQueryLevel.SENTINEL))
                        return type;
                } catch (RuntimeException e) {
                    if (UNASKABLE.add(type))
                        LOGGER.debug("Fan processing type {} could not be asked about an installed item, so it "
                            + "is read from its catalyst tags only", CreateBuiltInRegistries.FAN_PROCESSING_TYPE.getKey(type), e);
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * The tags a type's catalysts may be declared in: the type's own namespace first, and Create's own when
     * the type is not Create's — an addon's catalyst blocks and fluids are usually tagged in Create's
     * namespace, beside Create's own, so both are asked.
     */
    private static List<ResourceLocation> catalystTags(ResourceLocation typeId) {
        String path = CATALYST_TAG_PREFIX + typeId.getPath();
        ResourceLocation own = ResourceLocation.fromNamespaceAndPath(typeId.getNamespace(), path);
        if (CREATE_NAMESPACE.equals(typeId.getNamespace()))
            return List.of(own);
        return List.of(own, ResourceLocation.fromNamespaceAndPath(CREATE_NAMESPACE, path));
    }

    /** The block catalyst tag of that name. A tag nothing declares is empty, and matches nothing. */
    private static TagKey<Block> blockTag(ResourceLocation id) {
        return TagKey.create(Registries.BLOCK, id);
    }

    /**
     * The fluid catalyst tag of that name, asked of a fluid stack or a fluid state — the two ways an item
     * can hold a fluid, and the two ways Create's own check asks the same question.
     */
    private static TagKey<Fluid> fluidTag(ResourceLocation id) {
        return TagKey.create(Registries.FLUID, id);
    }
}
