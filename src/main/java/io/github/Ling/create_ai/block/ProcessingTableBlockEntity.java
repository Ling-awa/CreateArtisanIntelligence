package io.github.Ling.create_ai.block;

import io.github.Ling.create_ai.CreateAI;
import io.github.Ling.create_ai.table.TableCrafting;
import io.github.Ling.create_ai.table.TableMoveMode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The artisan's workbench: a single block that holds a crafting layout laid out like a mechanical crafter's
 * grid, assembled by hand.
 *
 * <p><b>What it is not.</b> This is no longer a depot in any sense. It used to extend Create's
 * {@code DepotBlockEntity} and inherit its slot, its eight output slots, its item-handler capability and
 * every tool action written against a depot; all of that is gone, and with it the tools' ability to target
 * this block at all — a hammer, a saw, a spout gun or a pair of goggles looking for a depot no longer finds
 * anything here, because this is not one. What is kept is the look: the block state still points at
 * Create's depot model and the block still has the depot's flat casing shape (see
 * {@link ProcessingTableBlock}), so it reads as the same table it always was.
 *
 * <p><b>The layout.</b> Placed items live in a sparse grid of frame coordinates, the shape Create's own
 * crafter calls a {@code GroupedItems}: a map of (x, y) to a stack, with no requirement that the layout be
 * rectangular. The frame — which way is "up" in recipe terms — is fixed by the first item placed on an empty
 * table: from then on, the direction the player was facing is the recipe's first row and their right is its
 * first column. Everything the player does afterwards is expressed in that frame, so a layout is built the
 * way a recipe is read, from whichever side of the table the player happens to be standing. Take everything
 * off and the frame goes with the last item, so a player who walks round to another side starts a fresh
 * layout read the way they are facing then.
 *
 * <p><b>Placing and pushing.</b> An item is always put down in the middle of the table, and the layout is
 * pushed along to make room for the next one: a sneak-click pushes the whole sheet one cell the way the
 * clicked part of the face points (see {@link #move}). So a sheet is built by placing, pushing, placing —
 * no aiming at cells, and no invisible cursor to find. The data is the sheet, not a view of it: what moves is
 * every placed cell, together, which is why the limit is a limit on the <em>layout</em>:
 * {@link #GRID_LIMIT} cells from the middle in each direction, which is a nine by nine sheet.
 *
 * <p><b>The assembly.</b> A wrench installs the matching recipe one ingredient per click
 * ({@link #install()}), and a completed assembly spends the layout and shows what it made
 * ({@link #beginCraft}). Anything the player does other than pushing the layout puts the count back to zero,
 * because a layout that has been touched is a layout that is no longer the one being assembled.
 */
public class ProcessingTableBlockEntity extends SmartBlockEntity {

    /**
     * How far the layout may be pushed from the middle of the table: four cells each way, so a nine by nine
     * sheet.
     *
     * <p>Nine by nine is the grid a mechanical crafter's recipes are written for, and the cells are two
     * pixels apart with two-pixel items — the sheet therefore reads at a glance and items in neighbouring
     * cells sit edge to edge. Nine of those is eighteen pixels, so the outermost cells of a full sheet hang a
     * pixel over the edge of the block; that is the price of nine cells at a legible size, and it was chosen
     * knowingly.
     */
    public static final int GRID_LIMIT = 4;

    /** How long the completion animation runs: the layout converging on the middle and the product arriving. */
    public static final int CRAFT_ANIMATION_TICKS = 20;

    /** The placed items, keyed by frame coordinates — the shape Create's own crafter works in. */
    private final Map<Pair<Integer, Integer>, ItemStack> cells = new HashMap<>();

    /**
     * The frame, fixed by the first item placed: {@code forward} is the recipe's "up" and {@code right} its
     * first column. Null while the table is empty, which is the one state in which the next placement is
     * free to choose them.
     */
    @Nullable
    private Direction forward;
    @Nullable
    private Direction right;

    /** How many ingredients of the matching recipe the wrench has installed, or zero with none started. */
    private int installed;

    /**
     * The game time of the wrench click that refused this layout, or zero when none has.
     *
     * <p>When rather than whether, because the refusal has to last a second: vanilla repeats a held use key
     * every four ticks, and "the next click empties the table" would otherwise mean "the next four ticks
     * empty the table". It is not saved: a refusal is about a click that is happening now.
     */
    private long warnedAt;

    /** Ticks left of the completion animation, or zero when none is running. */
    private int craftTicks;

    /** What the animation is showing, at the middle of the table. */
    private ItemStack shownResult = ItemStack.EMPTY;

    public ProcessingTableBlockEntity(BlockPos pos, BlockState state) {
        super(CreateAI.PROCESSING_TABLE_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // The one behaviour this table has, and it is not about items: it is the movement-mode button on the
        // four sides, which decides which way a sneaking click pushes the layout (see TableMoveModeBehaviour).
        // Everything else about this table is a player's click.
        behaviours.add(new TableMoveModeBehaviour(this));
    }

    /**
     * Which way a sneaking click pushes the layout, as the player set it on the side button.
     *
     * <p>Asked for rather than cached: the behaviour is the one that persists and syncs the value, so a copy
     * kept here would be a second source of truth to keep in step. Before the behaviours exist - a block
     * entity that has not been read or ticked yet - the answer is the default.
     */
    public TableMoveMode moveMode() {
        BlockEntityBehaviour behaviour = getBehaviour(ScrollValueBehaviour.TYPE);
        return behaviour instanceof TableMoveModeBehaviour mode ? mode.get() : TableMoveMode.NORMAL;
    }

    // --- reading the layout ---------------------------------------------------------------------

    /** The placed items, by frame coordinate. Read-only to callers; the block entity owns the map. */
    public Map<Pair<Integer, Integer>, ItemStack> cells() {
        return cells;
    }

    /** Whether the table holds nothing at all. */
    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** The frame's "up", or null while nothing has been placed. */
    @Nullable
    public Direction forward() {
        return forward;
    }

    /** The frame's "right", or null while nothing has been placed. */
    @Nullable
    public Direction right() {
        return right;
    }

    /** How many ingredients of the matching recipe have been installed. */
    public int installed() {
        return installed;
    }

    /** Whether a wrench click has already refused this layout once. */
    public boolean warned() {
        return warnedAt != 0L;
    }

    /** When the refusing click happened, or zero when none has. */
    public long warnedAt() {
        return warnedAt;
    }

    /** Ticks left of the completion animation, zero when none is running. */
    public int craftTicks() {
        return craftTicks;
    }

    /**
     * Whether the table is in the middle of showing what it made, which is when it is busy.
     *
     * <p>The layout is deliberately still on the table while the animation draws it converging, so it still
     * matches its recipe. Every click is therefore refused for those ticks: a wrench click in that window
     * would assemble the same layout a second time and hand out a second product for it, and a placement would
     * be swept away when the animation ends.
     */
    public boolean isCrafting() {
        return craftTicks > 0;
    }

    /** What the completion animation is showing at the middle of the table, empty when nothing is. */
    public ItemStack shownResult() {
        return shownResult;
    }

    // --- the frame ------------------------------------------------------------------------------

    /**
     * Fixes the frame from the way the player is facing, if the table is still empty and has no frame yet.
     *
     * <p>Called before a placement. The recipe's first row is the direction the player faces and its first
     * column is on their right, which is how a page lying on the table in front of them reads.
     */
    private void fixFrame(Direction facing) {
        if (forward != null || right != null)
            return;
        forward = facing;
        right = facing.getClockWise();
    }

    /** How far a world direction goes along the frame's rightward axis, as -1, 0 or 1. */
    private int frameX(Direction direction) {
        if (right == null || direction.getAxis() != right.getAxis())
            return 0;
        return direction.getAxisDirection() == right.getAxisDirection() ? 1 : -1;
    }

    /** How far a world direction goes along the frame's forward axis, as -1, 0 or 1. */
    private int frameY(Direction direction) {
        if (forward == null || direction.getAxis() != forward.getAxis())
            return 0;
        return direction.getAxisDirection() == forward.getAxisDirection() ? 1 : -1;
    }

    // --- what a player does ---------------------------------------------------------------------

    /**
     * Puts one item from the player's hand into the middle of the table.
     *
     * <p>The middle is where every placement lands: an item is put down, the layout is pushed along to make
     * room for the next one (see {@link #move}), and the cell in the middle is free again. That is what makes
     * the sheet grow away from the player rather than the player having to aim at a cell.
     *
     * <p>The first item placed fixes the frame, so the way the player is facing when they start is what every
     * later placement is read against — and it stays fixed only while something is on the table: take
     * everything off and the next item placed fixes it again, which is what lets a player walk round to
     * another side and lay the same recipe out from there.
     *
     * <p>Placing anything is also one of the operations that puts the assembly back to nothing: the layout
     * being assembled is no longer the layout that was there.
     *
     * @return whether the item went in: false while the middle is taken, which is a click that does nothing
     */
    public boolean place(ItemStack held, Direction facing) {
        if (held.isEmpty())
            return false;
        Pair<Integer, Integer> cell = Pair.of(0, 0);
        if (cells.containsKey(cell))
            return false;

        fixFrame(facing);
        cells.put(cell, held.copyWithCount(1));
        resetAssembly();
        changed();
        return true;
    }

    /**
     * Takes the item in the middle of the table back out, for an empty-handed click.
     *
     * <p>Taking the last item off also lets go of the frame: an empty table has no orientation, and the next
     * item placed is free to set one from wherever the player is standing then.
     *
     * @return the stack taken, empty when the middle cell is empty
     */
    public ItemStack take() {
        ItemStack taken = cells.remove(Pair.of(0, 0));
        if (taken == null || taken.isEmpty())
            return ItemStack.EMPTY;
        if (cells.isEmpty()) {
            forward = null;
            right = null;
        }
        resetAssembly();
        changed();
        return taken;
    }

    /**
     * Pushes the whole layout one cell the way the player asked for, refusing to leave the sheet.
     *
     * <p>The layout moves and the table does not: the direction is a world direction — the half of the top
     * face that was clicked, already turned the way the table's movement mode says (see
     * {@link ProcessingTableBlock#pushDirection}) — so it is written in the frame's coordinates first, and
     * every placed cell is moved along by that much. A player places an item, pushes the layout aside, and
     * places the next one in the middle again, which is how a sheet gets built without aiming at individual
     * cells.
     *
     * <p>A move is the one operation that leaves the assembly alone: pushing the layout along is not changing
     * it. It does refresh the wrench's refusal, which is about the layout as it stands.
     *
     * @return whether the layout moved: false on an empty table and false at the edge of the sheet, both of
     *         which are clicks that do nothing
     */
    public boolean move(Direction direction) {
        if (cells.isEmpty())
            return false;

        int stepX = frameX(direction);
        int stepY = frameY(direction);
        if (stepX == 0 && stepY == 0)
            return false;

        // Measured first, moved second: a move that would push any single cell off the sheet is refused
        // whole, so the layout can never be torn apart by a click that only half fits.
        for (Pair<Integer, Integer> cell : cells.keySet()) {
            if (Math.abs(cell.getLeft() + stepX) > GRID_LIMIT || Math.abs(cell.getRight() + stepY) > GRID_LIMIT)
                return false;
        }

        Map<Pair<Integer, Integer>, ItemStack> moved = new HashMap<>();
        cells.forEach((cell, stack) -> moved.put(Pair.of(cell.getLeft() + stepX, cell.getRight() + stepY), stack));
        cells.clear();
        cells.putAll(moved);

        warnedAt = 0L;
        changed();
        return true;
    }

    /** One ingredient installed by the wrench. */
    public void install() {
        installed++;
        changed();
    }

    /** Remembers that a wrench click refused this layout, and when, so the next one may empty the table. */
    public void warn(long gameTime) {
        warnedAt = gameTime;
        changed();
    }

    /**
     * Starts the completion animation on a layout that has just been spent.
     *
     * <p>The layout is <em>not</em> cleared here: the animation is the layout converging on the middle, so it
     * needs something to converge. What is cleared is the assembly count, and everything else goes when the
     * animation ends (see {@link #tick()}).
     */
    public void beginCraft(ItemStack result) {
        resetAssembly();
        shownResult = result.copy();
        craftTicks = CRAFT_ANIMATION_TICKS;
        changed();
    }

    /** Ejects everything on the table, which is what a breaking block and a fed-up wrench both do. */
    public void ejectAll() {
        if (level == null)
            return;
        for (ItemStack stack : cells.values()) {
            if (stack.isEmpty())
                continue;
            Vec3 at = Vec3.atCenterOf(worldPosition)
                .add(0, 0.5, 0);
            ItemEntity entity = new ItemEntity(level, at.x, at.y, at.z, stack.copy());
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        cells.clear();
        forward = null;
        right = null;
        resetAssembly();
        changed();
    }

    /** Back to nothing assembled and nothing refused: what every operation other than a move does. */
    private void resetAssembly() {
        installed = 0;
        warnedAt = 0L;
    }

    /**
     * Marks the table's contents as changed and pushes them to the clients that draw it.
     *
     * <p>Every mutation goes through here, so the renderer is never a tick behind: the grid is in the
     * synced tag as well as in the saved one (see {@link #write}).
     */
    private void changed() {
        notifyUpdate();
        sendData();
    }

    // --- ticking ---------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (craftTicks <= 0)
            return;
        craftTicks--;
        if (craftTicks > 0)
            return;

        // The animation has run: the layout is spent and the table is empty and ready for the next job.
        cells.clear();
        forward = null;
        right = null;
        shownResult = ItemStack.EMPTY;
        resetAssembly();
        if (level != null && !level.isClientSide)
            sendData();
    }

    // --- saving and syncing ----------------------------------------------------------------------

    /**
     * The layout as the tag Create's own grid reader expects: a {@code Grid} list of
     * {@code x} / {@code y} / {@code item} entries.
     *
     * <p>Both the saved form and the recipe lookup go through this one method, so what is written to disk and
     * what is matched against a recipe cannot drift apart. {@link TableCrafting} explains why the grid goes
     * out as a tag rather than as the map itself.
     */
    public CompoundTag gridTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag grid = new ListTag();
        cells.forEach((cell, stack) -> {
            if (stack.isEmpty())
                return;
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", cell.getLeft());
            entry.putInt("y", cell.getRight());
            entry.put("item", stack.saveOptional(registries));
            grid.add(entry);
        });
        tag.put("Grid", grid);
        return tag;
    }

    /**
     * Writes the layout out, both to disk and to the client.
     *
     * <p>The client copy is not a courtesy: this table is drawn from its grid, and a client that had to
     * guess would draw the previous frame's layout.
     */
    @Override
    public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        if (forward != null)
            compound.putInt("Forward", forward.get3DDataValue());
        if (right != null)
            compound.putInt("Right", right.get3DDataValue());
        compound.putInt("Installed", installed);
        compound.putInt("CraftTicks", craftTicks);
        compound.put("ShownResult", shownResult.saveOptional(registries));
        compound.put("Grid", gridTag(registries).getList("Grid", Tag.TAG_COMPOUND));
    }

    @Override
    public void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        forward = compound.contains("Forward") ? Direction.from3DDataValue(compound.getInt("Forward")) : null;
        right = compound.contains("Right") ? Direction.from3DDataValue(compound.getInt("Right")) : null;
        installed = compound.getInt("Installed");
        craftTicks = compound.getInt("CraftTicks");
        shownResult = ItemStack.parseOptional(registries, compound.getCompound("ShownResult"));

        cells.clear();
        ListTag grid = compound.getList("Grid", Tag.TAG_COMPOUND);
        for (int i = 0; i < grid.size(); i++) {
            CompoundTag entry = grid.getCompound(i);
            ItemStack stack = ItemStack.parseOptional(registries, entry.getCompound("item"));
            if (!stack.isEmpty())
                cells.put(Pair.of(entry.getInt("x"), entry.getInt("y")), stack);
        }
    }
}
