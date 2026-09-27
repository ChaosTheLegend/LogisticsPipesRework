package logisticspipes.gui.modularUI.pipes.patterncrafting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.network.NetworkUtils;
import com.cleanroommc.modularui.value.sync.SyncHandler;

import logisticspipes.crafting.PatternCraftingHudState;
import logisticspipes.crafting.PatternSatelliteInfo;
import logisticspipes.crafting.PipeFluidPatternSatelliteLogistics;
import logisticspipes.crafting.PipeItemsPatternSatelliteLogistics;
import logisticspipes.crafting.pattern.AbstractPattern;
import logisticspipes.crafting.pattern.ItemPattern;
import logisticspipes.crafting.pattern.PatternRecipeImport;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternItemStack;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics.BlockingMode;
import logisticspipes.utils.AdjacentTile;

/**
 * Carries every non-slot interaction of the pattern crafting pipe GUI.
 * <p>
 * The server pushes the crafting HUD snapshot, the known satellites and the pipe state (blocking mode, crafting
 * target). The client sends pattern edits, NEI imports and pipe commands, which are all applied to the pattern selected
 * in the shared {@link PatternEditorState}.
 */
public class PatternCraftingSyncHandler extends SyncHandler<PatternCraftingSyncHandler> {

    private static final int C_SELECT = 0;
    private static final int C_PATTERN_ACTION = 1;
    private static final int C_CANCEL = 2;
    private static final int C_RETURN_INPUTS = 3;
    private static final int C_BLOCKING_MODE = 4;
    private static final int C_SATELLITE = 5;
    private static final int C_IMPORT = 6;
    private static final int C_REFRESH_SATELLITES = 7;

    private static final int S_HUD = 100;
    private static final int S_SATELLITES = 101;
    private static final int S_STATE = 102;
    private static final int S_SELECT = 103;

    public enum PatternAction {
        CLEAR,
        MULTIPLY,
        TOGGLE_TYPE,
        TOGGLE_ORE_DICT,
        TOGGLE_IGNORE_NBT
    }

    private final PatternEditorState state;
    private final PipeItemsPatternCraftingLogistics pipe;

    private PatternCraftingHudState hudState = PatternCraftingHudState.empty();
    private List<PatternSatelliteInfo> satellites = Collections.emptyList();
    private BlockingMode blockingMode = BlockingMode.OFF;
    private boolean blockingModeFixed;
    private ItemStack targetStack;
    private ForgeDirection targetSide = ForgeDirection.UNKNOWN;
    private int unsupportedPatterns;
    private int satelliteRevision;

    private PatternCraftingHudState lastSentHud;
    private String lastSentStateKey;

    public PatternCraftingSyncHandler(PatternEditorState state) {
        this.state = state;
        this.pipe = state.getPipe();
        allowC2S();
    }

    // region client accessors

    public PatternCraftingHudState getHudState() {
        return hudState;
    }

    public PatternCraftingHudState.PatternInfo getSelectedPatternInfo() {
        for (PatternCraftingHudState.PatternInfo info : hudState.getPatterns()) {
            if (info.getSlot() == state.getSelectedSlot()) {
                return info;
            }
        }
        return null;
    }

    public List<PatternSatelliteInfo> getSatellites() {
        return satellites;
    }

    /**
     * Increases every time a new satellite list arrives, so open lists know when to rebuild.
     */
    public int getSatelliteRevision() {
        return satelliteRevision;
    }

    public PatternSatelliteInfo findSatellite(int satelliteId, String satelliteUuid, boolean fluid) {
        PatternSatelliteInfo.SatelliteType type = fluid ? PatternSatelliteInfo.SatelliteType.FLUID
                : PatternSatelliteInfo.SatelliteType.ITEM;
        for (PatternSatelliteInfo satellite : satellites) {
            if (satellite.type() != type) {
                continue;
            }
            if (satelliteUuid != null && !satelliteUuid.isEmpty() ? satelliteUuid.equals(satellite.uuid())
                    : satellite.id() == satelliteId) {
                return satellite;
            }
        }
        return null;
    }

    public BlockingMode getBlockingMode() {
        return blockingMode;
    }

    public boolean isBlockingModeFixed() {
        return blockingModeFixed;
    }

    public ItemStack getTargetStack() {
        return targetStack;
    }

    public ForgeDirection getTargetSide() {
        return targetSide;
    }

