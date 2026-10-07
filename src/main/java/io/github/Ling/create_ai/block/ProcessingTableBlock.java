package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.fan.FanCurrent;
import io.github.Ling.create_ai.table.TableAssembly;
import io.github.Ling.create_ai.table.TableMoveMode;
import io.github.Ling.create_ai.table.WorkbenchInteractions;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllShapes;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The artisan's workbench: the processing table, no longer a depot in any sense, and the way a player works a
 * crafting layout by hand.
 *
 * <p><b>What it kept.</b> The look, and only the look: the blockstate still points at Create's depot model
 * and {@link #getShape} still answers with the depot's flat casing, so it is the same table it always was to
 * look at. Everything else Create's depot brought with it — the item slot, the eight output slots, the
 * item-handler capability, the depot's own hand rules and every tool action written against a depot — is
 * gone. The block is a plain {@link Block} with an {@link EntityBlock} of its own, which is what makes it
 * stop being a target: a hammer, a saw, a spout gun or a pair of goggles looking for a depot finds nothing
 * here, because this is not one.
 *
 * <p><b>What a click means.</b> Four things, and nothing else:
 *
 * <ul>
 * <li><b>An item in hand</b> goes into the middle of the table, one item per click, and the first one placed
 *     on an empty table fixes the frame the whole layout is read in (see
 *     {@link ProcessingTableBlockEntity#place});</li>
 * <li><b>An empty hand</b> takes the middle item back (see
 *     {@link ProcessingTableBlockEntity#take});</li>
 * <li><b>Sneaking</b> pushes the whole layout one cell, in the direction of the part of the face that was
 *     clicked: click the southern half and the sheet slides south (see {@link #moveDirection}). That is how
 *     room is made in the middle for the next item;</li>
 * <li><b>A wrench</b> works the assembly, which is {@link TableAssembly} — it is reserved here so that a
 *     wrench is never put onto the table as if it were an ingredient.</li>
 * </ul>
 *
 * <p>Two of those are answered here and one is not. A sneaking click never reaches this class — vanilla
 * offers it to the held item rather than to the block — so it is {@link WorkbenchInteractions} that catches
 * it and calls the same push.
 *
 * <p><b>The button on the sides.</b> The four side faces also carry Create's own value-settings button, a
 * {@link TableMoveModeBehaviour}: long-press it with a wrench and a board opens with the table's
 * {@link TableMoveMode}, normal or reversed, which is the answer to "does clicking the far half push the
 * sheet away or towards me". It is on the sides rather than the top so that it is out of the way of the
 * layout, and Create's input handler only takes a click that lands on the button itself — a wrench aimed
 * anywhere else still assembles.
 *
 * <p>An item click is consumed rather than passed: a click that passes is repeated by the client with the
 * other hand, and that retry arrives bare-handed, which here would mean "take something". A bare-handed
 * click is the one thing that must be passed, and for the opposite reason: {@code useWithoutItem}, where the
 * take lives, is only reached when {@code useItemOn} says the click was not about the item at all.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ProcessingTableBlock extends Block implements EntityBlock {

    /** How far off centre a top-face click has to land before it counts as one side or another. */
    private static final double SECTOR_DEADZONE = 0.12D;

    public ProcessingTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProcessingTableBlockEntity(pos, state);
    }

    /**
     * The completion animation is the only thing here that runs on a clock, and it has to run on both sides:
     * the server to end it and clear the table, the client to draw it while it goes.
     *
     * <p>Without this there is no ticking at all — {@link Block} is not an {@link EntityBlock} and answers
     * null by default — and the animation would start and never finish, leaving a spent layout on the table
     * for good. Create's own blocks get this from its {@code IBE} interface; a plain block has to say it.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof ProcessingTableBlockEntity table)
                table.tick();
        };
    }

    /**
     * The layout is the block's contents, so it leaves with the block: broken, replaced, or pushed by a
     * piston, nothing a player put on the table disappears with it.
     *
     * <p>The block entity is still there at this point — vanilla removes a replaced block's block entity
     * after this call, not before — which is what makes the grid readable here.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ProcessingTableBlockEntity table)
            table.ejectAll();
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** The depot's own shape, so the table stands and looks exactly as it did. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return AllShapes.CASING_13PX.get(Direction.UP);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        ProcessingTableBlockEntity table = tableAt(level, pos);
        if (table == null)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // Busy: the layout is spent but still on the table for the animation to draw, so nothing may be done
        // to it. Consumed rather than passed, because a passed click comes back with the other hand.
        if (table.isCrafting()) {
            if (!level.isClientSide)
                TableAssembly.announceCrafting(player);
            return ItemInteractionResult.CONSUME;
        }

        // Sneaking is the move, whatever is in hand: it is a click about the layout rather than about what
        // the player is holding.
        if (player.isShiftKeyDown()) {
            Direction direction = pushDirection(table, hit);
            if (direction == null)
                return ItemInteractionResult.CONSUME;
            if (level.isClientSide)
                return ItemInteractionResult.SUCCESS;
            table.move(direction);
            return ItemInteractionResult.SUCCESS;
        }

        // The wrench drives the assembly and is never an ingredient.
        if (isWrench(stack)) {
            if (level.isClientSide)
                return ItemInteractionResult.SUCCESS;
            TableAssembly.useWrench(table, player, hand);
            return ItemInteractionResult.SUCCESS;
        }

        // An empty hand is not an item interaction at all, and it has to say so: vanilla only reaches
        // useWithoutItem — where the take lives — when this returns PASS_TO_DEFAULT_BLOCK_INTERACTION.
        // Consuming here would swallow every bare-handed click and nothing would ever come back off the
        // table.
        if (stack.isEmpty())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;

        if (!table.place(stack, player.getDirection()))
            return ItemInteractionResult.CONSUME;
        if (!player.isCreative())
            stack.shrink(1);
        return ItemInteractionResult.SUCCESS;
    }

    /** An empty-handed click takes the middle item back. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        ProcessingTableBlockEntity table = tableAt(level, pos);
        if (table == null)
            return InteractionResult.PASS;

        if (table.isCrafting()) {
            if (!level.isClientSide)
                TableAssembly.announceCrafting(player);
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            Direction direction = pushDirection(table, hit);
            if (direction == null || level.isClientSide)
                return InteractionResult.SUCCESS;
            table.move(direction);
            return InteractionResult.SUCCESS;
        }

        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        ItemStack taken = table.take();
        if (!taken.isEmpty())
            player.getInventory()
                .placeItemBackInInventory(taken);
        return InteractionResult.SUCCESS;
    }

    /**
     * Which way a sneaking click asked the layout to move, from where on the block it was clicked.
     *
     * <p>A click on the top face is read by the offset of the hit point from the middle of the face: the
     * larger of the two horizontal offsets decides, so clicking the southern half of the top asks for a
     * move south. A click on one of the four side faces is that face's direction, which is the same thing
     * said from the side. A click on the bottom face has no direction and is refused.
     *
     * <p>This is the raw reading, before the table's movement mode has had its say: see
     * {@link #pushDirection}.
     */
    @Nullable
    static Direction moveDirection(BlockHitResult hit) {
        Direction face = hit.getDirection();
        if (face.getAxis() != Direction.Axis.Y)
            return face;
        if (face == Direction.DOWN)
            return null;

        double dx = hit.getLocation().x - hit.getBlockPos()
            .getX() - 0.5D;
        double dz = hit.getLocation().z - hit.getBlockPos()
            .getZ() - 0.5D;
        // The middle of the face is nobody's side; a click that lands there does nothing rather than
        // guessing, which is what keeps a player from shuffling the layout by accident.
        if (Math.abs(dx) < SECTOR_DEADZONE && Math.abs(dz) < SECTOR_DEADZONE)
            return null;
        if (Math.abs(dx) > Math.abs(dz))
            return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Whether this is Create's wrench, by id.
     *
     * <p>By id because Create's wrench is its own block item reached through Registrate, whose types this mod
     * does not have; the same reasoning {@link FanCurrent#isNozzle} gives for the nozzle.
     */
    public static boolean isWrench(ItemStack stack) {
        return !stack.isEmpty() && ResourceLocation.fromNamespaceAndPath("create", "wrench")
            .equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * Which way a sneaking click pushes the layout, once the table's own mode has had its say.
     *
     * <p>The one place the mode is applied, so the click that moves the layout, the click that is refused for
     * want of a direction and the line under the crosshair that names the direction can never disagree about
     * which way the sheet is about to go.
     *
     * @return the world direction to push in, or null when the click asked for no direction at all
     */
    @Nullable
    public static Direction pushDirection(ProcessingTableBlockEntity table, BlockHitResult hit) {
        Direction clicked = moveDirection(hit);
        if (clicked == null)
            return null;
        return table.moveMode() == TableMoveMode.REVERSED ? clicked.getOpposite() : clicked;
    }

    /** This table's block entity at a position, or null when the block there is not one of ours. */
    @Nullable
    private static ProcessingTableBlockEntity tableAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ProcessingTableBlockEntity table ? table : null;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState();
    }
}
