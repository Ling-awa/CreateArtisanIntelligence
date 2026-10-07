package io.github.Ling.create_ai.table;

import io.github.Ling.create_ai.block.ProcessingTableBlockEntity;
import io.github.Ling.create_ai.Create_ai;

import com.simibubi.create.AllSoundEvents;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The wrench's half of the workbench: installing a layout one ingredient at a time, refusing a layout that
 * matches nothing, and throwing a hopeless layout out.
 *
 * <p>A wrench click is one of three things, and which one depends on what the table holds:
 *
 * <ul>
 * <li><b>A layout that matches</b> — one ingredient is installed, and when the last one goes in the recipe
 *     is applied, the product is handed over and the table shows the crafter's own completion animation.
 *     There is no cooldown between clicks: an assembly is meant to be rattled through.</li>
 * <li><b>A layout that matches nothing</b>, first click — the player is told so, in the line under the
 *     crosshair, and the wrench is put on a second of cooldown. That second is the whole of the guard, and it
 *     is enforced here as well as shown: an emptying click has to come a second after the refusal, so it is a
 *     click the player decided on rather than the next repeat of a held use key.</li>
 * <li><b>A layout that matches nothing</b>, next click — everything on the table is thrown out. This is the
 *     only way to clear a table without taking things off one at a time, and it is deliberately behind that
 *     warning rather than on the first click.</li>
 * </ul>
 *
 * <p>Any other thing the player does to the table — placing, taking, or pushing the layout along — refreshes
 * that
 * guard, so the warning is always about the layout as it stands now rather than about one that has since
 * been touched.
 */
public final class TableAssembly {

    /**
     * How long a refusal stands: the wrench's own cooldown, and the earliest the emptying click may come.
     *
     * <p>One second is the whole of the guard, and it is counted on the server rather than left to the item
     * cooldown: vanilla does not put a block's right-click on the held item's cooldown, so a player holding
     * the use key — which repeats every four ticks — would otherwise watch their layout emptied a fifth of a
     * second after being told it was not a recipe.
     */
    private static final int WARN_TICKS = 20;

    private TableAssembly() {
    }

    /** One wrench click. Server-side only; the caller has already decided the click belongs to the table. */
    public static void useWrench(ProcessingTableBlockEntity table, Player player, InteractionHand hand) {
        ItemStack wrench = player.getItemInHand(hand);

        // Nothing to assemble while the last one is still being shown: the layout is spent, but it is still
        // on the table so the animation has something to draw, and a click here would make it a second time.
        if (table.isCrafting()) {
            announceCrafting(player);
            return;
        }

        TableCrafting.Match match = TableCrafting.match(table);

        if (match == null) {
            Level level = table.getLevel();
            long now = level == null ? 0L : level.getGameTime();
            if (!table.warned()) {
                // First click on a layout that is not a recipe: say so, and take the wrench out of the
                // player's hands for a second.
                table.warn(now);
                player.displayClientMessage(Component.translatable("create_ai.processing_table.no_recipe"), true);
                player.getCooldowns()
                    .addCooldown(wrench.getItem(), WARN_TICKS);
                return;
            }
            // The emptying click is the one that comes after the warning has had its second. That second is
            // the whole of the guard, and it has to be kept here rather than trusted to the item cooldown:
            // vanilla does not put a block's own right-click on the held item's cooldown, so a held use key
            // — which repeats every four ticks — would otherwise empty the table right behind the warning.
            if (now - table.warnedAt() < WARN_TICKS)
                return;
            table.ejectAll();
            return;
        }

        table.install();
        if (table.installed() < match.ingredientCount()) {
            // One click, one clack, and how far along the assembly is in the line under the crosshair:
            // without it a player counting their own wrench clicks is the only progress bar there is.
            installFeedback(table);
            player.displayClientMessage(Component.translatable("create_ai.processing_table.assembling",
                table.installed(), match.ingredientCount()), true);
            return;
        }

        // The last ingredient is in: the layout is spent, the product is the player's, and the table shows
        // what it made. The last click gets the craft itself rather than another clack, and the feedback comes
        // while the sheet is still there to burst into particles.
        craftFeedback(table);
        ItemStack product = match.result();
        table.beginCraft(product);
        if (!product.isEmpty() && !player.getInventory()
            .add(product))
            player.drop(product, false);
    }

    /** Tells a player the table is in the middle of a craft, which is why their click did nothing. */
    public static void announceCrafting(Player player) {
        player.displayClientMessage(Component.translatable("create_ai.processing_table.crafting"), true);
    }

    // --- what a click looks and sounds like ------------------------------------------------------

    /**
     * The click and the puff a mechanical crafter makes when an item lands in it.
     *
     * <p>Both are Create's own, taken from the crafter's own code rather than invented: the click is
     * {@code AllSoundEvents.CRAFTER_CLICK} at the pitch the crafter uses for a grid it has just taken an item
     * into — rising with how much is already in it — and the puff is its crafting {@code CRIT} particle, here
     * at the middle of the table where the item went.
     */
    private static void installFeedback(ProcessingTableBlockEntity table) {
        if (!(table.getLevel() instanceof ServerLevel level))
            return;
        AllSoundEvents.CRAFTER_CLICK.playOnServer(level, table.getBlockPos(), 1f,
            table.installed() * 1f / 16f + .5f);
        Vec3 at = faceOf(table);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 4, .05, .02, .05, .02);
    }

    /**
     * What the crafter does at the moment a grid finishes: its craft sound, and the burst of item particles it
     * throws off as the ingredients are consumed — one burst per cell of the sheet, each made of the item that
     * was in it.
     *
     * <p>The crafter plays its click once more here, an octave up; this does not, because the click already
     * belongs to the clicks before it. The last click is the craft, and one sound at a time is what a player
     * hears as one action.
     */
    private static void craftFeedback(ProcessingTableBlockEntity table) {
        if (!(table.getLevel() instanceof ServerLevel level))
            return;
        AllSoundEvents.CRAFTER_CRAFT.playOnServer(level, table.getBlockPos());
        Vec3 at = faceOf(table);
        for (ItemStack stack : table.cells()
            .values()) {
            if (stack.isEmpty())
                continue;
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack), at.x, at.y, at.z, 10, .06, .03, .06,
                .08);
        }
    }

    /** The middle of the table's face, a hair above it: where the layout is laid down and the product lands. */
    private static Vec3 faceOf(ProcessingTableBlockEntity table) {
        return Vec3.atCenterOf(table.getBlockPos())
            .add(0, 13 / 16d + 0.05d, 0);
    }
}