    /**
     * Whether the pattern in the given slot is hidden from the network because it has fluid entries and the pipe lacks
     * a fluid crafting upgrade.
     */
    public boolean isPatternUnsupported(int patternSlot) {
        return (unsupportedPatterns & (1 << patternSlot)) != 0;
    }

    // endregion

    // region client actions

    public void select(int slot) {
        state.select(slot);
        syncToServer(C_SELECT, buf -> buf.writeVarIntToBuffer(slot));
    }

    public void patternAction(PatternAction action) {
        syncToServer(C_PATTERN_ACTION, buf -> buf.writeVarIntToBuffer(action.ordinal()));
    }

    public void cancelCraft() {
        syncToServer(C_CANCEL);
    }

    public void returnInputs() {
        syncToServer(C_RETURN_INPUTS);
    }

    public void cycleBlockingMode() {
        if (blockingModeFixed) {
            return;
        }
        BlockingMode[] values = BlockingMode.values();
        BlockingMode next = values[(blockingMode.ordinal() + 1) % values.length];
        blockingMode = next;
        syncToServer(C_BLOCKING_MODE, buf -> buf.writeVarIntToBuffer(next.ordinal()));
    }

    public void assignSatellite(int inputSlot, int satelliteId, String satelliteUuid, boolean fluid) {
        syncToServer(C_SATELLITE, buf -> {
            buf.writeVarIntToBuffer(inputSlot);
            buf.writeVarIntToBuffer(satelliteId);
            NetworkUtils.writeStringSafe(buf, satelliteUuid == null ? "" : satelliteUuid);
            buf.writeBoolean(fluid);
        });
    }

    public void importRecipe(PatternRecipeImport recipe) {
        syncToServer(C_IMPORT, buf -> buf.writeNBTTagCompoundToBuffer(recipe.writeToNBT()));
    }

    public void refreshSatellites() {
        syncToServer(C_REFRESH_SATELLITES);
    }

    // endregion

    @Override
    public void detectAndSendChanges(boolean init) {
        if (init) {
            sendSatellites();
            syncToClient(S_SELECT, buf -> buf.writeVarIntToBuffer(state.getSelectedSlot()));
        }
        PatternCraftingHudState hud = pipe.getPatternModule().getHudState();
        if (init || !hud.equals(lastSentHud)) {
            lastSentHud = hud;
            syncToClient(S_HUD, buf -> hud.writeData(new LPDataOutputStream(buf)));
        }
        BlockingMode mode = pipe.getBlockingMode();
        boolean fixed = pipe.isBlockingModeFixed();
        AdjacentTile target = pipe.getConnectedInventoryTile();
        ItemStack stack = target == null ? null : getDisplayStack(target.tile);
        ForgeDirection side = target == null ? ForgeDirection.UNKNOWN : target.orientation;
        int unsupported = computeUnsupportedPatterns();
        String stateKey = mode + "|" + fixed + "|" + side + "|" + unsupported + "|" + describe(stack);
        if (init || !stateKey.equals(lastSentStateKey)) {
            lastSentStateKey = stateKey;
            syncToClient(S_STATE, buf -> {
                buf.writeVarIntToBuffer(mode.ordinal());
                buf.writeBoolean(fixed);
                buf.writeVarIntToBuffer(side.ordinal());
                buf.writeVarIntToBuffer(unsupported);
                NetworkUtils.writeItemStack(buf, stack);
            });
        }
    }

    @Override
    public void readOnClient(int id, PacketBuffer buf) throws IOException {
        switch (id) {
            case S_HUD -> hudState = PatternCraftingHudState.readData(new LPDataInputStream(buf));
            case S_SATELLITES -> {
                satellites = new LPDataInputStream(buf).readList(PatternSatelliteInfo::readData);
                satelliteRevision++;
            }
            case S_STATE -> {
                blockingMode = BlockingMode.values()[buf.readVarIntFromBuffer()];
                blockingModeFixed = buf.readBoolean();
                targetSide = ForgeDirection.getOrientation(buf.readVarIntFromBuffer());
                unsupportedPatterns = buf.readVarIntFromBuffer();
                targetStack = NetworkUtils.readItemStack(buf);
            }
            case S_SELECT -> state.select(buf.readVarIntFromBuffer());
            default -> {}
        }
    }

