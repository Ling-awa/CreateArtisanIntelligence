package io.github.Ling.create_ai;

import java.util.List;

import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The processing table's block entity: Create's depot block entity, verbatim.
 *
 * <p>Storage, syncing, the item-handler capability, the eight output slots, contents dropping on break
 * and the renderer's data are all Create's own — {@code super.addBehaviours} installs Create's real
 * {@link DepotBehaviour} (which is what assigns {@code DepotBlockEntity.depotBehaviour}, the field
 * Create's {@code DepotRenderer} draws from), and nothing about it is overridden. That is why
 * {@code DepotRenderer} can be registered for this block and the products sitting in the output slots
 * are drawn exactly as they are on {@code create:depot}.
 *
 * <p>Everything a tool does to this table lives in {@link DepotToolActions} and {@link DeployerActions},
 * written against {@link DepotBlockEntity} rather than against this class, so the same tool actions work
 * on {@code create:depot} and on this. A press from the hammer is therefore the same instant press a
 * depot gets, with Create's sound and its crush particles, rather than a machine's timed cycle: the
 * table has no kinetics and nothing to pace.
 *
 * <p>The only deliberate deviation from {@code create:depot} is refusing tools as cargo, so a tool's
 * right-click can never put itself onto the table. Create's own depot is left as it is: it refuses
 * them only while it holds something (see {@link ToolUseOnDepots}).
 */
public class ProcessingTableBlockEntity extends DepotBlockEntity {

    public ProcessingTableBlockEntity(BlockPos pos, BlockState state) {
        super(Create_ai.PROCESSING_TABLE_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Create's own depot behavior, untouched: it assigns depotBehaviour itself and adds the
        // belt-input / item-handler sub-behaviors that make this a depot.
        super.addBehaviours(behaviours);

        // The one deviation: tools are not cargo. Applied to Create's own behavior instance.
        for (BlockEntityBehaviour behavior : behaviours) {
            if (behavior instanceof DepotBehaviour depot)
                depot.onlyAccepts(stack -> !Create_ai.isCustomTool(stack));
        }
    }

    /** Same capability Create exposes on its depot, wired to our own block entity type. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            Create_ai.PROCESSING_TABLE_BE.get(),
            (be, context) -> {
                DepotBehaviour depot = be.getBehaviour(DepotBehaviour.TYPE);
                return depot == null ? null : depot.itemHandler;
            }
        );
    }

    /**
     * Moves the table's contents into a player's inventory: the stack on top, plus anything sitting in
     * Create's eight processing output slots.
     *
     * <p>Only an empty hand may do this — the hand interaction in {@link ProcessingTableBlock} is the
     * single take-out path, and a custom tool's right-click is reserved for its own action.
     */
    public void takeContents(Player player) {
        if (level == null || level.isClientSide)
            return;
        DepotBehaviour depot = getBehaviour(DepotBehaviour.TYPE);
        if (depot == null)
            return;

        boolean tookSomething = false;
        ItemStack onTable = depot.getHeldItemStack();
        if (!onTable.isEmpty()) {
            player.getInventory()
                .placeItemBackInInventory(onTable);
            depot.removeHeldItem();
            tookSomething = true;
        }
        // Slot 0 is the held item; 1..8 are Create's processing output buffer, which is only
        // reachable through the public item handler because the field itself is package-private.
        for (int slot = 1; slot < depot.itemHandler.getSlots(); slot++) {
            ItemStack extracted = depot.itemHandler.extractItem(slot, 64, false);
            if (!extracted.isEmpty()) {
                player.getInventory()
                    .placeItemBackInInventory(extracted);
                tookSomething = true;
            }
        }
        if (tookSomething) {
            // Same feedback create:depot gives when its contents are picked up.
            level.playSound(null, getBlockPos(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, .2f,
                1f + level.getRandom()
                    .nextFloat());
            notifyUpdate();
        }
    }

    // --- deploying: one right-click with an item in hand ------------------------------------------

    /**
     * One of a player's right-clicks on this table while holding something.
     *
     * <p>There is no hold to sit through: the click is the action. What keeps a held use key from
     * firing the deployer over and over is the half second of item cooldown the action leaves behind —
     * see {@link DeployerActions#deploy} — which the client checks before it even sends the next click.
     *
     * <p>Nothing is reported back: the caller has already decided the click is the table's, and consumes it
     * whether the deployer found work or not.
     */
    public void deployerUse(Player player, InteractionHand hand, BlockHitResult hit) {
        if (level == null || level.isClientSide)
            return;
        DeployerActions.deploy(level, this, player, hand, hit);
    }
}
