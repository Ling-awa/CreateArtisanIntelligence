package io.github.Ling.create_ai.tool;

import io.github.Ling.create_ai.config.Config;
import io.github.Ling.create_ai.Create_ai;
import io.github.Ling.create_ai.item.HammerItem;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/**
 * What a hammer does to something it hits: the damage and the slow swing are declared on the item as
 * attributes ({@link HammerItem#createAttributes()}), and this is the third part — the knockback, which
 * vanilla gives no item a way to declare.
 *
 * <p>A hit's knockback is not the weapon's. Vanilla computes it in {@code LivingEntity#hurt} — every hit
 * shoves what it hits, whether or not anything was holding it — and Create's {@code LivingKnockBackEvent}
 * fires inside that, with the strength already worked out and a setter for it. So "four and a half times a
 * normal hit" is a multiplication of a number that already exists, and the event is where it can be done.
 *
 * <p>What the event does not carry is the attacker, so the hit is caught on the way in instead:
 * {@link LivingIncomingDamageEvent} fires at the top of the same {@code hurt} call and does name the
 * attacker through its damage source, which is the moment a hammer swing becomes known. The victim and the
 * game tick are remembered, and the knockback that follows — microseconds later, inside the same call —
 * is the one that gets multiplied. A record that was never used expires on its own, because the knockback
 * event only accepts a hit from the same tick.
 *
 * <p>Common, not client-only: the knockback is the server's to apply, and the event fires there.
 */
@EventBusSubscriber(modid = Create_ai.MODID)
public final class HammerCombat {

    /** The entity whose knockback is still to come, or null when the last hit's knockback has been seen. */
    @Nullable
    private static LivingEntity pendingVictim;

    /** The game tick {@link #pendingVictim} was hit on: a hit from any other tick is not this one. */
    private static long pendingTick = Long.MIN_VALUE;

    private HammerCombat() {
    }

    /** Whether this is one of the mod's hammers — the iron one or the obsidian one. */
    private static boolean isHammer(ItemStack stack) {
        return stack.is(Create_ai.HAMMER.get()) || stack.is(Create_ai.OBSIDIAN_HAMMER.get());
    }

    /**
     * A hammer landing on something: remembered, because the knockback that follows is not told who swung.
     *
     * <p>The weapon is read the way vanilla's own attack path reads it, {@code getWeaponItem}, so a hammer
     * in the off hand swinging at nothing is not a hammer hit — nor is a mob's attack, whose source entity
     * is not a player at all.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource()
            .getEntity() instanceof Player player))
            return;
        if (!isHammer(player.getWeaponItem()))
            return;
        pendingVictim = event.getEntity();
        pendingTick = event.getEntity()
            .level()
            .getGameTime();
    }

    /** The knockback of a hit that was a hammer's: multiplied, once, and only in the tick it happened. */
    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim != pendingVictim)
            return;
        if (victim.level()
            .getGameTime() != pendingTick)
            return;
        pendingVictim = null;
        event.setStrength(event.getOriginalStrength() * Config.hammerKnockbackMultiplier());
    }
}
