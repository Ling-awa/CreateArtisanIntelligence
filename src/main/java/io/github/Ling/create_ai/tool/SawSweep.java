package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.CreateAI;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/**
 * The handheld saw's other use: not a depot in front of it but something alive, cut by holding the key
 * with nothing to work on.
 *
 * <p>What it cuts is what is in the way of the eyes — the same 2 blocks the tool reaches, along the same
 * look vector everything else in this mod aims with, and only what is actually in front: an entity behind
 * the player, or beside them, is not in the sweep. Anything living is fair game except the player
 * themselves and what is theirs, which is what keeps a saw usable around a tamed wolf or a horse being
 * ridden without a config switch for it.
 *
 * <p>Every hit leaves Slowness III behind for a second, refreshed on every sweep while the key is held:
 * one sweep alone barely slows anything, and a player who keeps the key down keeps whatever they are
 * cutting from walking away. The paces — how often, how hard, how far, how long — are all in
 * {@link Config}, because they are balance rather than mechanism.
 *
 * <p><b>A sweep does not knock back.</b> Every hit in this game shoves what it hits, by vanilla's own doing
 * rather than the weapon's, and a saw that shoved would push what it is cutting out of the two blocks it
 * reaches — the tool would spend its hold chasing one mob. So the knockback of a sweep is cancelled: the
 * hit is recorded here just before it lands, and the knockback that follows — inside the same {@code hurt}
 * call — is cancelled by {@link #onKnockBack}, which is the same pair of hooks {@link HammerCombat} uses to
 * make a hammer's hit knock back harder. Only a sweep is treated this way; what the saw does when it is
 * swung as an ordinary weapon is left exactly as vanilla has it.
 *
 * <p>Server-side only, and called from the item's own tick; the caller owns the durability.
 */
@EventBusSubscriber(modid = CreateAI.MOD_ID)
public final class SawSweep {

    /** Slowness III: the amplifier is the level minus one, as vanilla counts these. */
    private static final int SLOWNESS_AMPLIFIER = 2;

    /** How far off the line of sight a target may stand and still be caught, in blocks. */
    private static final double SWEEP_WIDTH = 1.0D;

    /** The entity whose knockback is still to come, or null when nothing is pending. */
    @Nullable
    private static LivingEntity pendingVictim;

    /** The game tick {@link #pendingVictim} was hit on: a hit from any other tick is not this one. */
    private static long pendingTick = Long.MIN_VALUE;

    private SawSweep() {
    }

    /**
     * Sweeps whatever is in front of the player.
     *
     * @return whether anything was caught, which is what the caller charges the saw's durability for
     */
    public static boolean sweep(Level level, Player player) {
        double range = Config.sawMeleeRange();
        Vec3 eyes = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        boolean caught = false;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
            player.getBoundingBox()
                .inflate(range + 1.0D))) {
            if (!isFairGame(player, target))
                continue;
            if (!inFront(eyes, look, range, target))
                continue;
            if (!player.hasLineOfSight(target))
                continue;

            // Recorded before the hit, because the knockback it produces is decided while the hit is being
            // applied and there is nothing in that event to say which tool made it.
            noKnockback(target);
            if (!target.hurt(level.damageSources()
                .playerAttack(player), Config.sawMeleeDamage()))
                continue;

            caught = true;
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                Config.sawMeleeSlownessTicks(), SLOWNESS_AMPLIFIER, false, true));
        }
        return caught;
    }

    /** Remembers that the hit about to land on this entity is a sweep's, and is not to knock it back. */
    private static void noKnockback(LivingEntity victim) {
        pendingVictim = victim;
        pendingTick = victim.level()
            .getGameTime();
    }

    /** The knockback of a hit that was a sweep's, cancelled: only in the tick it happened, and only once. */
    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim != pendingVictim)
            return;
        if (victim.level()
            .getGameTime() != pendingTick)
            return;
        pendingVictim = null;
        event.setCanceled(true);
    }

    /** Anything alive but the player, and not something the player owns or is riding. */
    private static boolean isFairGame(Player player, LivingEntity target) {
        if (target == player || !target.isAlive() || target.isSpectator())
            return false;
        if (target.getVehicle() == player || player.getVehicle() == target)
            return false;
        if (target instanceof OwnableEntity owned && player.getUUID()
            .equals(owned.getOwnerUUID()))
            return false;
        return !(target instanceof Player other) || !other.isCreative();
    }

    /**
     * Whether the target stands within {@code range} blocks of the eyes <em>along the way they are
     * looking</em>, and close enough to that line to be in the blade's path.
     *
     * <p>Measured against the target's middle rather than its feet, so a tall mob is caught by the chest
     * rather than slipping under the sweep.
     */
    private static boolean inFront(Vec3 eyes, Vec3 look, double range, LivingEntity target) {
        Vec3 toTarget = target.position()
            .add(0, target.getBbHeight() * 0.5D, 0)
            .subtract(eyes);
        double along = toTarget.dot(look);
        if (along < 0 || along > range)
            return false;
        return toTarget.subtract(look.scale(along))
            .length() <= SWEEP_WIDTH;
    }
}
