package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.item.StirringRodItem;
import io.github.Ling.create_ai.tool.BasinToolActions;
import io.github.Ling.create_ai.tool.MixingCycle;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/**
 * Who is stirring a basin, where, and how far into the stir they are — what the stirring rod's animation
 * is drawn from.
 *
 * <p>The rod is not in the player's hand while they stir: it is in the basin, turning (
 * {@link StirringRodStirRenderer}), and the copy in their hand is not drawn (
 * {@link StirringRodItemRenderer}) — not in their own first-person view, and not in anyone's third-person
 * view of them either. Both of those need the same few facts, so they are worked out once per client tick
 * and here, rather than every frame in each renderer.
 *
 * <p><b>Other players, without a packet.</b> Everything this needs is already synced: whether a player is
 * using an item (a living-entity flag), which item it is, the countdown of that use — the client sets it
 * from the item's own duration when the flag arrives and ticks it down like the server does — and where the
 * player is standing and looking. So each client works the same thing out for every player it can see, off
 * the same numbers the server is working from, and no packet of this mod's own is involved. What is
 * computed here is the stir's position and phase only; whether the stir does anything at all is the
 * server's business and is not in question.
 *
 * <p>Client-only: it is registered on the client bus by way of {@code value = Dist.CLIENT}, and the
 * renderers that read it are client-only too.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class StirringRodStir {

    /**
     * One stirrer this tick: the basin they are stirring, how long their hold has been going, and the
     * rhythm the basin's recipe is being stirred at.
     *
     * @param player  the player whose stir this is
     * @param basin   the basin they are holding the rod over
     * @param elapsed ticks the hold has been running
     * @param delay   ticks between one mixing operation landing and the next, from {@link MixingCycle}
     */
    public record Stir(Player player, BlockPos basin, int elapsed, int delay) {
    }

    /** Every stirrer in the level, rebuilt once per client tick. Read by the renderers, never written by them. */
    private static final List<Stir> STIRRING = new ArrayList<>();

    /**
     * The player whose model is being drawn right now, or null when no player is being drawn.
     *
     * <p>An item renderer is not told whose hand it is drawing — but a player's own render is, and
     * {@link RenderPlayerEvent.Pre} brackets it, so the held item drawn in the middle of one can be traced
     * back to the player it belongs to. This is what lets a rod be left out of the hand of the player
     * holding it in third person, for everyone watching, rather than only in the stirrer's own
     * first-person view.
     */
    @Nullable
    private static Player renderedPlayer;

    private StirringRodStir() {
    }

    /** The stirs to draw this frame, empty when nobody is stirring. */
    public static List<Stir> current() {
        return STIRRING;
    }

    /** Whether this player is stirring a basin right now. */
    public static boolean isStirring(Player player) {
        for (Stir stir : STIRRING)
            if (stir.player() == player)
                return true;
        return false;
    }

    /**
     * Whether a rod being drawn in this display context belongs to a player who is stirring with it, and so
     * must not be drawn.
     *
     * <p>Only the hand contexts are asked about: the rod is still the item in the inventory, on the ground,
     * in an item frame and in the creative menu, and hiding it there would be hiding the item.
     *
     * <p>Which player is stirring depends on the view. A first-person hand is the local player's own and is
     * not drawn inside any player's render, so it is the local player that is asked about. A third-person
     * hand is somebody's hand in the world, drawn as part of that player's model, so it is
     * {@link #renderedPlayer} that is asked about — whether that is this player in a third-person view of
     * themselves or another player entirely.
     */
    public static boolean hideHeldRod(ItemDisplayContext transformType) {
        if (!isHandView(transformType))
            return false;
        Player player = transformType.firstPerson() ? Minecraft.getInstance().player : renderedPlayer;
        return player != null && isStirring(player);
    }

    /** Whether this is a hand — the local player's own in front of the camera, or anyone's in the world. */
    private static boolean isHandView(ItemDisplayContext transformType) {
        return transformType.firstPerson()
            || transformType == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || transformType == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        STIRRING.clear();

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return;
        for (Player player : level.players()) {
            Stir stir = stirOf(player);
            if (stir != null)
                STIRRING.add(stir);
        }
    }

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        renderedPlayer = event.getEntity();
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        renderedPlayer = null;
    }

    /**
     * What the player is stirring, or null when they are not stirring anything: no rod in use, no basin
     * under their eyes, or a basin whose contents no mixing recipe matches.
     *
     * <p>The last of those is the same question the item itself asks before a hold is even allowed to
     * start ({@link BasinToolActions#mixingDelay}), so a rod held over an empty basin draws nothing and
     * stays in the hand.
     */
    @Nullable
    private static Stir stirOf(Player player) {
        if (!player.isUsingItem())
            return null;
        if (!player.getUseItem()
            .is(Create_ai.STIRRING_ROD.get()))
            return null;

        BasinBlockEntity basin = BasinToolActions.targetOf(player);
        if (basin == null)
            return null;
        int delay = BasinToolActions.mixingDelay(basin);
        if (delay <= 0)
            return null;

        return new Stir(player, basin.getBlockPos(), elapsed(player), delay);
    }

    /**
     * How long this hold has been running, from the countdown the entity keeps — the same subtraction
     * {@link StirringRodItem#heldTicks} makes, and the same number on every client for a player being
     * watched as for that player themselves.
     */
    private static int elapsed(Player player) {
        return Math.max(Config.toolHoldTicks() - player.getUseItemRemainingTicks(), 0);
    }
}
