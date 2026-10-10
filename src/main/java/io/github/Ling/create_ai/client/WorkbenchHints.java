package io.github.Ling.create_ai.client;

import io.github.Ling.create_ai.block.ProcessingTableBlock;
import io.github.Ling.create_ai.block.ProcessingTableBlockEntity;
import io.github.Ling.create_ai.CreateAI;
import io.github.Ling.create_ai.table.WorkbenchInteractions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The line under the crosshair that says which way the next click will push the layout.
 *
 * <p>A sneaking click on the workbench pushes the whole layout one cell the way the clicked part of the face
 * points, and which part of the face is aimed at is not something a player can see — the top face is sixteen
 * pixels of flat casing and a side face is a direction nobody thinks about while looking at a table. So while
 * a player is sneaking and looking at a table that has something on it, the direction is named: up, down,
 * left or right <em>of the recipe</em>, which is the one set of terms that means anything here, because the
 * recipe is the sheet lying in front of them.
 *
 * <p>The frame the names are read against is the table's own — the facing that was fixed when the first item
 * was placed — so "up" is the recipe's top row whatever side of the table the player has walked round to. On
 * an empty table there is no frame yet and the player's own facing is used, which is the frame the next item
 * placed would fix anyway.
 *
 * <p>Only while sneaking: without the sneak the click is a placement or a take, nothing moves, and a hint
 * about moving would be answering a question the player did not ask.
 *
 * <p>Client-only, and read-only: it looks at what the player is aiming at and writes a line on the screen.
 * The move itself happens on the server (see {@link WorkbenchInteractions}).
 */
@EventBusSubscriber(modid = CreateAI.MOD_ID, value = Dist.CLIENT)
public final class WorkbenchHints {

    private WorkbenchHints() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !player.isShiftKeyDown())
            return;
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return;
        if (!(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof ProcessingTableBlockEntity table))
            return;
        // Nothing on the table is nothing to push: the hint would be about a click that does nothing.
        if (table.isEmpty())
            return;

        Direction direction = ProcessingTableBlock.pushDirection(table, hit);
        if (direction == null)
            return;

        Direction forward = table.forward() != null ? table.forward() : player.getDirection();
        Direction right = table.right() != null ? table.right() : forward.getClockWise();
        minecraft.gui.setOverlayMessage(
            Component.translatable("create_ai.processing_table.move_hint", name(direction, forward, right)), false);
    }

    /**
     * Which way a clicked world direction pushes the layout, named in the recipe's own terms.
     *
     * <p>Forward and right are the frame's two horizontal axes, so every horizontal direction is one of the
     * four: the way the player faced when they started is "up", their right hand then is "right".
     */
    private static Component name(Direction direction, Direction forward, Direction right) {
        String term;
        if (direction == forward)
            term = "up";
        else if (direction == forward.getOpposite())
            term = "down";
        else if (direction == right)
            term = "right";
        else
            term = "left";
        return Component.translatable("create_ai.processing_table.move." + term);
    }
}
