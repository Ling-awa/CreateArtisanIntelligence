package io.github.Ling.create_ai;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.logistics.depot.DepotRenderer;
import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Create_ai.MODID)
public class Create_ai {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "create_ai";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "create_ai" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "create_ai" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "create_ai" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    // Create a Deferred Register to hold BlockEntityTypes which will all be registered under the "create_ai" namespace
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    // Create a Deferred Register to hold DataComponentTypes which will all be registered under the "create_ai" namespace
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

    // The spout gun's tank contents. One component holding a fluid stack, which is what NeoForge's
    // FluidHandlerItemStack reads and writes; an empty tank removes the component entirely.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> SPOUT_GUN_FLUID =
        DATA_COMPONENTS.registerComponentType("spout_gun_fluid",
            builder -> builder.persistent(SimpleFluidContent.CODEC)
                .networkSynchronized(SimpleFluidContent.STREAM_CODEC));

    // create_ai:processing_table - Create's depot, one stack deep, with tool-friendly hand rules.
    // The block properties mirror create:depot: Create builds its depot from Blocks.ANDESITE's
    // properties (Registrate initialProperties) and only overrides the map color to gray.
    public static final DeferredBlock<ProcessingTableBlock> PROCESSING_TABLE = BLOCKS.register("processing_table",
        () -> new ProcessingTableBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.ANDESITE).mapColor(MapColor.COLOR_GRAY)));
    public static final DeferredItem<BlockItem> PROCESSING_TABLE_ITEM = ITEMS.registerSimpleBlockItem("processing_table", PROCESSING_TABLE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProcessingTableBlockEntity>> PROCESSING_TABLE_BE =
        BLOCK_ENTITY_TYPES.register("processing_table",
            () -> BlockEntityType.Builder.of(ProcessingTableBlockEntity::new, PROCESSING_TABLE.get()).build(null));

    // create_ai:hammer - hold use for 0.75 seconds to press what a depot or basin holds.
    // NOTE: registered as our HammerItem subclass, not with registerSimpleItem - a plain Item would
    // silently drop every override (useOn, getUseDuration, finishUsingItem) and do nothing.
    public static final DeferredItem<Item> HAMMER = ITEMS.register("hammer", () -> new HammerItem(new Item.Properties().stacksTo(1)));

    // create_ai:spout_gun - a hand-held 4000 mB fluid tank, drawn with a spinning cog and the fluid it
    // carries. See SpoutGunItemRenderer for the rendering.
    public static final DeferredItem<Item> SPOUT_GUN = ITEMS.register("spout_gun", () -> new SpoutGunItem(new Item.Properties().stacksTo(1)));

    // create_ai:stirring_rod - hold use over a basin to stir it, for as long as the key is held.
    public static final DeferredItem<Item> STIRRING_ROD = ITEMS.register("stirring_rod", () -> new StirringRodItem(new Item.Properties().stacksTo(1)));

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
        return stack.is(HAMMER.get()) || stack.is(SPOUT_GUN.get()) || stack.is(STIRRING_ROD.get());
    }

    // This mod's own creative tab, placed after the combat tab. The holder is registered from a static
    // initializer instead of being kept in a public field: nothing ever reads the holder, and an
    // unreferenced field is what IDE inspections flag as dead code.
    static {
        CREATIVE_MODE_TABS.register("base", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.create_ai")).withTabsBefore(CreativeModeTabs.COMBAT).icon(() -> PROCESSING_TABLE_ITEM.get().getDefaultInstance()).displayItems((parameters, output) -> {
            output.accept(PROCESSING_TABLE_ITEM.get());
            output.accept(HAMMER.get());
            output.accept(SPOUT_GUN.get());
            output.accept(STIRRING_ROD.get());
        }).build());
    }

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Create_ai(IEventBus modEventBus, ModContainer modContainer) {
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

        // Expose the depot's item handler capability on our own block entity type
        modEventBus.addListener(ProcessingTableBlockEntity::registerCapabilities);
        // Expose the spout gun's fluid tank through the stock item fluid handler capability
        modEventBus.addListener(SpoutGunItem::registerCapabilities);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // One line, and only one: the scaffold's chatter (a dirt block, a magic number, a list of
        // items) was console noise with nothing behind it. What is worth printing is what actually got
        // registered, and only once.
        LOGGER.info("Registered processing_table >> block={} blockEntity={} item={}",
            BuiltInRegistries.BLOCK.getKey(PROCESSING_TABLE.get()),
            BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(PROCESSING_TABLE_BE.get()),
            BuiltInRegistries.ITEM.getKey(PROCESSING_TABLE_ITEM.get()));
    }

    // Our content into Create's own base tab, next to Create's blocks
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CREATE_BASE_TAB) {
            event.accept(PROCESSING_TABLE_ITEM);
            event.accept(HAMMER);
            event.accept(SPOUT_GUN);
            event.accept(STIRRING_ROD);
        }
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent.
    // The bus is not specified on purpose: it is deprecated for removal (since 1.21.1) and ignored — FML routes each
    // listener by its event type (IModBusEvent -> mod bus, everything else -> NeoForge.EVENT_BUS).
    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Create's item tooltips, from this mod's items. Client-side, which is where a tooltip is
            // ever built — Create's own tooltip modifiers are read by its client events, and the
            // description classes behind them reach into client-only code.
            ItemTooltips.register();
        }

        // Create's own depot renderer: it draws the held stack AND the eight output slots, out of the
        // depot behavior our block entity inherits from Create. A hand-written renderer here was why
        // products sitting in those slots used to be invisible.
        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(PROCESSING_TABLE_BE.get(), DepotRenderer::new);
        }

        /**
         * The spout gun's renderer, attached as a client extension.
         *
         * <p>This has to happen here rather than in an {@code initializeClient} override on the item.
         * Overriding that method puts a reference to a client-only renderer inside a class the server
         * loads as well — and the JVM, verifying the override, follows the reference and tries to load
         * {@code BlockEntityWithoutLevelRenderer}, which a dedicated server does not have. Registration
         * through this event keeps the item itself free of client code.
         *
         * <p>Two things happen in the one line: the renderer becomes the gun's client extension, and the
         * gun is entered into Create's list of custom-rendered items, whose models Create wraps while
         * baking — which is what lets the renderer draw the model itself, and then the cog on top of it.
         */
        @SubscribeEvent
        public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
            event.registerItem(SimpleCustomRenderer.create(SPOUT_GUN.get(), new SpoutGunItemRenderer()),
                SPOUT_GUN.get());
        }
    }
}
