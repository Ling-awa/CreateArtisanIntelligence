package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.CreateAI;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * The brass powered saw's blade models, registered with Flywheel <em>before</em> the model events run.
 *
 * <p>A blade is not part of the saw's block model — Create draws it as a separate partial model — so
 * these six models are not referenced by any blockstate and would otherwise never be baked. Flywheel
 * bakes them by looking up {@code PartialModel.ALL} inside {@code ModelEvent.RegisterAdditional}:
 *
 * <pre>{@code
 * public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
 *     for (ResourceLocation modelLocation : PartialModel.ALL.keySet()) { ... }
 * }</pre>
 *
 * <p>That is a <em>snapshot</em>. A partial created after the event is never submitted for baking, so
 * {@code PartialModel.get()} quietly returns the missing model — no error is logged, and the blade
 * renders as a missing texture. Creating them lazily in the renderer's static fields does exactly
 * that, because those fields initialise when the renderer class loads, which happens during
 * {@code EntityRenderersEvent.RegisterRenderers} — well after model registration.
 *
 * <p>So this class is initialised from client setup, which runs before the model events, and the
 * renderer reads the constants from here rather than building its own.
 */
public final class BrassMechanicalSawBladeModels {

    public static final PartialModel BLADE_HORIZONTAL_ACTIVE = block("blade_horizontal_active");
    public static final PartialModel BLADE_HORIZONTAL_INACTIVE = block("blade_horizontal_inactive");
    public static final PartialModel BLADE_HORIZONTAL_REVERSED = block("blade_horizontal_reversed");
    public static final PartialModel BLADE_VERTICAL_ACTIVE = block("blade_vertical_active");
    public static final PartialModel BLADE_VERTICAL_INACTIVE = block("blade_vertical_inactive");
    public static final PartialModel BLADE_VERTICAL_REVERSED = block("blade_vertical_reversed");

    private BrassMechanicalSawBladeModels() {
    }

    /**
     * Forces this class to initialise, and therefore registers the six models with Flywheel in time for
     * the model events.
     *
     * <p>Called from client setup. The call itself does nothing — the static initializer above is the
     * point.
     */
    public static void register() {
    }

    private static PartialModel block(String name) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(CreateAI.MOD_ID,
            "block/brass_mechanical_saw/" + name));
    }
}
