package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.CreateAI;
import io.github.Ling.create_ai.item.ArtisanGogglesItem;
import io.github.Ling.create_ai.table.WorkbenchInteractions;

import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The artisan's goggles worn on the head, standing in for a deployer over an ordinary depot.
 *
 * <p>The deployer step is {@link DeployerActions}, which is written against Create's
 * {@link DepotBlockEntity} rather than against any particular block. That is the whole of the
 * generalisation needed here: the deployer recipe search, the sequenced assembly step, the vanilla
 * axe and honeycomb treatments, the routing of the products into the depot's output slots and the
 * spending of the held item all work on {@code create:depot} as they stand. Our own processing table
 * used to be a depot and answered these clicks too; it is a plain workbench now and is not a
 * {@code DepotBlockEntity}, so the goggles pass over it — and its own sneaking click, which pushes its
 * layout along, is {@link WorkbenchInteractions}'.
 *
 * <p>The click cannot come from the item. What gets installed is the ingredient in the player's hand,
 * so a {@code useOn} on the goggles would have to read the other hand and would fire on the item's own
 * right-click, which is how the helmet slot is filled. The click is therefore read from
 * {@code PlayerInteractEvent.RightClickBlock}, and only when all of these hold:
 *
 * <ul>
 * <li>the goggles are in the helmet slot,
 * <li>the player is sneaking, which is what separates this from an ordinary click,
 * <li>the clicked block entity is a {@link DepotBlockEntity} holding an item,
 * <li>and the held stack passes the same {@link DeployerActions#cannotDeploy} test the table uses.
 * </ul>
 *
 * <p>When any of them does not hold, this class returns without touching the event, and the click
 * carries on to whatever would have handled it. When they all hold, the click is <em>consumed</em>:
 * the event's cancellation result is set to {@code SUCCESS} first — cancellations default to
 * {@code PASS}, which would have the client keep trying interactions — and then the event is
 * cancelled, which is the one switch that stops the block's own use and the held item's own use for
 * this click. Doing so on both sides keeps the client's prediction and the server's answer the same;
 * only the server copy acts.
 *
 * <p>Nothing here paces the click: {@link DeployerActions#deploy} leaves its own half second of item
 * cooldown behind, and the client stops sending clicks for an item that is cooling down.
 */
@EventBusSubscriber(modid = CreateAI.MOD_ID)
public final class ArtisanGogglesOnDepots {

    private ArtisanGogglesOnDepots() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!ArtisanGogglesItem.isWorn(player) || !player.isShiftKeyDown())
            return;

        Level level = event.getLevel();
        DepotBlockEntity depot = DepotToolActions.depotAt(level, event.getPos());
        if (depot == null)
            return;
        DepotBehaviour behaviour = depot.getBehaviour(DepotBehaviour.TYPE);
        if (behaviour == null)
            return;
        // What a deployer works on: something already lying in front of it.
        ItemStack onDepot = behaviour.getHeldItemStack();
        if (onDepot.isEmpty())
            return;

        // The stack the click came from, which is the ingredient — never the goggles. An empty hand has
        // nothing to install, and a tool's right-click already means its own action.
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || CreateAI.isCustomTool(held))
            return;
        if (DeployerActions.cannotDeploy(level, onDepot, held, player, event.getHand(), event.getHitVec()))
            return;

        // The click is this action's: consume it, so neither the depot's own take-and-swap nor the
        // held item's use runs on the same click. Cancelling stops useBlock and useItem both; the
        // cancellation result is what the caller returns instead, and SUCCESS is what a deployer that
        // did something reports.
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        // The client copy stops here — it has consumed the click, which is the whole of what it
        // should do, and the server is the side that owns the depot, the recipe and the inventory.
        if (level.isClientSide)
            return;

        DeployerActions.deploy(level, depot, player, event.getHand(), event.getHitVec());
    }
}
