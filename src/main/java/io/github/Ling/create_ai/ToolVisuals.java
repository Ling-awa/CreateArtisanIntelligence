package io.github.Ling.create_ai;

import java.util.List;

import com.simibubi.create.content.fluids.FluidFX;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.item.SmartInventory;

import net.createmod.catnip.math.VecHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