    @Override
    public void readOnServer(int id, PacketBuffer buf) throws IOException {
        switch (id) {
            case C_SELECT -> state.select(buf.readVarIntFromBuffer());
            case C_PATTERN_ACTION -> {
                int action = buf.readVarIntFromBuffer();
                if (action >= 0 && action < PatternAction.values().length) {
                    applyPatternAction(PatternAction.values()[action]);
                }
            }
            case C_CANCEL -> pipe.cancelPatternCraft(state.getSelectedSlot());
            case C_RETURN_INPUTS -> pipe.returnStoredInputsToStorage();
            case C_BLOCKING_MODE -> {
                int mode = buf.readVarIntFromBuffer();
                if (mode >= 0 && mode < BlockingMode.values().length) {
                    pipe.setBlockingMode(BlockingMode.values()[mode]);
                    markPipeDirty();
                }
            }
            case C_SATELLITE -> applySatellite(
                    buf.readVarIntFromBuffer(),
                    buf.readVarIntFromBuffer(),
                    NetworkUtils.readStringSafe(buf),
                    buf.readBoolean());
            case C_IMPORT -> applyImport(PatternRecipeImport.readFromNBT(buf.readNBTTagCompoundFromBuffer()));
            case C_REFRESH_SATELLITES -> sendSatellites();
            default -> {}
        }
    }

    private int computeUnsupportedPatterns() {
        int mask = 0;
        for (int slot = 0; slot < PatternEditorState.PATTERN_SLOTS; slot++) {
            ItemStack configured = pipe.getPatternModule().getPatternStack(slot);
            if (configured != null && !pipe.getPatternModule().isPatternCraftingSupported(configured)) {
                mask |= 1 << slot;
            }
        }
        return mask;
    }

    private void sendSatellites() {
        EntityPlayer player = getSyncManager().getPlayer();
        List<PatternSatelliteInfo> known = new ArrayList<>(
                PipeItemsPatternSatelliteLogistics.getKnownSatellitesFor(player));
        known.addAll(PipeFluidPatternSatelliteLogistics.getKnownSatellitesFor(player));
        syncToClient(
                S_SATELLITES,
                buf -> new LPDataOutputStream(buf)
                        .writeList(known, (stream, satellite) -> satellite.writeData(stream)));
    }

    // region server logic

    private void applyPatternAction(PatternAction action) {
        ItemStack stack = state.getPatternStack();
        if (!PatternEditorState.isPattern(stack)) {
            return;
        }
        AbstractPattern pattern = ItemPattern.fromStack(stack);
        switch (action) {
            case CLEAR -> pattern.clear();
            case MULTIPLY -> {
                if (!canMultiply(pattern)) {
                    getSyncManager().getPlayer().addChatComponentMessage(
                            new ChatComponentTranslation("gui.patterncrafting.multiply.toolarge"));
                    return;
                }
                pattern.multiply(2);
            }
            case TOGGLE_TYPE -> togglePatternType(stack);
            case TOGGLE_ORE_DICT -> pattern.toggleOreDictSubstitution();
            case TOGGLE_IGNORE_NBT -> pattern.toggleIgnoreNbt();
        }
        markPatternDirty();
    }

    private static boolean canMultiply(AbstractPattern pattern) {
        for (int slot = 0; slot < pattern.getItemSlotCount(); slot++) {
            IPatternStack stack = pattern.getPatternStackInSlot(slot);
            if (stack instanceof PatternItemStack && stack.getAmount() * 2 > PatternRecipeImport.MAX_ITEM_AMOUNT) {
                return false;
            }
        }
        return true;
    }

