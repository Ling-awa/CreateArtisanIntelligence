package io.github.Ling.create_ai;

import io.github.Ling.create_ai.block.BrassMechanicalSawBlock;
import io.github.Ling.create_ai.block.BrassMechanicalSawBlockEntity;
import io.github.Ling.create_ai.block.BrassMechanicalSawMovementBehaviour;
import io.github.Ling.create_ai.block.BrassMechanicalSawMovementChecks;
import io.github.Ling.create_ai.block.BrassMechanicalSawPlacementHelper;
import io.github.Ling.create_ai.block.BrassMechanicalSawStress;
import io.github.Ling.create_ai.block.ProcessingTableBlock;
import io.github.Ling.create_ai.block.ProcessingTableBlockEntity;
import io.github.Ling.create_ai.client.BrassMechanicalSawBladeModels;
import io.github.Ling.create_ai.client.BrassMechanicalSawRenderer;
import io.github.Ling.create_ai.client.FanCogSpinItemRenderer;
import io.github.Ling.create_ai.client.ProcessingTableRenderer;
import io.github.Ling.create_ai.client.SawCogSpinItemRenderer;
import io.github.Ling.create_ai.client.SpoutGunItemRenderer;
import io.github.Ling.create_ai.client.StirringRodItemRenderer;
import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.fan.FanAirParticle;
import io.github.Ling.create_ai.item.ArtisanGogglesItem;
import io.github.Ling.create_ai.item.HammerItem;
import io.github.Ling.create_ai.item.HandheldFanItem;
import io.github.Ling.create_ai.item.HandheldMechanicalSawItem;
import io.github.Ling.create_ai.item.ObsidianHammerItem;
import io.github.Ling.create_ai.item.SpoutGunItem;
import io.github.Ling.create_ai.item.StirringRodItem;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.simibubi.create.content.kinetics.saw.SawVisual;
import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;

import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CreateAI.MOD_ID)
public class CreateAI {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "create_ai";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "create_ai" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    // Create a Deferred Register to hold Items which will all be registered under the "create_ai" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "create_ai" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    // Create a Deferred Register to hold BlockEntityTypes which will all be registered under the "create_ai" namespace
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    // Create a Deferred Register to hold DataComponentTypes which will all be registered under the "create_ai" namespace
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    // The spout gun's tank contents. One component holding a fluid stack, which is what NeoForge's
    // FluidHandlerItemStack reads and writes; an empty tank removes the component entirely.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> SPOUT_GUN_FLUID =
        DATA_COMPONENTS.registerComponentType("spout_gun_fluid",
            builder -> builder.persistent(SimpleFluidContent.CODEC)
                .networkSynchronized(SimpleFluidContent.STREAM_CODEC));

