package io.github.Ling.create_ai;

import java.util.List;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.fluids.FluidFX;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.item.SmartInventory;

import net.createmod.catnip.math.VecHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The particles a tool action throws, for depots that cannot raise them themselves.
 *
 * <p>Client only, and it has to stay that way: Create's fluid particle class reaches into
 * client-only classes, so building one on a dedicated server is a hard crash. Every method here
 * returns immediately unless it is running on the client, and every caller checks the side first.
 *
 * <p>Both effects are Create's own, copied from the code that raises them there:
 * {@code SpoutBlockEntity#spawnSplash} for the pour, {@code PressingBehaviour#makePressingParticleEffect}
 * for the press.
 */
public final class ToolVisuals {

    /** Same splash burst Create's spout throws when it finishes filling: twenty particles. */
    public static final int SPLASH_PARTICLE_COUNT = 20;

    /** Same compacting burst Create's press throws over a basin, per input item. */
    private static final int COMPACTING_PARTICLE_COUNT = 20;

    /**
     * Where Create's own {@code DepotRenderer} draws a depot's item: it translates to
     * {@code (.5, 15/16, .5)} inside the block. A depot is a 13-pixel-tall casing, so its top face is
     * at 13/16 — anything spawned lower than that is spawned inside the block, which is what left the
     * crush particles of a hammer press stuck in the depot.
     */
    private static final double ITEM_HEIGHT = 15 / 16d;
    private static final double ITEM_CENTER = .5d;

    private ToolVisuals() {
    }

    /**
     * The point a tool's particles come from: the item's own position on the depot, which is above the
     * depot's top face.
     */
    static Vec3 itemPosition(BlockPos pos) {
        return Vec3.atLowerCornerOf(pos)
            .add(ITEM_CENTER, ITEM_HEIGHT, ITEM_CENTER);
    }

    /** The splash of a finished pour, in the fluid that was poured. */
    public static void splash(Level level, BlockPos pos, FluidStack poured) {
        if (level == null || !level.isClientSide || poured.isEmpty())
            return;
        Vec3 at = itemPosition(pos);
        ParticleOptions particle = FluidFX.getFluidParticle(poured);
        for (int i = 0; i < SPLASH_PARTICLE_COUNT; i++) {
            Vec3 motion = VecHelper.offsetRandomly(Vec3.ZERO, level.random, 0.125f);
            motion = new Vec3(motion.x, Math.abs(motion.y), motion.z);
            level.addAlwaysVisibleParticle(particle, at.x, at.y, at.z, motion.x, motion.y, motion.z);
        }
    }

    /** The crush particles of a finished press, in the item that was pressed. */
    public static void pressParticles(Level level, BlockPos pos, ItemStack pressed, int amount) {
        if (level == null || !level.isClientSide || pressed.isEmpty() || amount <= 0)
            return;
        Vec3 at = itemPosition(pos);
        for (int i = 0; i < amount; i++) {
            Vec3 motion = VecHelper.offsetRandomly(Vec3.ZERO, level.random, .125f)
                .multiply(1, 0, 1);
            motion = motion.add(0, amount != 1 ? 0.125f : 1 / 16f, 0);
            level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, pressed), at.x, at.y, at.z, motion.x,
                motion.y, motion.z);
        }
    }

    /**
     * The particles Create's press throws over a basin: twenty per item it is compressing, rising from
     * the basin's center. Mirrors {@code PressingBehaviour#makeCompactingParticleEffect}.
     */
    public static void basinPressParticles(Level level, BlockPos basinPos, List<ItemStack> inputs) {
        if (level == null || !level.isClientSide || inputs.isEmpty())
            return;
        Vec3 at = Vec3.atCenterOf(basinPos);
        for (ItemStack pressed : inputs) {
            if (pressed.isEmpty())
                continue;
            for (int i = 0; i < COMPACTING_PARTICLE_COUNT; i++) {
                Vec3 motion = VecHelper.offsetRandomly(Vec3.ZERO, level.random, .175f)
                    .multiply(1, 0, 1);
                level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, pressed), at.x, at.y, at.z, motion.x,
                    motion.y + .25f, motion.z);
            }
        }
    }

    /**
     * The particles Create's mechanical saw throws while it cuts: one per tick, thrown the way the
     * blade's own stroke throws them. Mirrors {@code SawBlockEntity#spawnParticles}.

     * <p>Two things are the saw's, and deliberately so. Which particle it is depends on the item: a block
     * item throws that block's own fragments, which is what makes cutting a log look like cutting wood,
     * and anything else throws the item's sprite. And how fast it flies depends on the same choice — a
     * block's fragments carry at full speed, an item's sprite at an eighth of it.
     *
     * <p>What is not the saw's is the direction. A saw cuts along its shaft's axis and leaves the
     * direction from {@code SawBlockEntity#getItemMovementVec}; a tool has no axis, so the fragments go
     * down the player's look vector, from the item being cut and away from the eye that is aiming at it.
     * The direction is the sign a saw gets from {@code getItemMovementVec} and nothing else is touched:
     * {@code SawBlockEntity#spawnParticles} spawns at {@code pos - vec * offset} and launches with
     * {@code -vec}, both functions of the same vector, so the two signs flip together and the fragments
     * still come out of the near face and travel the way the blade runs — here, away from the player.
     * Everything else about the emission is the saw's own arithmetic: the offset read off how far through
     * the stroke it is, the speed, and the little scatter of upward velocity.
     *
     * @param direction the direction the player is looking, which is the way the fragments travel
     * @param progress how far through the stroke, 0 to 1, which is the saw's
     *                 {@code remainingTime / recipeDuration} and moves the emission point along the cut
     */
    public static void sawParticles(Level level, BlockPos pos, Vec3 direction, ItemStack cut, double progress) {
        if (level == null || !level.isClientSide || cut.isEmpty())
            return;

        ParticleOptions particle;
        float speed = 1;
        if (cut.getItem() instanceof BlockItem blockItem) {
            particle = new BlockParticleOption(ParticleTypes.BLOCK, blockItem.getBlock()
                .defaultBlockState());
        } else {
            particle = new ItemParticleOption(ParticleTypes.ITEM, cut);
            speed = .125f;
        }

        // The saw's own emission point: the center of the block and .45 above it, which is where the blade
        // meets the item it is cutting. The offset is the saw's too — half the progress, taken back along
        // the direction of travel, which is the near face of the item.
        double offset = progress / 2;
        Vec3 at = VecHelper.getCenterOf(pos)
            .subtract(direction.scale(offset))
            .add(0, .45f, 0);
        level.addParticle(particle, at.x, at.y, at.z, direction.x * speed, level.random.nextFloat() * speed,
            direction.z * speed);
    }

    /**
     * The sound a finished stroke makes: Create's own saw activation, wood or stone, chosen exactly the way
     * {@code SawBlockEntity#tickAudio} chooses it (its lines 152 to 163) — a block item whose own sound type
     * is wood gets the wood one, everything else the stone one — at the volume and pitch Create uses.
     *
     * <p>Played through {@code SoundEntry#playOnServer}, which broadcasts it to everyone nearby. That is the
     * point: the stroke is decided on the server, and the version before this one asked the entry for a
     * <em>client-side</em> play ({@code playAt}) from here, where it is a no-op — which is why a finished
     * stroke used to be silent no matter which of the two sounds was asked for.
     */
    public static void sawCutSound(Level level, BlockPos pos, ItemStack cut) {
        boolean isWood = false;
        if (cut.getItem() instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock()
                .defaultBlockState();
            isWood = blockItem.getBlock()
                .getSoundType(state, level, pos, null) == SoundType.WOOD;
        }
        if (isWood)
            AllSoundEvents.SAW_ACTIVATE_WOOD.playOnServer(level, pos, 3, 1);
        else
            AllSoundEvents.SAW_ACTIVATE_STONE.playOnServer(level, pos, 3, 1);
    }

    /**
     * What Create's mixer spills while it stirs: one particle per item sitting in the basin and one per
     * fluid in its tanks, thrown outwards from the basin's rim. Mirrors
     * {@code MechanicalMixerBlockEntity#renderParticles} and its {@code #spillParticle}.
     */
    public static void mixParticles(Level level, BasinBlockEntity basin) {
        if (level == null || !level.isClientSide || basin == null)
            return;

        for (SmartInventory inventory : basin.getInvs())
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack in = inventory.getItem(slot);
                if (!in.isEmpty())
                    spillFromBasin(level, basin.getBlockPos(), new ItemParticleOption(ParticleTypes.ITEM, in));
            }

        for (SmartFluidTankBehaviour behavior : basin.getTanks())
            for (SmartFluidTankBehaviour.TankSegment segment : behavior.getTanks()) {
                // isEmpty, not "is the rendered fluid non-empty": a tank segment keeps its last
                // rendered fluid forever, so an emptied tank would go on throwing particles of a fluid
                // that is no longer there. Create's mixer checks the same way.
                if (tankHasFluid(segment))
                    spillFromBasin(level, basin.getBlockPos(), FluidFX.getFluidParticle(segment.getRenderedFluid()));
            }
    }

    /** Whether a tank segment has anything left to throw: {@code TankSegment#isEmpty} with no lerp. */
    static boolean tankHasFluid(SmartFluidTankBehaviour.TankSegment segment) {
        return !segment.isEmpty(0);
    }

    /**
     * {@code MechanicalMixerBlockEntity#spillParticle}, anchored on the basin instead of on a mixer:
     * a point on the rim, thrown outwards and upwards with a little scatter. The mixer stands two
     * blocks above its basin, so its own spawn height of {@code center.y - 1.75} measures from two
     * blocks up — from the basin itself the same height is {@code center.y + 0.25}.
     */
    private static void spillFromBasin(Level level, BlockPos basinPos, ParticleOptions data) {
        float angle = level.random.nextFloat() * 360;
        Vec3 offset = VecHelper.rotate(new Vec3(0, 0, 0.25f), angle, Direction.Axis.Y);
        Vec3 target = VecHelper.rotate(offset, 25, Direction.Axis.Y)
            .add(0, .25f, 0);
        Vec3 center = offset.add(VecHelper.getCenterOf(basinPos));
        target = VecHelper.offsetRandomly(target.subtract(offset), level.random, 1 / 128f);
        level.addParticle(data, center.x, center.y + .25f, center.z, target.x, target.y, target.z);
    }
}
