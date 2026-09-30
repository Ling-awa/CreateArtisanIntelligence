package io.github.Ling.create_ai;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * This mod's common config.
 *
 * <p>It holds the mod's one tunable so far: how hard a nozzle-fitted handheld fan pushes. Everything else
 * the mod does is meant to match Create exactly, and a number that has to be a judgment call belongs in a
 * file a player can edit rather than in the code.
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
     * is inside the field is pushed and nothing else — the range stays the fan's own four blocks.
     */
    private static final ModConfigSpec.DoubleValue NOZZLE_PUSH_STRENGTH = BUILDER
        .comment("How much harder a nozzle-fitted handheld fan pushes than Create's own nozzle: 1.0 is Create's own numbers, 2.0 is twice as hard. The range is not affected.")
        .defineInRange("nozzlePushStrength", 2.0D, 0.0D, 10.0D);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** How hard a nozzle-fitted fan pushes, as a multiple of Create's own factors. */
    public static double nozzlePushStrength() {
        return NOZZLE_PUSH_STRENGTH.get();
    }
}
