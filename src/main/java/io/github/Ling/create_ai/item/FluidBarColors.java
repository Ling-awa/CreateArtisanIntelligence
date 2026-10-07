package io.github.Ling.create_ai.item;

import io.github.Ling.create_ai.Create_ai;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The color of a fluid that does not declare one.
 *
 * <p>Most fluids answer {@code IClientFluidTypeExtensions#getTintColor} with a color, and that is the
 * end of it. But a fluid may instead paint its color into its textures and report pure white as its
 * tint — vanilla's water and lava both do, and so do modded fluids that ship a colored texture rather
 * than a tinted one. For those, the only place the color exists is the texture itself, so this takes
 * the average of the fluid's still texture: every opaque pixel, red, green and blue each summed, then
 * divided by how many there were.
 *
 * <p>Client-only by construction — it needs the texture atlas, which only exists on the client — so it
 * is reached only from the item's bar color, which is drawn by the GUI. The answer is cached per
 * fluid, since a texture cannot change while the game is running and the bar is redrawn every frame.
 */
// A SpriteContents is AutoCloseable, and a sprite read here belongs to the texture atlas: nothing here owns
// one, and closing it would tear the atlas down under every other renderer.
@SuppressWarnings("resource")
final class FluidBarColors {

    /** No usable answer: the texture is missing, or every pixel of it is transparent. */
    public static final int UNKNOWN = 0;

    private static final Map<Fluid, Integer> CACHE = new HashMap<>();

    private FluidBarColors() {
    }

    /**
     * The average color of a fluid's still texture, as {@code 0xFFRRGGBB}, or {@link #UNKNOWN}.
     */
    static int textureAverage(FluidStack stack) {
        Fluid fluid = stack.getFluid();
        Integer cached = CACHE.get(fluid);
        if (cached != null)
            return cached;
        int color = sample(stack);
        CACHE.put(fluid, color);
        return color;
    }

    private static int sample(FluidStack stack) {
        // Neither of the two reads below can answer null: a bar is drawn by a GUI, so the client exists and
        // its atlas is up, and a fluid type always names a still texture. The null guards an earlier version
        // carried here were dead code, and are gone.
        Minecraft minecraft = Minecraft.getInstance();
        // Fluid textures are stitched into the block atlas — the same atlas Create's own fluid
        // renderer reads them from.
        ResourceLocation still = IClientFluidTypeExtensions.of(stack.getFluidType())
            .getStillTexture(stack);
        TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(still);
        if (sprite == null)
            return UNKNOWN;

        int width = sprite.contents()
            .width();
        int height = sprite.contents()
            .height();
        if (width <= 0 || height <= 0)
            return UNKNOWN;

        long red = 0;
        long green = 0;
        long blue = 0;
        int samples = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // ABGR, whatever the name says: NativeImage stores pixels as memPutInt(abgrColor).
                int pixel = sprite.getPixelRGBA(0, x, y);
                if ((pixel >>> 24) < 0x20)
                    // Transparent pixels are the shape of the sprite, not its color.
                    continue;
                red += pixel & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += (pixel >> 16) & 0xFF;
                samples++;
            }
        }
        if (samples == 0)
            return UNKNOWN;

        // The bar is read as 0xRRGGBB, so the channels go back in that order — not the ABGR they
        // came out in.
        return 0xFF000000 | (int) (red / samples) << 16 | (int) (green / samples) << 8 | (int) (blue / samples);
    }
}
