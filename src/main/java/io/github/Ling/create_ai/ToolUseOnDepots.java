package io.github.Ling.create_ai;

import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Keeps a tool from rummaging in a depot, on Create's own depot.
 *
 * <p>{@code create:depot} is Create's block and cannot be overridden, and its hand interaction is a
 * single call that both empties the depot and puts the held stack down. So a tool's right-click is
 * redirected instead: the block interaction is switched off for that click and the item interaction
 * is left alone, which is exactly "do not swap, do the tool's own action". The tool then compresses
 * or pours what is on the depot through {@link DepotToolActions}.
 *
 * <p>An <em>empty</em> depot is left completely alone, so a tool can still be put down on it. That is
 * deliberate: a spout above a depot fills whatever is on it, and the spout gun is a fluid container,
 * so parking one there is how it gets filled.
 *
 * <p>This mod's own table is not touched — it refuses tools as cargo outright.
 */
@EventBusSubscriber(modid = Create_ai.MODID)
public final class ToolUseOnDepots {

    private ToolUseOnDepots() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        // A tool in either hand means this click belongs to the tool, and not just when the tool is
        // the stack that was clicked with: the client repeats an interaction that passed with its
        // other hand, and that retry arrives with an empty stack, which Create's depot would read as
        // a bare-handed take.
        if (!Create_ai.isCustomTool(event.getItemStack())
            && !Create_ai.isCustomTool(otherHand(player, event.getHand())))
            return;

        Level level = event.getLevel();
        DepotBlockEntity depot = DepotToolActions.depotAt(level, event.getPos());
        // Create's depot only: our own table answers a tool's click itself.
        if (depot == null || depot instanceof ProcessingTableBlockEntity)
            return;
        if (isEmpty(depot))
            return;

        // Skip the depot's take-and-swap for this click, and let the tool's own useOn run.
        event.setUseBlock(TriState.FALSE);
    }

    /** The hand the click did not come from, which is where a retry would look for a tool. */
    private static ItemStack otherHand(Player player, InteractionHand hand) {
        return player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND
            : InteractionHand.MAIN_HAND);
    }

    /** Nothing on top and nothing in the output slots, which is what Create's own depot calls empty. */
    private static boolean isEmpty(DepotBlockEntity depot) {
        DepotBehaviour behavior = DepotToolActions.behaviorOf(depot);
        return behavior != null && behavior.isEmpty();
    }
}
