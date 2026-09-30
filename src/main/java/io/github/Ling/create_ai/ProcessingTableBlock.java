package io.github.Ling.create_ai;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlock;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A processing table: Create's depot, with hand interaction narrowed so that custom tools have
 * something well defined to talk to.
 *
 * <p>Everything else is Create's, at the code level rather than copied: the block extends
 * {@link DepotBlock}, so the collision and outline shape, waterlogging, wrench behavior, items
 * dropped onto it, belt funnel output, path-finding, the comparator scale and the contents dropping
 * out on break are all inherited; the blockstate points straight at {@code create:block/depot/block},
 * so model and textures are literally Create's; and the block entity is a
 * {@link DepotBlockEntity} with Create's own {@link DepotBehaviour}, which is what lets Create's
 * {@code DepotRenderer} draw the held stack and the eight output slots exactly as on a depot.
 *
 * <p>Hand interaction keeps the depot's rules — put in with a hand, take out with an empty hand —
 * with two changes:
 * <ul>
 * <li>An occupied table does nothing instead of swapping its contents for what the player holds.</li>
 * <li>A custom tool neither goes in nor takes something out: its right-click always means the tool's
 *     own action (for the hammer, one press cycle). That is the only restriction on taking, and it is
 *     what makes tool interaction unambiguous.</li>
 * </ul>
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ProcessingTableBlock extends DepotBlock {

    public ProcessingTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends DepotBlockEntity> getBlockEntityType() {
        return Create_ai.PROCESSING_TABLE_BE.get();
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        // A custom tool is never placed here and never takes anything out: handing the click back to
        // the item pipeline is what lets the tool's own useOn run (for the hammer, a press cycle).
        if (Create_ai.isCustomTool(stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // Only the top face is interactive, same as create:depot.
        if (hitResult.getDirection() != Direction.UP)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        ProcessingTableBlockEntity table = tableAt(level, pos);
        if (table == null)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        DepotBehaviour depot = table.getBehaviour(DepotBehaviour.TYPE);
        if (depot == null)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        boolean tableEmpty = depot.getHeldItemStack()
            .isEmpty() && depot.isOutputEmpty();

        // Empty hand: take what is on the table, exactly like create:depot — unless a custom tool is
        // in the player's other hand. The client retries an interaction that passed with its other
        // hand, and that retry can arrive with an empty stack; a tool's click must never end up
        // meaning "take", so the take stays out of the way whenever a tool is held at all.
        if (stack.isEmpty()) {
            if (tableEmpty || Create_ai.isCustomTool(otherHand(player, hand)))
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (!level.isClientSide)
                table.takeContents(player);
            return ItemInteractionResult.SUCCESS;
        }

        // One stack is the whole capacity, so an occupied table gives no reaction either way — except
        // that something in hand is a deployer action: a right-click does to what is on the table what
        // Create's mechanical hand would do with it — the next step of a sequenced assembly, an
        // ingredient from a deploying recipe, or, for a tool, what vanilla would do to the block the
        // item stands for. One click is one step; the action's own cooldown paces the next one.
        if (!tableEmpty) {
            // Whether the deployer could do anything is what decides the animation, and it is a question
            // the client can answer for itself. A click that would change nothing is consumed without
            // the arm swinging — held use keys repeat block interactions every few ticks, and an arm
            // waving forever at an item it cannot touch is not what "nothing happens" should look like.
            //
            // Consumed, not passed: passing makes the client repeat the click with its other hand, and
            // that retry arrives bare-handed, which on a depot means "take the contents".
            if (DeployerActions.cannotDeploy(level, depot.getHeldItemStack(), stack, player, hand, hitResult))
                return ItemInteractionResult.CONSUME;
            if (!level.isClientSide)
                table.deployerUse(player, hand, hitResult);
            return ItemInteractionResult.SUCCESS;
        }
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;

        placeStack(player, depot, stack);
        return ItemInteractionResult.SUCCESS;
    }

    /**
     * Puts the held stack on an empty table, up to one stack: Create's own insertion clips it to the
     * item's max size, plays the depot sound and hands back whatever did not fit.
     */
    private static void placeStack(Player player, DepotBehaviour depot, ItemStack held) {
        TransportedItemStack transported = new TransportedItemStack(held.copy());
        transported.insertedFrom = player.getDirection();
        transported.prevBeltPosition = .25f;
        transported.beltPosition = .25f;
        ItemStack refused = depot.insert(transported, false);
        if (!player.isCreative())
            held.shrink(held.getCount() - refused.getCount());
        depot.blockEntity.notifyUpdate();
    }

    /** This table's block entity at a position, or null when the block there is not one of ours. */
    @Nullable
    private static ProcessingTableBlockEntity tableAt(Level level, BlockPos pos) {
        return DepotToolActions.depotAt(level, pos) instanceof ProcessingTableBlockEntity table ? table : null;
    }

    /** The hand the player is not interacting with, which is where a retry would look. */
    private static ItemStack otherHand(Player player, InteractionHand hand) {
        return player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND
            : InteractionHand.MAIN_HAND);
    }
}
