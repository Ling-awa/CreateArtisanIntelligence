package io.github.Ling.create_ai;

import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The brass powered saw: Create's mechanical saw, with a mode selector and two knobs turned.
 *
 * <p>The block itself is Create's, at the code level rather than copied. Extending {@link SawBlock}
 * means the collision and outline shape, the {@code facing} / {@code axis_along_first_coordinate} /
 * {@code flipped} states, the wrench behaviour, the damage dealt to entities touching a spinning
 * blade, the item pickup when a dropped item lands on the saw, the hand interaction that empties the
 * processing inventory, path-finding, the placement helper and the rotation axis are all inherited
 * unchanged — and its blockstate points straight at {@code create:block/mechanical_saw/*}, so model
 * and textures are literally Create's saw.
 *
 * <p>This class adds exactly one thing: {@link #MODE}, the state the mode selector writes. It exists
 * so the mode survives assembly into a contraption — a contraption captures blockstates, not block
 * entities, so a mode that lived only in the block entity would be lost the moment the saw moved.
 *
 * <p>The behaviour deviations live in {@link BrassMechanicalSawBlockEntity} (processing speed and stress)
 * and {@link BrassMechanicalSawMovementBehaviour} (breaking speed and precision cutting).
 */
public class BrassMechanicalSawBlock extends SawBlock {

    /** Index into {@link BrassMechanicalSawMode}. {@code 0} is the default, fast tree felling. */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, BrassMechanicalSawMode.values().length - 1);

    public BrassMechanicalSawBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(MODE, BrassMechanicalSawMode.FAST_TREE_FELLING.ordinal()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(MODE));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link SawBlockEntity} rather than our own subclass, deliberately. Create's saw machinery — this
     * block's own {@code onBlockEntityUseItemOn} lookups, {@code SawVisual} and the renderer — is written
     * against that type, and our subclass is one. Only the registered type below has to be ours, so that
     * this block gets our block entity rather than Create's.
     */
    @Override
    public Class<SawBlockEntity> getBlockEntityClass() {
        return SawBlockEntity.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Create's damage figure, doubled. A spinning blade hurts whatever touches it by
     * {@code clamp(sub1 + sub2 + sub3, 1, 10)}, built from the speed — so the doubling goes on the figure
     * that feeds the clamp, not on the result. Clamping first and doubling after would be undone by the
     * ceiling of 10 on any saw running fast enough to reach it; this way the doubled value simply reaches
     * that ceiling sooner, and the hardest possible hit stays Create's hardest possible hit.
     *
     * <p>The figure itself is worked out by the block entity, through
     * {@link BrassMechanicalSawBlockEntity#hurtWithBlade} — this block only gets a {@link SawBlockEntity}
     * out of {@code withBlockEntityDo}, so it cannot see past that class to the mode-independent speed.
     *
     * <p>Written out rather than wrapped because Create computes the damage inline inside
     * {@code entityInside} and hands it straight to {@code hurt} — there is no seam to hook. The guards,
     * the bounding-box test and the zero-speed bail-out are Create's, character for character.
     */
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (entity instanceof ItemEntity)
            return;
        if (!new AABB(pos).deflate(.1f)
            .intersects(entity.getBoundingBox()))
            return;
        withBlockEntityDo(level, pos, be -> {
            if (be.getSpeed() == 0)
                return;
            if (be instanceof BrassMechanicalSawBlockEntity brassSaw)
                brassSaw.hurtWithBlade(entity);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Create's saw interaction, with this mod's placement helper in place of Create's. Everything else —
     * the placement-helper test, the spectator and held-item guard, the "only a saw facing up can be
     * emptied by hand" rule, and the block entity interaction that hands the processing inventory back — is
     * Create's, copied because its helper id is baked into the method and there is no seam to swap it.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        IPlacementHelper placementHelper = PlacementHelpers.get(BrassMechanicalSawPlacementHelper.ID);
        if (!player.isShiftKeyDown() && player.mayBuild()) {
            if (placementHelper.matchesItem(stack) && placementHelper.getOffset(player, level, state, pos, hitResult)
                .placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hitResult)
                .consumesAction())
                return ItemInteractionResult.SUCCESS;
        }

        if (player.isSpectator() || !stack.isEmpty())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (state.getOptionalValue(FACING)
            .orElse(Direction.WEST) != Direction.UP)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        return onBlockEntityUseItemOn(level, pos, be -> {
            for (int i = 0; i < be.inventory.getSlots(); i++) {
                ItemStack heldItemStack = be.inventory.getStackInSlot(i);
                if (!level.isClientSide && !heldItemStack.isEmpty())
                    player.getInventory()
                        .placeItemBackInInventory(heldItemStack);
            }
            be.inventory.clear();
            be.notifyUpdate();
            return ItemInteractionResult.SUCCESS;
        });
    }

    @Override
    public BlockEntityType<? extends SawBlockEntity> getBlockEntityType() {
        return Create_ai.BRASS_MECHANICAL_SAW_BE.get();
    }
}
