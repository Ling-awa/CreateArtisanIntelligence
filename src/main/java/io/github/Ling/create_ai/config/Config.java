package io.github.Ling.create_ai.config;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * This mod's common config.
 *
 * <p>Every number a player might want to judge for themselves lives here: the two tool looks (how hard a
 * nozzle-fitted fan pushes, how large the item in a fan's socket is drawn) and the timing of everything the
 * tools do — how long a hammer holds the hands afterwards, how long the spout gun charges, how fast a
 * stirring rod stirs, what one step of a deployer costs, how long a held tool lasts, how far a fan reaches
 * and the speed the strike sounds like it is running at.
 *
 * <p>Everything is read where it is used rather than cached at load, so an edit takes effect the next time
 * that number is needed and nothing has to be restarted. The defaults are the numbers the mod shipped with,
 * and those are Create's own wherever Create has one: a hammer's half second is the press's own pause, the
 * stirring speed is a mixer's 256 rpm, a deployer's step is Create's own deployer cooldown. A default config
 * therefore behaves the way the machine it stands in for does.
 *
 * <p>The spec is registered in {@code Create_ai}'s constructor as a common config. If reading an entry has
 * to happen at load time rather than at use, add a {@code @SubscribeEvent} method for {@code ModConfigEvent}
 * here and put {@code @EventBusSubscriber(modid = Create_ai.MODID)} back on the class. That annotation must
 * NOT be here while the class has no listener methods: FML registers every class carrying it, and a class
 * with nothing to subscribe throws, which takes the whole mod down at construction.
 */
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * How much harder the fan pushes with Create's Nozzle in its socket, as a multiple of what Create's own
     * nozzle pushes with.
     *
     * <p>Create's own numbers are the ones {@code NozzleBlockEntity#tick} uses: the outward vector scaled by
     * the range still left to the entity, then by a hundred-and-twenty-eighth for a dropped item and a
     * thirty-second for anything else. Those suit a block bolted to a machine; a hand-held fan at four blocks
     * reads as too gentle at 1.0, which is why the default is above it. The multiplier changes how hard what
     * is inside the field is pushed and nothing else — the range is {@link #fanRange()}.
     */
    private static final ModConfigSpec.DoubleValue NOZZLE_PUSH_STRENGTH = BUILDER
        .comment("How much harder a nozzle-fitted handheld fan pushes than Create's own nozzle: 1.0 is Create's own numbers, 2.0 is twice as hard. The range is not affected.")
        .defineInRange("nozzlePushStrength", 2.0D, 0.0D, 10.0D);

    /**
     * How large the item installed in the handheld fan's socket is drawn on the fan, as a fraction of the
     * item's own size: 0.5 draws a sixteen-pixel item at eight pixels, which is half the fan's grid.
     *
     * <p>A number that has to be judged by eye, so it is a file a player can edit rather than a constant
     * that needs a rebuild. The config is watched, so a change takes effect the next time the item is drawn.
     */
    private static final ModConfigSpec.DoubleValue SOCKET_ITEM_SCALE = BUILDER
        .comment("How large the item in the handheld fan's socket is drawn, as a fraction of its own size: 0.25 is a quarter (a 16-pixel item becomes 4 pixels), which is about the width of the fan itself.")
        .defineInRange("socketItemScale", 0.25D, 0.0D, 4.0D);

    // --- the tools' timing --------------------------------------------------------------------------

    /**
     * How long a hammer's press leaves every carried stack on vanilla's item cooldown.
     *
     * <p>This is what makes a swing have weight: the cooldown is consulted by the client before it sends a
     * click, by the server as the authority, and it is drawn on the hotbar, so the pause is both real and
     * visible. Ten ticks is half a second, which is what the mod ships with.
     */
    private static final ModConfigSpec.IntValue HAMMER_PRESS_COOLDOWN = BUILDER
        .comment("Ticks a hammer's press puts every carried stack on cooldown for: 20 ticks is one second. Default 10.")
        .defineInRange("hammerPressCooldown", 10, 0, 200);

    /** How long the sneak-and-strike grind leaves behind: the same pause a press costs, by default. */
    private static final ModConfigSpec.IntValue HAMMER_GRIND_COOLDOWN = BUILDER
        .comment("Ticks a hammer's sneak-strike grind puts every carried stack on cooldown for: 20 ticks is one second. Default 10.")
        .defineInRange("hammerGrindCooldown", 10, 0, 200);

    /**
     * What one step of a deployer action costs, as item cooldown over every carried stack.
     *
     * <p>A deployer action is one right-click with an item held out — the goggles' deploy onto what it is
     * looking at, and the fan's and the saw's installing or taking back of what sits in their slot. The pause
     * is what stops a click from being a stream: the default is Create's own deployer cooldown.
     */
    private static final ModConfigSpec.IntValue DEPLOYER_COOLDOWN = BUILDER
        .comment("Ticks one deployer step (installing an item, or the goggles' deploy) puts every carried stack on cooldown for: 20 ticks is one second. Default 10.")
        .defineInRange("deployerCooldown", 10, 0, 200);

    /**
     * How long the spout gun has to be held before it pours or purges.
     *
     * <p>The hold is the charge: the use key held for this many ticks is what the gun reads as "go", and the
     * wind-up animation the item plays is that same stretch of time. Twenty ticks is one second.
     */
    private static final ModConfigSpec.IntValue SPOUT_GUN_CHARGE_TICKS = BUILDER
        .comment("Ticks the spout gun has to be held before it pours or purges: 20 ticks is one second. Default 20.")
        .defineInRange("spoutGunChargeTicks", 20, 1, 200);

    /**
     * How long one stroke of the handheld mechanical saw takes.
     *
     * <p>The same number serves three purposes, because they are the same stroke: the first cut is due this
     * many ticks into the hold and every one after it is this far behind the last, the fragments travelling
     * the cut are placed by how far into the stroke the hold is, and the stroke sound is paced by it. Twenty
     * ticks is one second, which is the rate the mod ships with.
     */
    private static final ModConfigSpec.IntValue SAW_CHARGE_TICKS = BUILDER
        .comment("Ticks one stroke of the handheld mechanical saw takes (the wait before a cut, the pace of the cuts after it, and the animation): 20 ticks is one second. Default 20.")
        .defineInRange("sawChargeTicks", 20, 1, 200);

    /**
     * How fast a stirring rod stirs, in the same rpm a mechanical mixer would be running at.
     *
     * <p>This is the mixing efficiency. Create's mixer derives how long one mixing operation takes from its
     * kinetic speed and the recipe's own processing time — {@code processingTicks} is
     * {@code log2(512 / speed) * ceil(duration / 100 * 15) + 1} — and a rod held over a basin stands in for a
     * mixer at this speed, so the number here is exactly that speed. The countdown is the wait between
     * reaching the bottom and the recipe landing, so applications are one tick further apart than it says: at
     * the default 256 a recipe of duration 100 (the usual case) is applied every 17 ticks, half the speed
     * makes that 32, and the fastest this entry goes, 512, makes it 2. Above 512 there is nothing left to
     * shorten, so the entry stops there.
     *
     * <p>It is a speed rather than a time because a time would have to assume the recipe: a recipe that asks
     * for a longer processing time takes proportionally longer at any speed, exactly as it does on a machine.
     */
    private static final ModConfigSpec.DoubleValue STIRRING_SPEED = BUILDER
        .comment("How fast a stirring rod stirs, in rpm: 256 is the speed Create's mixer is normally run at, lower is slower. A recipe of the usual duration is then applied every log2(512/speed) * 15 + 2 ticks.")
        .defineInRange("stirringSpeed", 256.0D, 16.0D, 512.0D);

    /**
     * The speed a press strike sounds like it is running at.
     *
     * <p>Create's press plays its activation sound at a pitch derived from its speed, and this is the speed a
     * hammer's strike stands in for: the strike plays at {@code 0.75 + speed / 1024}, so the default 256 gives
     * the pitch a press running at 256 rpm would have. It is a sound and nothing else — how often a hammer can
     * strike is {@link #hammerPressCooldown()}.
     */
    private static final ModConfigSpec.DoubleValue PRESS_STRIKE_SPEED = BUILDER
        .comment("The rpm a hammer's press strike sounds like: the strike plays at pitch 0.75 + this / 1024. Only the sound is affected. Default 256.")
        .defineInRange("pressStrikeSpeed", 256.0D, 16.0D, 1024.0D);

    /**
     * How long a held tool lasts before its use runs out on its own.
     *
     * <p>The fan, the saw and the stirring rod keep working for as long as the use key is held, and this is
     * how long that can be: twenty ticks short of an hour. It is not how long an action takes — it exists so
     * that a hold never expires in the middle of one. The spout gun is the exception and does not use it, its
     * hold being its charge ({@link #spoutGunChargeTicks()}).
     */
    private static final ModConfigSpec.IntValue TOOL_HOLD_TICKS = BUILDER
        .comment("Ticks a held fan, saw or stirring rod lasts before the hold expires on its own. Default 72000 (an hour); this is not how long an action takes.")
        .defineInRange("toolHoldTicks", 72000, 200, 72000);

    /**
     * How far the handheld fan's air reaches, in blocks.
     *
     * <p>Create's fan processing reaches up to sixteen blocks; a hand-held fan with four reads as a tool
     * rather than as a machine standing on the floor, which is why that is the default. It is the length of
     * the air the fan blows in every direction it blows, and it is what a nozzle-fitted fan spreads around
     * the player as well.
     */
    private static final ModConfigSpec.IntValue FAN_RANGE = BUILDER
        .comment("How far the handheld fan's air reaches, in blocks. Default 4; Create's own fan processing reaches up to 16.")
        .defineInRange("fanRange", 4, 1, 16);

    // --- the stirring rod's animation ---------------------------------------------------------------

    /**
     * How far the stirring rod leans from upright while it stirs, in degrees.
     *
     * <p>This is the width of the stir. The rod turns about its own middle, so an upright rod would turn on
     * the spot and stir nothing; leaning it is what swings its head, and the circle it sweeps is as wide as
     * the sine of this angle in blocks — about a third of a block at the default twenty, half a block near
     * thirty-five, a quarter at sixteen.
     */
    private static final ModConfigSpec.DoubleValue STIR_TILT_DEGREES = BUILDER
        .comment("How far the stirring rod leans from upright (the plumb line) while stirring, in degrees: this is how wide it stirs, the head sweeping a circle sin(this) blocks across. Default 20.")
        .defineInRange("stirTiltDegrees", 20.0D, 4.0D, 60.0D);

    /**
     * How many whole turns the stirring rod makes in the basin in the time one mixing operation takes.
     *
     * <p>A rate in the basin's own units rather than in degrees per tick, so that it stays tied to the
     * rhythm being stirred: the stirring speed in {@link #stirringSpeed()} and the recipe's own processing
     * time both drive the visible speed along with them, and a rod over a slow recipe does not outrun the
     * machine it stands in for.
     */
    private static final ModConfigSpec.DoubleValue STIR_TURNS_PER_OPERATION = BUILDER
        .comment("How many whole turns the stirring rod makes per mixing operation. Default 2; a mixing operation is one stir of the basin's recipe.")
        .defineInRange("stirTurnsPerOperation", 2.0D, 0.25D, 8.0D);

    // --- the tools' durability ----------------------------------------------------------------------
    //
    // Read once a tick per held stack, by the items themselves (see ToolDurability): an item's maximum
    // damage lives in the stack's own component, and this mod's config is loaded after the registry events
    // that create the items, so the value cannot be handed over at registration. Changing one of these is
    // picked up by every stack in play, and a stack lying in a chest keeps whatever it had.

    /** How much use an iron hammer takes before it breaks. */
    private static final ModConfigSpec.IntValue HAMMER_DURABILITY = BUILDER
        .comment("Uses an iron hammer lasts before breaking. Default 512.")
        .defineInRange("hammerDurability", 512, 1, 1000000);

    /** How much use the obsidian hammer takes — four times the iron one, as its material suggests. */
    private static final ModConfigSpec.IntValue OBSIDIAN_HAMMER_DURABILITY = BUILDER
        .comment("Uses an obsidian hammer lasts before breaking. Default 2048.")
        .defineInRange("obsidianHammerDurability", 2048, 1, 1000000);

    /** How much use the handheld fan takes: one per second of holding it open. */
    private static final ModConfigSpec.IntValue FAN_DURABILITY = BUILDER
        .comment("Uses the handheld fan lasts before breaking, one per second of use. Default 512.")
        .defineInRange("fanDurability", 512, 1, 1000000);

    /** How much use the handheld saw takes: one per cut it actually makes. */
    private static final ModConfigSpec.IntValue SAW_DURABILITY = BUILDER
        .comment("Uses the handheld saw lasts before breaking, one per cut. Default 512.")
        .defineInRange("sawDurability", 512, 1, 1000000);

    /** How much use the spout gun takes: one per pour, purge or transfer. */
    private static final ModConfigSpec.IntValue SPOUT_GUN_DURABILITY = BUILDER
        .comment("Uses the spout gun lasts before breaking, one per operation. Default 512.")
        .defineInRange("spoutGunDurability", 512, 1, 1000000);

    /** How much use the stirring rod takes: one per mixing operation it completes. */
    private static final ModConfigSpec.IntValue STIRRING_ROD_DURABILITY = BUILDER
        .comment("Uses the stirring rod lasts before breaking, one per completed mixing operation. Default 512.")
        .defineInRange("stirringRodDurability", 512, 1, 1000000);

    /**
     * How many tool uses one full backtank of pressurized air pays for.
     *
     * <p>This is Create's own question, asked with Create's own arithmetic: the fan, the saw and the spout
     * gun hand it to {@code BacktankUtil.canAbsorbDamage}, which spends {@code airInBacktank / usesPerTank}
     * air (at least one) out of the emptiest tank the wearer has and answers whether it could. While it can,
     * the tool is not worn at all — the tank is being used up in its place — and once no tank has air left,
     * the tool starts taking damage itself.
     */
    private static final ModConfigSpec.IntValue BACKTANK_USES_PER_TANK = BUILDER
        .comment("How many tool uses a full backtank of air is worth for the fan, saw and spout gun: air is spent first, and the tool is only damaged once no tank has any left. Default 256.")
        .defineInRange("backtankUsesPerTank", 256, 1, 1000000);

    // --- which processing this mod's tools are offered as a catalyst for -----------------------------

    /**
     * What the two lists below mean.
     *
     * <p>{@code OFF} reads neither of them, which is the default and what a pack that has never thought
     * about this wants. {@code BLACKLIST} makes the tools refuse the recipes the blacklist matches and lets
     * everything else through. {@code WHITELIST} does the opposite: only the recipes the whitelist matches
     * may be worked by hand. Both lists are always in the file; the mode is what decides which one is read.
     */
    private static final ModConfigSpec.EnumValue<RecipeFilter.Mode> RECIPE_CATALYST_FILTER_MODE = BUILDER
        .comment("Which of the two lists below is read, and what a match means.",
            "",
            "OFF       - neither list is read; every recipe this mod's tools can do, they do (default).",
            "BLACKLIST - a recipe the blacklist matches cannot be worked by this mod's hand tools, and this",
            "            mod's items are not offered for it in JEI. Everything else is unaffected.",
            "WHITELIST - only the recipes the whitelist matches can be worked by hand; everything else is",
            "            refused.",
            "",
            "The mode applies to the mod's hand tools and to JEI only. Create's machines, and this mod's",
            "brass powered saw, are never filtered: the purpose of these lists is to make a recipe something",
            "a player has to automate.",
            "",
            "See the comments on recipeCatalystBlacklist below for what an entry looks like.")
        .defineEnum("recipeCatalystFilterMode", RecipeFilter.Mode.OFF);

    /**
     * The recipes this mod's tools are kept out of, in blacklist mode.
     *
     * <p>One rule per line, each an object with any of {@code input}, {@code output}, {@code id}, {@code mod},
     * {@code type} and {@code tool}. A field that is written has to hold, and every field written in one entry
     * has to hold together; several entries are alternatives. Values may be regular expressions between
     * slashes. See {@link RecipeFilter} for the whole of it.
     *
     * <p>{@code tool} is the one that names the tool rather than the recipe: {@code crushing} is the obsidian
     * hammer's crushing function, {@code milling} is both hammers', {@code fan} is the handheld fan's bulk
     * processing, and a tool's item id works as well as its function. It may be one name or a list of them.
     *
     * <p>A single string rather than a config list, because an entry is full of the punctuation a list wants
     * escaped — quotes, commas, braces — and a line of it is exactly what its author wants to write. Several
     * entries are therefore several lines, which is what a TOML multi-line string is for.
     */
    private static final ModConfigSpec.ConfigValue<String> RECIPE_CATALYST_BLACKLIST = BUILDER
        .comment("Recipes this mod's hand tools are KEPT OUT OF, one entry per line. Read only when",
            "recipeCatalystFilterMode is BLACKLIST.",
            "",
            "AN ENTRY is one rule, written either as an object or as a list of pairs:",
            "    {type: \"milling\"}",
            "    {output: \"/create:.*/\", tool: \"crushing\"}",
            "    type=milling",
            "    output=/create:.*/,tool=crushing",
            "Every field an entry writes has to hold for a recipe to match it, so an entry with several fields",
            "is one rule and not several. Several entries are alternatives: a recipe any one of them matches",
            "is refused. An entry that writes no field at all is ignored.",
            "",
            "THE FIELDS, and what each is matched against:",
            "  input  - every item id the recipe accepts, e.g. minecraft:raw_iron",
            "  output - the id of the item the recipe reports as its result; a recipe with several chanced",
            "           outputs is read by the first of them, e.g. create:crushed_raw_iron",
            "  id     - the recipe's own id, e.g. create:crushing/raw_iron",
            "  mod    - the namespace of that id, e.g. create",
            "  type   - the kind of processing: one of the names in the list below, or a recipe type id such",
            "           as create:mixing (writing just mixing does the same)",
            "  tool   - which tool the rule is about: a function name from the list below, or the tool's own",
            "           item id such as create_ai:obsidian_hammer. One name or a list of them, e.g.",
            "           {tool: [\"milling\", \"crushing\"]}",
            "Fluid ingredients are not matched: a filter entry names items.",
            "",
            "REGULAR EXPRESSIONS: any field may be wrapped in slashes to be read as a regular expression",
            "instead of a literal string. /create:.+/ matches every id in Create's namespace; without the",
            "slashes the text is matched exactly, punctuation and all, so create:crushing/raw_iron means that",
            "one recipe and minecraft.stick means a literal dot rather than any character.",
            "",
            "THE KINDS OF PROCESSING, for type and tool:",
            "  fan                 - the handheld fan blowing an item: blasting, smoking, smelting, splashing",
            "                        and haunting recipes",
            "  mixing              - the stirring rod in a basin",
            "  compacting          - a hammer compressing a basin's contents",
            "  pressing            - a hammer pressing a depot",
            "  milling             - a hammer's sneaking grind, the millstone's recipes",
            "  crushing            - the obsidian hammer's sneaking grind, the crushing wheels' recipes",
            "  sawing              - the handheld mechanical saw, cutting recipes",
            "  filling             - the spout gun filling an item",
            "  deploying           - the artisan's goggles applying an item, as a deployer would",
            "  mechanical_crafting - the processing table, including ordinary crafting recipes laid out on it",
            "",
            "EXAMPLE - crushing and milling left to the machines, one other mod left entirely alone, and one",
            "recipe of ours refused (a TOML multi-line string, so each rule gets its own line):",
            "recipeCatalystBlacklist = \"\"\"",
            "{type: \"crushing\"}",
            "{mod: \"some_other_mod\"}",
            "{output: \"/create:crushed_raw_(iron|copper)/\", tool: \"crushing\"}",
            "\"\"\"",
            "",
            "Empty by default.")
        .define("recipeCatalystBlacklist", "");

    /**
     * The recipes this mod's tools are kept to, in whitelist mode.
     *
     * <p>Written exactly like the blacklist — same fields, same spellings, same regular expressions, and the
     * same rule that every field in an entry has to hold — but read the other way round: a recipe that no
     * entry matches cannot be worked by hand.
     */
    private static final ModConfigSpec.ConfigValue<String> RECIPE_CATALYST_WHITELIST = BUILDER
        .comment("Recipes this mod's hand tools ARE allowed to work, and nothing else. Read only when",
            "recipeCatalystFilterMode is WHITELIST. Written exactly like the blacklist above — same fields,",
            "same two spellings, same regular expressions, same all-fields-must-hold.",
            "",
            "EXAMPLE - only Create's own recipes may be worked by hand, and only the crushing and milling of",
            "raw ores at that:",
            "recipeCatalystWhitelist = \"\"\"",
            "{id: \"/create:.*/\", tool: [\"crushing\", \"milling\"]}",
            "\"\"\"",
            "",
            "Empty by default. Note that an empty whitelist with the mode set to WHITELIST refuses every",
            "recipe: the tools would do nothing at all.")
        .define("recipeCatalystWhitelist", "");

    // --- the spout gun ------------------------------------------------------------------------------

    /** The most fluid one right-click may move between the gun and a container. */
    private static final ModConfigSpec.IntValue SPOUT_GUN_TRANSFER_LIMIT = BUILDER
        .comment("Most fluid (in mB) the spout gun moves in one right-click, whether filling from a container or emptying into one. The amount a gun is set to never goes above it. Default 1000.")
        .defineInRange("spoutGunTransferLimit", 1000, 1, 1000000);

    /**
     * How much the spout gun's tank holds.
     *
     * <p>Eight buckets by default. The gun is a hand tool rather than a pipeline, so the tank is there to be
     * carried around rather than to hold a factory's worth of fluid — but it is a config value because a pack
     * that hands out larger jobs (or wants the gun to be a real portable tank) should not have to patch the
     * mod to say so.
     */
    private static final ModConfigSpec.IntValue SPOUT_GUN_CAPACITY = BUILDER
        .comment("How much fluid (in mB) the spout gun's tank holds. Default 8000, which is eight buckets.")
        .defineInRange("spoutGunCapacity", 8000, 1, 1000000);

    // --- the hammer in a fight ----------------------------------------------------------------------

    /**
     * How much harder a hammer knocks back than the weaponless attack it is standing in for.
     *
     * <p>Vanilla gives every hit a knockback of its own — {@code LivingEntity}, not the attacker — and this
     * multiplier is applied to that, which is why it is a multiple and not a number to add: 4.5 turns the
     * usual shove into something that lifts a mob off its feet.
     */
    private static final ModConfigSpec.DoubleValue HAMMER_KNOCKBACK_MULTIPLIER = BUILDER
        .comment("How many times the usual knockback a hammer's hit delivers. Default 4.5.")
        .defineInRange("hammerKnockbackMultiplier", 4.5D, 1.0D, 20.0D);

    // --- the saw's sweeping cut ---------------------------------------------------------------------

    /** How much damage one sweep of the saw does to each enemy it catches. */
    private static final ModConfigSpec.DoubleValue SAW_MELEE_DAMAGE = BUILDER
        .comment("Damage one sweep of the handheld saw deals to each enemy in front of it. Default 3.0 (1.5 hearts).")
        .defineInRange("sawMeleeDamage", 3.0D, 0.0D, 100.0D);

    /**
     * How far in front of the player the saw reaches, in blocks — measured from the eyes to the middle of
     * what it cuts.
     *
     * <p>From the eyes, not from the feet, which is why the number reads a little larger than the gap it
     * leaves: three and a half blocks is about three blocks of clear air between the player and what the
     * blade catches. It is read on every sweep, so a change to it is felt on the next half second rather
     * than at the next restart.
     */
    private static final ModConfigSpec.DoubleValue SAW_MELEE_RANGE = BUILDER
        .comment("How far in front of the player the handheld saw's sweep reaches, in blocks, from the eyes. Default 3.5.")
        .defineInRange("sawMeleeRange", 3.5D, 0.5D, 8.0D);

    /** How often the saw sweeps while the use key is held. */
    private static final ModConfigSpec.IntValue SAW_MELEE_INTERVAL_TICKS = BUILDER
        .comment("Ticks between one sweep of the handheld saw and the next: 10 ticks is twice a second. Default 10.")
        .defineInRange("sawMeleeIntervalTicks", 10, 1, 200);

    /** How long the Slowness a sweep leaves behind lasts, refreshed on every sweep. */
    private static final ModConfigSpec.IntValue SAW_MELEE_SLOWNESS_TICKS = BUILDER
        .comment("Ticks of Slowness III one saw sweep applies to each enemy it hits: 20 ticks is one second. Default 20.")
        .defineInRange("sawMeleeSlownessTicks", 20, 1, 1200);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** How hard a nozzle-fitted fan pushes, as a multiple of Create's own factors. */
    public static double nozzlePushStrength() {
        return NOZZLE_PUSH_STRENGTH.get();
    }

    /** How large the socket's item is drawn on the fan, as a fraction of the item's own size. */
    public static float socketItemScale() {
        return (float) (double) SOCKET_ITEM_SCALE.get();
    }

    /** Ticks a hammer's press leaves every carried stack on cooldown for. */
    public static int hammerPressCooldown() {
        return HAMMER_PRESS_COOLDOWN.get();
    }

    /** Ticks a hammer's sneak-strike grind leaves every carried stack on cooldown for. */
    public static int hammerGrindCooldown() {
        return HAMMER_GRIND_COOLDOWN.get();
    }

    /** Ticks one deployer step leaves every carried stack on cooldown for. */
    public static int deployerCooldown() {
        return DEPLOYER_COOLDOWN.get();
    }

    /** Ticks the spout gun has to be held before it pours or purges. */
    public static int spoutGunChargeTicks() {
        return SPOUT_GUN_CHARGE_TICKS.get();
    }

    /** Ticks one stroke of the handheld mechanical saw takes. */
    public static int sawChargeTicks() {
        return SAW_CHARGE_TICKS.get();
    }

    /** How fast a stirring rod stirs, in rpm — the mixing efficiency. */
    public static float stirringSpeed() {
        return (float) (double) STIRRING_SPEED.get();
    }

    /** The rpm a press strike sounds like it is running at. */
    public static float pressStrikeSpeed() {
        return (float) (double) PRESS_STRIKE_SPEED.get();
    }

    /** Ticks a held fan, saw or stirring rod lasts before the hold expires on its own. */
    public static int toolHoldTicks() {
        return TOOL_HOLD_TICKS.get();
    }

    /** How far the handheld fan's air reaches, in blocks. */
    public static int fanRange() {
        return FAN_RANGE.get();
    }

    /** How far the stirring rod leans from upright while stirring, in degrees — how wide it stirs. */
    public static float stirTiltDegrees() {
        return (float) (double) STIR_TILT_DEGREES.get();
    }

    /** How many whole turns the stirring rod makes per mixing operation. */
    public static float stirTurnsPerOperation() {
        return (float) (double) STIR_TURNS_PER_OPERATION.get();
    }

    /** Uses an iron hammer lasts before breaking. Read while the item is registered: a change needs a restart. */
    public static int hammerDurability() {
        return HAMMER_DURABILITY.get();
    }

    /** Uses an obsidian hammer lasts before breaking. Read at registration: a change needs a restart. */
    public static int obsidianHammerDurability() {
        return OBSIDIAN_HAMMER_DURABILITY.get();
    }

    /** Uses the handheld fan lasts before breaking. Read at registration: a change needs a restart. */
    public static int fanDurability() {
        return FAN_DURABILITY.get();
    }

    /** Uses the handheld saw lasts before breaking. Read at registration: a change needs a restart. */
    public static int sawDurability() {
        return SAW_DURABILITY.get();
    }

    /** Uses the spout gun lasts before breaking. Read at registration: a change needs a restart. */
    public static int spoutGunDurability() {
        return SPOUT_GUN_DURABILITY.get();
    }

    /** Uses the stirring rod lasts before breaking. Read at registration: a change needs a restart. */
    public static int stirringRodDurability() {
        return STIRRING_ROD_DURABILITY.get();
    }

    /** How many tool uses a full backtank of air pays for, for the fan, the saw and the spout gun. */
    public static int backtankUsesPerTank() {
        return BACKTANK_USES_PER_TANK.get();
    }

    /** Most fluid, in mB, the spout gun moves in one right-click. */
    public static int spoutGunTransferLimit() {
        return SPOUT_GUN_TRANSFER_LIMIT.get();
    }

    /** How the recipe catalyst lists are read. */
    public static RecipeFilter.Mode recipeCatalystFilterMode() {
        return RECIPE_CATALYST_FILTER_MODE.get();
    }

    /** The recipes this mod's tools are kept out of. */
    public static List<String> recipeCatalystBlacklist() {
        return entries(RECIPE_CATALYST_BLACKLIST.get());
    }

    /** The recipes this mod's tools are kept to. */
    public static List<String> recipeCatalystWhitelist() {
        return entries(RECIPE_CATALYST_WHITELIST.get());
    }

    /**
     * The lines of a list setting, one entry each.
     *
     * <p>The brackets and trailing commas of a list pasted in whole are tolerated, so a pack author who writes
     * the entries as an array and then spreads them over lines — or leaves them on one — gets the same rules
     * either way.
     */
    private static List<String> entries(String text) {
        String body = text.trim();
        if (body.startsWith("["))
            body = body.substring(1);
        if (body.endsWith("]"))
            body = body.substring(0, body.length() - 1);

        List<String> entries = new java.util.ArrayList<>();
        for (String line : body.split("\\R")) {
            String entry = line.trim();
            while (entry.endsWith(","))
                entry = entry.substring(0, entry.length() - 1)
                    .trim();
            if (!entry.isEmpty())
                entries.add(entry);
        }
        return entries;
    }

    /** How much fluid, in mB, the spout gun's tank holds. */
    public static int spoutGunCapacity() {
        return SPOUT_GUN_CAPACITY.get();
    }

    /** How many times the usual knockback a hammer's hit delivers. */
    public static float hammerKnockbackMultiplier() {
        return (float) (double) HAMMER_KNOCKBACK_MULTIPLIER.get();
    }

    /** Damage one sweep of the handheld saw deals to each enemy in front of it. */
    public static float sawMeleeDamage() {
        return (float) (double) SAW_MELEE_DAMAGE.get();
    }

    /** How far in front of the player the saw's sweep reaches, in blocks. */
    public static double sawMeleeRange() {
        return SAW_MELEE_RANGE.get();
    }

    /** Ticks between one sweep of the handheld saw and the next. */
    public static int sawMeleeIntervalTicks() {
        return SAW_MELEE_INTERVAL_TICKS.get();
    }

    /** Ticks of Slowness III one saw sweep applies to each enemy it hits. */
    public static int sawMeleeSlownessTicks() {
        return SAW_MELEE_SLOWNESS_TICKS.get();
    }
}
