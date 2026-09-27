package io.github.Ling.create_ai;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * This mod's common config.
 *
 * <p>Empty on purpose. The scaffold shipped four entries that exist to demonstrate NeoForge's config
 * API — a dirt-block flag, a magic number, its introduction text and a list of items to log — and
 * nothing in the mod ever read them. What they did do was print lines into the console and put four
 * meaningless options in front of anyone who opened the file.
 *
 * <p>The spec stays, because a config file is the right home for the first option that does need one:
 * add entries to the builder above, then read them from {@code SPEC} — and if reading them needs to
 * happen at load time, add a {@code @SubscribeEvent} method for {@code ModConfigEvent} here and put
 * {@code @EventBusSubscriber(modid = Create_ai.MODID)} back on the class. That annotation must NOT be
 * here while the class has no listener methods: FML registers every class carrying it, and a class
 * with nothing to subscribe throws, which takes the whole mod down at construction.
 */
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static final ModConfigSpec SPEC = BUILDER.build();
}
