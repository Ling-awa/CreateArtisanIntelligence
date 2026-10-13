package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.item.HandheldFanItem;
import io.github.Ling.create_ai.item.HandheldMechanicalSawItem;
import io.github.Ling.create_ai.item.SpoutGunItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * What drives every tool cog this mod turns: once per client tick, ask the player which tool is being
 * used and hand each cog its answer.
 *
 * <p>The arithmetic — a rate that eased towards what the tool is doing, integrated into an angle — is
 * {@link ToolCogSpin}, one instance per cog. What is here is the half that has to know about the client:
 * the player, the item in their hand, and whether the use key is down. So the three tool cogs are the three
 * constants below, each with its own angle, and each renderer asks its own for this frame's rotation.
 *
 * <p>{@link #GUN_SPIN} is the spout gun's cog ({@link SpoutGunItemRenderer}), {@link #FAN_SPIN} the handheld
 * fan's ({@link FanCogSpinItemRenderer}) and {@link #SAW_SPIN} the handheld saw's
 * ({@link SawCogSpinItemRenderer}). All three share {@link ToolCogSpin}'s rates, so every cog drifts at the
 * same idle speed and winds up to the same turn per second while its tool is used, which is what makes the
 * three tools feel like the same machine. Which axis each cog turns about is the renderer's business, not
 * this class's: the gun's and the fan's turn about Z, the saw's about X.
 *
 * <p>Client-only, and on the client bus by way of {@code value = Dist.CLIENT} — which is also what keeps
 * it, and the three cogs' worth of state, from being loaded on a dedicated server. Neither tool's item
 * class refers to any of this; the renderers are attached in {@code Create_ai.ClientModEvents}, and the
 * item itself carries no client code at all.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class SpoutGunSpin {

    /** The spout gun's cog: {@link SpoutGunItemRenderer}'s, read by nothing else. */
    public static final ToolCogSpin GUN_SPIN = new ToolCogSpin();

    /** The handheld fan's cog: {@link FanCogSpinItemRenderer}'s, read by nothing else. */
    public static final ToolCogSpin FAN_SPIN = new ToolCogSpin();

    /** The handheld saw's cog: {@link SawCogSpinItemRenderer}'s, read by nothing else. */
    public static final ToolCogSpin SAW_SPIN = new ToolCogSpin();

    private SpoutGunSpin() {
    }

    /** One tick for every cog: the tool in hand is being used, the others are only held. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        Item inUse = player != null && player.isUsingItem() ? player.getUseItem()
            .getItem() : null;

        GUN_SPIN.tick(inUse instanceof SpoutGunItem);
        FAN_SPIN.tick(inUse instanceof HandheldFanItem);
        SAW_SPIN.tick(inUse instanceof HandheldMechanicalSawItem);
    }
}