    /**
     * How much fluid the spout gun moves in one right-click, as the player set it on the gun.
     *
     * <p>One of the ten amounts a sneaking press walks through, and absent on a gun that has never been set,
     * which reads as the default — see {@code SpoutGunItem#transferAmount}. It is the amount moved between the
     * gun and a container or a block's tank, and nothing else: the spout recipe a pour runs is Create's,
     * decided by the fluid and the item, and is not scaled by this.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> SPOUT_GUN_TRANSFER =
        DATA_COMPONENTS.registerComponentType("spout_gun_transfer",
            builder -> builder.persistent(Codec.INT)
                .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * What is installed in a handheld fan, as the id of the installed item.
     *
     * <p>An id rather than an {@link ItemStack}: a component's value has to implement equals and hashCode —
     * the component map checks that when it is written and throws "Data components must implement equals
     * and hashCode" when it does not — and {@code ItemStack} compares by identity, so a stack can never be
     * one. A {@link ResourceLocation} does both. What is lost is any components a modded container item
     * carried; the installed item is rebuilt from its id for display and for the catalyst lookup.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> FAN_UPGRADE =
        DATA_COMPONENTS.registerComponentType("fan_upgrade",
            builder -> builder.persistent(ResourceLocation.CODEC)
                .networkSynchronized(ResourceLocation.STREAM_CODEC));

    /**
     * What is installed in a handheld mechanical saw's filter slot, as the NBT of the installed stack.
     *
     * <p>NBT rather than an id, because a filter item carries its contents in its own components and an id
     * would drop them: {@link CompoundTag} keeps them and implements equals and hashCode, which a component
     * value must — an {@link net.minecraft.world.item.ItemStack} compares by identity and is refused.
     *
     * <p>Declared here, beside the other components, and not in the class that reads it: a component
     * registered by a class that first loads when the item is used is registered <em>after</em> the register
     * event has already run, and NeoForge throws "Cannot register new entries to DeferredRegister after
     * RegisterEvent has been fired" — which surfaced as {@code NoClassDefFoundError: Could not initialize
     * class SawFilterSlotItem} the moment a tooltip was drawn. Everything the components need to exist must
     * be created while this class is loaded, which is before the event.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> SAW_FILTER =
        DATA_COMPONENTS.registerComponentType("saw_filter",
            builder -> builder.persistent(CompoundTag.CODEC)
                .networkSynchronized(ByteBufCodecs.COMPOUND_TAG));

    /**
     * Where a saw's run through its matching recipes got to, so a multi-output recipe hands its results out
     * in turn. Zero when absent, which is where Create's own saw starts its index.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> SAW_RECIPE_INDEX =
        DATA_COMPONENTS.registerComponentType("saw_recipe_index",
            builder -> builder.persistent(Codec.INT)
                .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * Whether a saw's filter is bypassed: sneak and right-click a saw toggles this, and while it is set the
     * installed filter is left in the slot but nothing it says is obeyed.
     *
     * <p>A {@link Boolean} is the whole of the value, and that is not laziness: a component's value has to
     * implement equals and hashCode, which {@code Boolean} does and an {@link ItemStack} does not, and a
     * plain boolean is the entire state the toggle holds. Absent means false — the filter is obeyed — so a
     * saw that has never been toggled carries no component at all.
     *
     * <p>Declared here, beside the saw's other two components, for the reason spelled out on
     * {@link #SAW_FILTER}: a component registered by the class that reads it is registered after the
     * register event has run, which NeoForge refuses.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> SAW_FILTER_OFF =
        DATA_COMPONENTS.registerComponentType("saw_filter_off",
            builder -> builder.persistent(Codec.BOOL)
                .networkSynchronized(ByteBufCodecs.BOOL));

    /**
     * This mod's own particle, which is Create's air-flow sprite animation: {@code create:air_flow} itself
     * cannot be used for a fan held in a hand, because its factory looks the coordinate in its data up as a
     * <em>block entity</em> and needs an {@code IAirCurrentSource} — a player's air has none, so those puffs
     * die on their first tick. The particle class is Create's; only the sprite list is registered here, and
     * it is the same eight vanilla sprites Create's own air-flow definition names.
     */
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(Registries.PARTICLE_TYPE, MOD_ID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FAN_AIR =
        PARTICLE_TYPES.register("fan_air", () -> new SimpleParticleType(false));

    // create_ai:processing_table - the artisan's workbench: a crafting layout laid out and assembled by
    // hand, with no depot under it any more. See ProcessingTableBlock. The block properties mirror
    // create:depot, so it still looks and stands like the table it always was.
    public static final DeferredBlock<ProcessingTableBlock> PROCESSING_TABLE = BLOCKS.register("processing_table",
        () -> new ProcessingTableBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.ANDESITE).mapColor(MapColor.COLOR_GRAY)));
    public static final DeferredItem<BlockItem> PROCESSING_TABLE_ITEM = ITEMS.registerSimpleBlockItem("processing_table", PROCESSING_TABLE);
    // The data-fixer type the builder asks for is optional, and null is how a mod says "none": vanilla
    // always passes a real one because its own register() fetches it, which is why the parameter reads
    // as non-null and why the IDE flags this. Nothing dereferences it for a modded type, and a mod with
    // no legacy saves has nothing for the data fixer to do.
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProcessingTableBlockEntity>> PROCESSING_TABLE_BE =
        BLOCK_ENTITY_TYPES.register("processing_table",
            () -> BlockEntityType.Builder.of(ProcessingTableBlockEntity::new, PROCESSING_TABLE.get()).build(null));

    // create_ai:brass_mechanical_saw - Create's mechanical saw, twice the cutting speed and twice the
    // stress. The block properties and shape are Create's: Create builds its saw from
    // SharedProperties.stone() and only overrides the map color.
    public static final DeferredBlock<BrassMechanicalSawBlock> BRASS_MECHANICAL_SAW = BLOCKS.register("brass_mechanical_saw",
        () -> new BrassMechanicalSawBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.ANDESITE).mapColor(MapColor.PODZOL)));
    public static final DeferredItem<BlockItem> BRASS_MECHANICAL_SAW_ITEM = ITEMS.registerSimpleBlockItem("brass_mechanical_saw", BRASS_MECHANICAL_SAW);
    // The class in the type parameter is Create's SawBlockEntity, not our subclass: everything Create
    // does with a saw - SawBlock's own item-interaction lookup, SawVisual and SawRenderer - is written
    // against that type, and our subclass is one. Only the registered type is ours.
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BrassMechanicalSawBlockEntity>> BRASS_MECHANICAL_SAW_BE =
        BLOCK_ENTITY_TYPES.register("brass_mechanical_saw",
            () -> BlockEntityType.Builder.of(BrassMechanicalSawBlockEntity::new, BRASS_MECHANICAL_SAW.get()).build(null));

    // create_ai:hammer - hold use for 0.75 seconds to press what a depot or basin holds.
    // NOTE: registered as our HammerItem subclass, not with registerSimpleItem - a plain Item would
    // silently drop every override (useOn, getUseDuration, finishUsingItem) and do nothing.
    // The durability here is the item's default: the config's value is applied to each stack by the item
    // itself, because this mod's config is loaded after the registry events fire (see ToolDurability).
    public static final DeferredItem<Item> HAMMER = ITEMS.register("hammer",
        () -> new HammerItem(new Item.Properties().stacksTo(1)
            .durability(512)
            .attributes(HammerItem.createAttributes())));

    // create_ai:obsidian_hammer - the hammer's press, and crushing wheels instead of a millstone on a
    // sneaking strike. Its own item rather than a mode, because a mode would have to be kept on the
    // stack or cycled by a key, and the two hammers are meant to be held at the same time.
    public static final DeferredItem<Item> OBSIDIAN_HAMMER = ITEMS.register("obsidian_hammer",
        () -> new ObsidianHammerItem(new Item.Properties().stacksTo(1)
            .durability(2048)
            .attributes(HammerItem.createAttributes())));

    // create_ai:spout_gun - a hand-held 4000 mB fluid tank, drawn with a spinning cog and the fluid it
    // carries. See SpoutGunItemRenderer for the rendering. One of the three tools that run on a backtank.
    public static final DeferredItem<Item> SPOUT_GUN = ITEMS.register("spout_gun",
        () -> new SpoutGunItem(new Item.Properties().stacksTo(1)
            .durability(512)));

    // create_ai:stirring_rod - hold use over a basin to stir it, for as long as the key is held.
    public static final DeferredItem<Item> STIRRING_ROD = ITEMS.register("stirring_rod",
        () -> new StirringRodItem(new Item.Properties().stacksTo(1)
            .durability(512)));

    // create_ai:handheld_encased_fan - hold use to blow Create's own fan air, three blocks along the eyes.
    // The Java identifier stays HANDHELD_FAN: the registry id is what a player reads in a command, and
    // renaming it is what makes the item an "encased" fan there; the class and constant names follow the
    // tool's own name and would only churn call sites.
    public static final DeferredItem<Item> HANDHELD_FAN = ITEMS.register("handheld_encased_fan",
        () -> new HandheldFanItem(new Item.Properties().stacksTo(1)
            .durability(512)));

    // create_ai:handheld_mechanical_saw - hold use on a depot for one second to cut what it holds, with a
    // filter slot that selects among the recipes the input matches, and a sweeping cut in front of the
    // player when there is no depot to work on. See HandheldMechanicalSawItem.
    public static final DeferredItem<Item> HANDHELD_MECHANICAL_SAW = ITEMS.register("handheld_mechanical_saw",
        () -> new HandheldMechanicalSawItem(new Item.Properties().stacksTo(1)
            .durability(512)));

    // create_ai:goggles - Create's goggles, worn in the helmet slot, that also stand in for a deployer
    // on a depot while sneaking. See ArtisanGogglesItem for how the goggles half is registered and
    // ArtisanGogglesOnDepots for the deployer half. Not a custom tool: it is worn, never used on a
    // table, so it has no business in isCustomTool - and, being worn rather than swung, it is the one
    // item here that takes no wear at all.
    public static final DeferredItem<Item> GOGGLES = ITEMS.register("goggles",
        () -> new ArtisanGogglesItem(new Item.Properties().stacksTo(1)));

    // Create's own "base" tab, so our blocks show up next to Create's
    private static final ResourceKey<CreativeModeTab> CREATE_BASE_TAB =
        ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("create", "base"));

    /**
     * Whether an item is a tool for our blocks rather than cargo.
     *
     * <p>Tools are never accepted as contents and are never swallowed by block interaction, so their
     * own right-click action always runs. Add future tools here.
     */
    public static boolean isCustomTool(ItemStack stack) {
        return stack.is(HAMMER.get()) || stack.is(OBSIDIAN_HAMMER.get()) || stack.is(SPOUT_GUN.get())
            || stack.is(STIRRING_ROD.get()) || stack.is(HANDHELD_FAN.get())
            || stack.is(HANDHELD_MECHANICAL_SAW.get());
    }

    // This mod's own creative tab, placed after the combat tab. The holder is registered from a static
    // initializer instead of being kept in a public field: nothing ever reads the holder, and an
    // unreferenced field is what IDE inspections flag as dead code.
    static {
        CREATIVE_MODE_TABS.register("base", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.create_ai")).withTabsBefore(CreativeModeTabs.COMBAT).icon(() -> PROCESSING_TABLE_ITEM.get().getDefaultInstance()).displayItems((parameters, output) -> {
            output.accept(PROCESSING_TABLE_ITEM.get());
            output.accept(BRASS_MECHANICAL_SAW_ITEM.get());
            output.accept(HAMMER.get());
            output.accept(OBSIDIAN_HAMMER.get());
            output.accept(SPOUT_GUN.get());
            output.accept(STIRRING_ROD.get());
            output.accept(HANDHELD_FAN.get());
            output.accept(HANDHELD_MECHANICAL_SAW.get());
            output.accept(GOGGLES.get());
        }).build());
    }

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CreateAI(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so block entity types get registered
        BLOCK_ENTITY_TYPES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so data component types get registered
        DATA_COMPONENTS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so our particle type gets registered
        PARTICLE_TYPES.register(modEventBus);

        // Expose the saw's item handler capability on our own block entity type
        modEventBus.addListener(BrassMechanicalSawBlockEntity::registerCapabilities);
        // Expose the spout gun's fluid tank through the stock item fluid handler capability
        modEventBus.addListener(SpoutGunItem::registerCapabilities);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Create's movement behaviour registry is keyed by block, and Create only ever registers its own
        // saw there. Deferred to the main thread because FMLCommonSetupEvent runs mods in parallel and
        // this registry is shared state; by now every block is registered, so the lookup will find ours.
        event.enqueueWork(BrassMechanicalSawMovementBehaviour::register);
        // Create's contraption assembly treats a saw as non-supportive towards its facing, so a piston
        // pushing one does not drag the block the blade points at along with it. Create's own fallback
        // recognises only create:mechanical_saw by identity, so ours has to say so itself.
        event.enqueueWork(BrassMechanicalSawMovementChecks::register);
        // The stress impact, into Create's own registry: the network, the goggles and the item tooltip's
        // stress line all read it from there, and a kinetic block missing from it draws nothing.
        event.enqueueWork(BrassMechanicalSawStress::register);
        // Create registers its saw's placement helper against its own block, so a subclass inherits none.
        event.enqueueWork(BrassMechanicalSawPlacementHelper::register);

        // Wearing the goggles is wearing goggles. Create's overlay, its goggles entry in a machine's
        // tooltip and its rotation indicator all ask one question - GogglesItem.isWearingGoggles - and
        // Create answers it from a list of predicates that holds its own goggles alone. That list is
        // the extension point Create documents for another item that counts as goggles, and this is
        // the only thing an item has to do to get the whole overlay.
        event.enqueueWork(ArtisanGogglesItem::registerGogglesOverlay);

        // One line, and only one: the scaffold's chatter (a dirt block, a magic number, a list of
        // items) was console noise with nothing behind it. What is worth printing is what actually got
        // registered, and only once.
        LOGGER.info("Registered processing_table >> block={} blockEntity={} item={}",
            BuiltInRegistries.BLOCK.getKey(PROCESSING_TABLE.get()),
            BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(PROCESSING_TABLE_BE.get()),
            BuiltInRegistries.ITEM.getKey(PROCESSING_TABLE_ITEM.get()));
    }

    // Our content stays in our own tab. It used to be added to Create's base tab as well, so it appeared
    // twice; a pack that wants it there can move it with a datapack, and a player who wants one list of
    // this mod's things wants one list of them.

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent.
    // The bus is not specified on purpose: it is deprecated for removal (since 1.21.1) and ignored —FML routes each
    // listener by its event type (IModBusEvent -> mod bus, everything else -> NeoForge.EVENT_BUS).
    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static class ClientModEvents {

        /**
         * Asks for the item models nothing else names.
         *
         * <p>A model is only baked if a blockstate, an item or this event refers to it. The saw's blades are
         * named by their blockstate and bake on their own, but an item renderer's cog is named by nothing
         * but the renderer — and Flywheel's {@code PartialModel} does not fail loudly when the model it wants
         * was never baked: it hands back the missing model, which is the purple cube. That is why the spout
         * gun's cog, the fan's cog and the saw's cog all have to be asked for here, by name, before baking
         * starts.
         */
        @SubscribeEvent
        public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
            event.register(ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "item/spout_gun/cog")));
            event.register(ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "item/handheld_encased_fan/cog")));
            event.register(ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "item/handheld_mechanical_saw/cog")));
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Register the blade models with Flywheel FIRST, and here rather than in the renderer's
            // static fields: Flywheel snapshots the partial models it knows about inside
            // ModelEvent.RegisterAdditional, and anything created after that event is never baked - it
            // silently renders as the missing model, with no error in the log. Client setup runs before
            // the model events; EntityRenderersEvent.RegisterRenderers, where the renderer class would
            // otherwise initialise, runs after.
            BrassMechanicalSawBladeModels.register();

            // The same timing for the three cogs: their PartialModels have to exist before
            // onRegisterAdditional above runs, and the renderer classes would otherwise not initialise until
            // the renderer events, which come after the model events.
            SpoutGunItemRenderer.register();
            FanCogSpinItemRenderer.register();
            SawCogSpinItemRenderer.register();

            // Create's item tooltips, from this mod's items. Client-side, which is where a tooltip is
            // ever built —Create's own tooltip modifiers are read by its client events, and the
            // description classes behind them reach into client-only code.
            ItemTooltips.register();

            // Flywheel draws kinetic blocks, and it looks its visual up per block entity type. Create
            // wires SawVisual to create:mechanical_saw through its own Registrate builder, so our block
            // entity type - a different type - has no visual until this registers one, and a kinetic
            // block with no visual silently loses its spinning shaft. This is the public Flywheel API
            // Create itself calls; registering it is what puts the shaft back.
            //
            // neverSkipVanillaRender, not the default: Create's own saw keeps SawRenderer running
            // alongside the visual (SawRenderer still draws the blade, the items on it and the recipe
            // filter, and SawVisual only owns the shaft). Skipping vanilla render here would drop those.
            SimpleBlockEntityVisualizer.builder(CreateAI.BRASS_MECHANICAL_SAW_BE.get())
                .factory(SawVisual::new)
                .neverSkipVanillaRender()
                .apply();
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            // The workbench's own renderer: the crafting layout, laid flat on the table's face. It used to be
            // Create's depot renderer, which drew the depot's held stack and its eight output slots out of
            // the depot behaviour the table inherited — none of which the table is any more.
            event.registerBlockEntityRenderer(PROCESSING_TABLE_BE.get(), ProcessingTableRenderer::new);
            // Our saw renderer: Create's, with this mod's blade models in place of Create's steel one.
            // The blade is not part of the block model - Create draws it as a separate partial model -
            // so the stock SawRenderer would ignore our blade files entirely.
            event.registerBlockEntityRenderer(BRASS_MECHANICAL_SAW_BE.get(), BrassMechanicalSawRenderer::new);
        }

        @SubscribeEvent
        public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(FAN_AIR.get(), FanAirParticle.Factory::new);
        }

        /**
         * The spout gun's, the handheld fan's, the handheld saw's and the stirring rod's renderers,
         * attached as client extensions.
         *
         * <p>This has to happen here rather than in an {@code initializeClient} override on the item.
         * Overriding that method puts a reference to a client-only renderer inside a class the server
         * loads as well —and the JVM, verifying the override, follows the reference and tries to load
         * {@code BlockEntityWithoutLevelRenderer}, which a dedicated server does not have. Registration
         * through this event keeps the item itself free of client code.
         *
         * <p>Two things happen in the one line: the renderer becomes the item's client extension, and the
         * item is entered into Create's list of custom-rendered items, whose models Create wraps while
         * baking —which is what lets the renderer draw the model itself, and then the cog on top of it.
         *
         * <p>The fan, the saw and the rod are the same pattern as the gun, and need it for the same reason:
         * the fan's and the saw's cogs are second models drawn over the body, and the rod has to be able to
         * decide not to draw itself at all while its holder is stirring with it. None of the four item
         * classes knows any of this — the renderers are named only here, on the client.
         */
        @SubscribeEvent
        public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
            event.registerItem(SimpleCustomRenderer.create(SPOUT_GUN.get(), new SpoutGunItemRenderer()),
                SPOUT_GUN.get());
            event.registerItem(SimpleCustomRenderer.create(HANDHELD_FAN.get(), new FanCogSpinItemRenderer()),
                HANDHELD_FAN.get());
            event.registerItem(
                SimpleCustomRenderer.create(HANDHELD_MECHANICAL_SAW.get(), new SawCogSpinItemRenderer()),
                HANDHELD_MECHANICAL_SAW.get());
            event.registerItem(SimpleCustomRenderer.create(STIRRING_ROD.get(), new StirringRodItemRenderer()),
                STIRRING_ROD.get());
        }
    }
}
