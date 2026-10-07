package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.Create_ai;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

/**
 * The creative inventory's half of an inventory right-click.
 *
 * <p>Everywhere else a click a player makes in an inventory becomes a click packet and the server runs the
 * interaction, which is why both of this mod's inventory handlers work server-side. The creative inventory
 * is the exception the NeoForge event is written around: its screen runs the player's own inventory menu
 * <em>itself</em>, locally, and never sends a click packet — {@code ItemStackedOnOtherEvent} is fired on the
 * client there and on nowhere else, so a server-side handler sees nothing at all and the interaction
 * silently does not happen. The client therefore has to do the work in that one case, and this is how the
 * result reaches the server.
 *
 * <p>Two things travel separately:
 * <ul>
 * <li>the <b>slot</b> an item sits in, told to the server here with {@link ServerboundSetCreativeModeSlotPacket}
 *     — the packet vanilla's own {@code CreativeInventoryListener} sends, and the only one that writes a
 *     player-inventory slot outside a click. It takes an index into the player's own inventory menu and
 *     only accepts 1..45, so the slot is looked up there rather than trusting the index the event carries:
 *     in a creative tab the clicked slot belongs to the screen's own menu, which wraps the player's
 *     inventory rather than being it;</li>
 * <li>the <b>carried</b> (cursor) stack, which has no packet of its own. That is vanilla's design rather
 *     than a gap here — with no click packet and no synchronizer on the client, the cursor in the creative
 *     inventory is the client's alone and the server's own carried stack is never read back from it. What
 *     the cursor holds becomes real when it is put down into a slot, which is the case above.</li>
 * </ul>
 *
 * <p>Client-only by construction: it is reached only from the creative branch of the two handlers, which is
 * guarded on the level's side, so a dedicated server never loads it.
 */
public final class CreativeSlotSync {

    private CreativeSlotSync() {
    }

    /**
     * Whether this click is <em>not</em> the creative inventory's own — the one click the client has to
     * answer itself, and so the one the server-side handlers must leave alone.
     *
     * <p>The screen, not the game mode, is what decides. In creative the click is still sent to the server
     * everywhere else (a chest, a machine's menu), and both sides would then apply the interaction, once per
     * side. Only the creative screen swallows the click whole.
     */
    public static boolean isNotCreativeScreenClick(Player player) {
        if (!player.hasInfiniteMaterials())
            return true;
        Minecraft minecraft = Minecraft.getInstance();
        return !(minecraft.screen instanceof CreativeModeInventoryScreen);
    }

    /**
     * Tells the server what a slot holds now, since the click that changed it never reached it.
     *
     * <p>Does nothing when the slot is not one of the player's own inventory slots, or has no counterpart
     * there: the creative screen's own listener still pushes the change a moment later, and this only makes
     * it immediate.
     */
    public static void pushSlot(Player player, Slot slot) {
        if (!(player instanceof LocalPlayer local))
            return;
        int index = inventoryMenuIndex(player, slot);
        if (index < 1 || index > 45)
            return;
        local.connection.send(new ServerboundSetCreativeModeSlotPacket(index, slot.getItem()));
    }

    /**
     * Puts a line under the crosshair, for the one case the client answers a click itself.
     *
     * <p>Everywhere else a click reaches the server, and the server is what whispers to the player; in the
     * creative screen there is no server side to the click at all, so the line has to be written here.
     */
    public static void showOverlay(Component message) {
        Minecraft.getInstance().gui.setOverlayMessage(message, false);
    }

    /**
     * Where a clicked slot lives in the player's own inventory menu, or -1 when it is not one of its slots.
     *
     * <p>Matched by what the slot actually reads and writes — its container and its index in it — because
     * the slot the event hands over may be the screen's wrapper around a player slot rather than that slot
     * itself, and a wrapper's own index means nothing to the server.
     */
    private static int inventoryMenuIndex(Player player, Slot slot) {
        for (int i = 0; i < player.inventoryMenu.slots.size(); i++) {
            Slot candidate = player.inventoryMenu.slots.get(i);
            if (candidate.container == slot.container && candidate.getContainerSlot() == slot.getContainerSlot())
                return i;
        }
        return -1;
    }
}