    /**
     * Switches between crafting and processing while keeping the recipe when it fits the new type.
     * <p>
     * Slot meanings differ between the two types, so entries (and their satellite targets) are re-packed from the first
     * slot on. A processing pattern that is too large for the 3x3 crafting grid is cleared instead.
     */
    private static void togglePatternType(ItemStack stack) {
        AbstractPattern old = ItemPattern.fromStack(stack);
        List<IPatternStack> inputs = new ArrayList<>();
        List<int[]> satelliteIds = new ArrayList<>();
        List<String[]> satelliteUuids = new ArrayList<>();
        for (int slot = 0; slot < old.getIngredientSlotCount(); slot++) {
            IPatternStack input = old.getPatternStackInSlot(slot);
            if (input == null) {
                continue;
            }
            inputs.add(input);
            satelliteIds
                    .add(new int[] { old.getSatelliteIdForInputSlot(slot), old.getFluidSatelliteIdForInputSlot(slot) });
            satelliteUuids.add(
                    new String[] { old.getSatelliteUuidForInputSlot(slot),
                            old.getFluidSatelliteUuidForInputSlot(slot) });
        }
        List<IPatternStack> outputs = old.getOutputs();
        boolean toProcessing = !ItemPattern.isProcessingPattern(stack);
        ItemPattern.setProcessingPattern(stack, toProcessing);
        AbstractPattern next = ItemPattern.fromStack(stack);
        if (inputs.size() > next.getIngredientSlotCount() || outputs.size() > next.getResultSlotCount()) {
            return;
        }
        for (int slot = 0; slot < inputs.size(); slot++) {
            next.setPatternStackInSlot(slot, inputs.get(slot));
            next.setSatelliteTargetForInputSlot(slot, satelliteIds.get(slot)[0], satelliteUuids.get(slot)[0]);
            next.setFluidSatelliteTargetForInputSlot(slot, satelliteIds.get(slot)[1], satelliteUuids.get(slot)[1]);
        }
        for (int i = 0; i < outputs.size(); i++) {
            next.setPatternStackInSlot(next.getResultSlotStart() + i, outputs.get(i));
        }
    }

    /**
     * Stores the satellite target on the pattern input slot and links the satellite so staged ingredient requests can
     * resolve it.
     */
    private void applySatellite(int inputSlot, int satelliteId, String satelliteUuid, boolean fluid) {
        ItemStack stack = state.getPatternStack();
        if (!PatternEditorState.isPattern(stack)) {
            return;
        }
        AbstractPattern pattern = ItemPattern.fromStack(stack);
        if (inputSlot < 0 || inputSlot >= pattern.getIngredientSlotCount()) {
            return;
        }
        if (fluid) {
            pattern.setFluidSatelliteTargetForInputSlot(inputSlot, satelliteId, satelliteUuid);
            pipe.linkPatternFluidSatellite(satelliteId, satelliteUuid);
        } else {
            pattern.setSatelliteTargetForInputSlot(inputSlot, satelliteId, satelliteUuid);
            pipe.linkPatternSatellite(satelliteId, satelliteUuid);
        }
        markPatternDirty();
    }

    /**
     * Writes an NEI recipe into the selected pattern. If the selected slot is empty, the first blank pattern is used
     * instead, so configured patterns are never overwritten without being selected.
     */
    private void applyImport(PatternRecipeImport recipe) {
        if (recipe.isEmpty()) {
            return;
        }
        if (!state.hasPattern()) {
            int blankSlot = findBlankPatternSlot();
            if (blankSlot < 0) {
                getSyncManager().getPlayer()
                        .addChatComponentMessage(new ChatComponentTranslation("gui.patterncrafting.import.nopattern"));
                return;
            }
            state.select(blankSlot);
            syncToClient(S_SELECT, buf -> buf.writeVarIntToBuffer(blankSlot));
        }
        recipe.applyTo(state.getPatternStack());
        markPatternDirty();
    }

    private int findBlankPatternSlot() {
        for (int slot = 0; slot < PatternEditorState.PATTERN_SLOTS; slot++) {
            ItemStack stack = state.getPatternStack(slot);
            if (PatternEditorState.isPattern(stack) && !ItemPattern.fromStack(stack).isConfigured()) {
                return slot;
            }
        }
        return -1;
    }

    private void markPatternDirty() {
        pipe.getPatternModule().markPatternInventoryDirty();
        markPipeDirty();
    }

    private void markPipeDirty() {
        if (pipe.container != null) {
            pipe.container.markDirty();
        }
    }

    private static ItemStack getDisplayStack(TileEntity tile) {
        if (tile == null || tile.getWorldObj() == null) {
            return null;
        }
        Block block = tile.getWorldObj().getBlock(tile.xCoord, tile.yCoord, tile.zCoord);
        Item item = Item.getItemFromBlock(block);
        if (item == null) {
            return null;
        }
        try {
            ItemStack pick = block.getPickBlock(null, tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
            if (pick != null && pick.getItem() != null) {
                return pick;
            }
        } catch (RuntimeException ignored) {
            // some blocks require a real ray trace result; fall back to the damage value below
        }
        return new ItemStack(item, 1, block.getDamageValue(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord));
    }

    private static String describe(ItemStack stack) {
        if (stack == null) {
            return "";
        }
        return Item.getIdFromItem(stack.getItem()) + ":"
                + stack.getItemDamage()
                + ":"
                + Objects.hashCode(stack.getTagCompound());
    }

    // endregion
}
