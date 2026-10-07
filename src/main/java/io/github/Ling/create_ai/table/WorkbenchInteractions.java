package io.github.Ling.create_ai.table;

import io.github.Ling.create_ai.block.ProcessingTableBlock;
import io.github.Ling.create_ai.block.ProcessingTableBlockEntity;
import io.github.Ling.create_ai.Create_ai;

import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The one click the workbench cannot be given by its own block: sneaking with something in hand.
 *
 * <p>Everything else about the table is answered by {@link ProcessingTableBlock}'s own
 * {@code useItemOn} / {@code useWithoutItem}. This one cannot be, and the reason is in vanilla's click flow:
 * before either of those methods is reached, the server checks whether the player is sneaking <em>and</em>
 * holding something in a hand, and if so it skips the block entirely and offers the click to the item
 * instead ({@code ServerPlayerGameMode.useItemOn}). A player who sneaks while carrying an ingredient is
 * therefore invisible to the table, which is exactly the player who wants to push the layout along.
 *
 * <p>NeoForge fires an event before that decision, with the two answers vanilla is about to read as
 * settable states. Cancelling it answers {@code false} to both: the block does not get the click and the
 * item does not either, and the move has already been made here. Cancelling is what keeps a sneaking wrench
 * from wrenching and a sneaking block from placing.
 *
 * <p>Vanilla fires the same event on the client, before its own prediction of the click, so this runs on
 * both sides and cancels on both; only the server changes the table. Both hands are cancelled and only the
 * main hand moves — the client retries an unconsumed click with the other hand, and a move on each of those
 * attempts is one click moving the layout twice.
 */
@EventBusSubscriber(modid = Create_ai.MODID)
public final class WorkbenchInteractions {

    private WorkbenchInteractions() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown())
            return;

        Level level = event.getLevel();
        if (!(level.getBlockEntity(event.getPos()) instanceof ProcessingTableBlockEntity table))
            return;

        // Busy: the completion animation is running, so the click is the table's but there is nothing to push.
        if (table.isCrafting()) {
            if (!level.isClientSide)
                TableAssembly.announceCrafting(player);
            event.setCanceled(true);
            return;
        }

        // Both hands answer, but only one of them does anything. A click the client's main hand leaves
        // unconsumed is tried again with the other hand, and both attempts arrive here: moving on each of
        // them moved the layout twice per click, which is exactly the "one click, two cells" a player sees.
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            // A click on the bottom face, or dead centre on the top, asks for no direction: the click is
            // still the table's — it is not the held item's — but nothing moves. Which way a click that does
            // ask is read is the table's own movement mode's business.
            Direction direction = ProcessingTableBlock.pushDirection(table, event.getHitVec());
            if (direction != null && !level.isClientSide)
                table.move(direction);
        }

        event.setCanceled(true);
    }
}
