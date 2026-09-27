package logisticspipes.crafting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import logisticspipes.crafting.pattern.AbstractPattern;
import logisticspipes.crafting.pattern.ItemPattern;
import logisticspipes.crafting.pattern.PatternHandler;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternFluidStack;
import logisticspipes.crafting.patternStack.PatternItemStack;
import logisticspipes.crafting.patternStack.PatternStackHelper;
import logisticspipes.interfaces.ISlotUpgradeManager;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.ICraftFluids;
import logisticspipes.interfaces.routing.ICraftItems;
import logisticspipes.interfaces.routing.IFilter;
import logisticspipes.interfaces.routing.IItemSpaceControl;
import logisticspipes.interfaces.routing.IRequest;
import logisticspipes.interfaces.routing.IRequestFluid;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.interfaces.routing.IRequireReliableTransport;
import logisticspipes.logistics.LogisticsManager;
import logisticspipes.modules.abstractmodules.LogisticsGuiModule;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.pipefxhandlers.Particles;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.request.ICraftingTemplate;
import logisticspipes.request.IPromise;
import logisticspipes.request.RequestTree;
import logisticspipes.request.RequestTreeNode;
import logisticspipes.request.debug.CraftingRequestDebugManager;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.FluidResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.FluidExtraPromise;
import logisticspipes.routing.FluidLogisticsPromise;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.LogisticsDictPromise;
import logisticspipes.routing.LogisticsExtraPromise;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.routing.order.IOrderInfoProvider;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LogisticsFluidOrder;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.routing.order.LogisticsOrder;
import logisticspipes.utils.AdjacentTile;
import logisticspipes.utils.CacheHolder.CacheTypes;
import logisticspipes.utils.DelayedGeneric;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.SinkReply.BufferMode;
import logisticspipes.utils.SinkReply.FixedPriority;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.item.SimpleStackInventory;
import logisticspipes.utils.tuples.Pair;

public class ModulePatternCrafting extends LogisticsGuiModule
        implements ICraftItems, ICraftFluids, IRequestFluid, IRequireReliableTransport, IStagedCraftingProvider {

    private static final String LOST_INGREDIENTS_TAG = "patternLostIngredients";
    private static final String LOST_DELAY_TAG = "delay";
    private static final String STAGED_CRAFTING_TAG = "patternStagedCrafting";
    private static final String TARGET_PATTERN_SLOT_TAG = "targetPatternSlot";
    private static final String TARGET_INPUT_SLOT_TAG = "targetInputSlot";
    private static final int RESTORED_REQUESTED_RETRY_DELAY = 8000;
    private static final int RESTORE_DEBUG_INTERVAL = 40;
    private static final int DEFAULT_THROTTLE_TICKS = 40;
    private static final int HUD_STATE_RECHECK_INTERVAL = 20;
    private static final int TAG_COMPOUND = 10;

    private final PipeItemsPatternCraftingLogistics pipe;
    private final SimpleStackInventory patternInventory = new SimpleStackInventory(9, "Patterns", 1);
    private final Map<Integer, List<IPatternStack>> requestedIngredients = new HashMap<>();
    private final Set<Integer> cancelledPatternSlots = new HashSet<>();
    private final Map<String, ThrottledDebugEvent> throttledDebugEvents = new HashMap<>();
    private final DelayQueue<DelayedGeneric<Pair<IPatternStack, IAdditionalTargetInformation>>> lostIngredients = new DelayQueue<>();
    private final PatternHandler patternHandler = new PatternHandler(patternInventory);
    private final AdjacentInventoryHandler adjacentInventory;
    private final PatternStackBufferHandler ingredientBuffer;
    private final PatternStackRequestHandler requestedIngredient;
    private final PatternStagedCraftingCoordinator stagedCrafting;
    private final PatternCraftingTemplateBuilder templateBuilder;
    private final PatternCraftingResultExtractor resultExtractor;
    private SinkReply sinkReply;
    private PipeItemsPatternCraftingLogistics.BlockingMode blockingMode = PipeItemsPatternCraftingLogistics.BlockingMode.OFF;
    private int runningCraft = -1;
    private boolean runningCraftInAdjacent = false;
    private PatternSatelliteDispatchBatch activeSatelliteBatch;
    private boolean checkingBufferedOrders = false;
    private NBTTagCompound pendingStagedCrafting;
    private boolean pendingRequestedIngredientRestoreRetries;
    private int stagedCraftingRestoreAttempts;
    private PatternCraftingHudState cachedHudState = PatternCraftingHudState.empty();
    private boolean hudStateDirty = true;
    private long lastHudStateBuildTick = Long.MIN_VALUE;

    public ModulePatternCrafting(PipeItemsPatternCraftingLogistics pipe) {
        this.pipe = pipe;
        patternInventory.addListener(inventory -> {
            if (pipe.container != null) {
                pipe.container.markDirty();
            }
            markHudStateDirty();
            pipe.listenedChanged();
        });
        adjacentInventory = new AdjacentInventoryHandler(this, pipe);
        ingredientBuffer = new PatternStackBufferHandler(this::markHudStateDirty);
        requestedIngredient = new PatternStackRequestHandler(requestedIngredients, this::markHudStateDirty);
        stagedCrafting = new PatternStagedCraftingCoordinator(
                this,
                pipe,
                patternHandler,
                requestedIngredient,
                adjacentInventory);
        templateBuilder = new PatternCraftingTemplateBuilder(this, patternHandler);
        resultExtractor = new PatternCraftingResultExtractor(this, pipe, adjacentInventory);
        _service = pipe;
        _world = pipe;
        registerPosition(ModulePositionType.IN_PIPE, 0);
    }

    public IInventory getPatternInventory() {
        return patternInventory;
    }

    public ItemStack getPatternStack(int slot) {
        return patternHandler.getConfiguredPatternStack(slot);
    }

    public ItemStack getPatternItemStack(int slot) {
        if (slot < 0 || slot >= patternInventory.getSizeInventory()) {
            return null;
        }
        return patternInventory.getStackInSlot(slot);
    }

    public void markPatternInventoryDirty() {
        patternInventory.markDirty();
        markHudStateDirty();
    }

    public int assignSatelliteToAllPatternIngredients(int satelliteId, String satelliteUuid) {
        int changed = 0;
        for (int patternSlot = 0; patternSlot < patternInventory.getSizeInventory(); patternSlot++) {
            ItemStack patternStack = patternInventory.getStackInSlot(patternSlot);
            if (patternStack == null) {
                continue;
            }
            AbstractPattern pattern = ItemPattern.fromStack(patternStack);
            for (int inputSlot = 0; inputSlot < pattern.getIngredientSlotCount(); inputSlot++) {
                IPatternStack ingredient = pattern.getPatternStackInSlot(inputSlot);
                if (!PatternStackHelper.isSolid(ingredient)) {
                    continue;
                }
                pattern.setSatelliteTargetForInputSlot(inputSlot, satelliteId, satelliteUuid);
                changed++;
            }
        }
        if (changed > 0) {
            markPatternInventoryDirty();
        }
        return changed;
    }

    public PipeItemsPatternCraftingLogistics.BlockingMode getBlockingMode() {
        return getEffectiveBlockingMode();
    }

    public void setBlockingMode(PipeItemsPatternCraftingLogistics.BlockingMode blockingMode) {
        PipeItemsPatternCraftingLogistics.BlockingMode requestedMode = blockingMode == null
                ? PipeItemsPatternCraftingLogistics.BlockingMode.OFF
                : blockingMode;
        PipeItemsPatternCraftingLogistics.BlockingMode nextMode = adjacentInventory.isConnectedToPatternCraftingTable()
                ? PipeItemsPatternCraftingLogistics.BlockingMode.SMART
                : requestedMode;
        if (this.blockingMode != nextMode) {
            this.blockingMode = nextMode;
            markHudStateDirty();
        }
    }

    public boolean isBlockingModeFixed() {
        return adjacentInventory.isConnectedToPatternCraftingTable();
    }

    PipeItemsPatternCraftingLogistics.BlockingMode getEffectiveBlockingMode() {
        if (adjacentInventory.isConnectedToPatternCraftingTable()) {
            return PipeItemsPatternCraftingLogistics.BlockingMode.SMART;
        }
        return blockingMode;
    }

    /**
     * Reports how many ingredients this module can currently receive for its configured patterns.
     * <p>
     * The result includes reserved space for ingredients this module has already requested through staged crafting, so
     * subcraft results from this same pipe can be routed back into the module buffer instead of being rejected while
     * the connected inventory is busy.
     */
    @Override
    public SinkReply sinksItem(ItemIdentifier item, int bestPriority, int bestCustomPriority, boolean allowDefault,
            boolean includeInTransit) {
        if (bestPriority > sinkReply.fixedPriority.ordinal() || (bestPriority == sinkReply.fixedPriority.ordinal()
                && bestCustomPriority >= sinkReply.customPriority)) {
            return null;
        }
        FluidIdentifier fluid = item != null && item.isFluidContainer() ? FluidIdentifier.get(item) : null;
        if (fluid != null) {
            if (!supportsFluidCrafting()) {
                debug("sink rejected fluid ingredient %s: fluid crafting upgrade missing", fluid);
                return null;
            }
            int room = spaceForFluid(fluid, includeInTransit);
            if (room <= 0) {
                debug("sink rejected fluid ingredient %s includeInTransit=%s", fluid, includeInTransit);
                return null;
            }
            debug("sink accepts fluid ingredient %s room=%d includeInTransit=%s", fluid, room, includeInTransit);
            return new SinkReply(
                    sinkReply,
                    room,
                    areAllOrdersBuffered() ? BufferMode.DESTINATION_BUFFERED : BufferMode.NONE);
        }
        int room = spaceFor(item, includeInTransit);
        if (room <= 0) {
            debug("sink rejected item ingredient %s includeInTransit=%s", item, includeInTransit);
            return null;
        }
        debug("sink accepts item ingredient %s room=%d includeInTransit=%s", item, room, includeInTransit);
        return new SinkReply(
                sinkReply,
                room,
                areAllOrdersBuffered() ? BufferMode.DESTINATION_BUFFERED : BufferMode.NONE);
    }

    /**
     * Reports how much of a routed fluid container can currently be accepted as a pattern ingredient.
     * <p>
     * The pattern pipe advertises itself as a fluid sink so storage routing can find it, but the accepted fluid is
     * still delivered as a LogisticsFluidContainer item and buffered by this module.
     */
    public int sinkAmount(FluidStack stack) {
        if (stack == null || stack.amount <= 0) {
            return 0;
        }
        if (!supportsFluidCrafting()) {
            debug("fluid sink amount rejected %s: fluid crafting upgrade missing", FluidIdentifier.get(stack));
            return 0;
        }
        int room = spaceForFluid(FluidIdentifier.get(stack), true);
        debug("fluid sink amount check %s amount=%d room=%d", FluidIdentifier.get(stack), stack.amount, room);
        return room >= stack.amount ? stack.amount : 0;
    }

    @Override
    public void tick() {
        restoreStagedCraftingIfNeeded();
        cancelUnsupportedFluidPatternCrafts();
        scheduleRequestedIngredientRestoreRetriesIfReady();
        retryLostItems();
        refreshSatelliteDispatchBatch();
        pushBufferedIngredients();
        stagedCrafting.requestIngredients();
        clearRunningCraftIfFinished();
        resultExtractor.tick();
    }

    @Override
    public boolean hasGenericInterests() {
        return false;
    }

    @Override
    public Collection<ItemIdentifier> getSpecificInterests() {
        return patternHandler.getIngredientItems();
    }

    /**
     * Returns every item identity this module can craft, including fluid outputs represented by their display item.
     */
    public Set<ItemIdentifier> getCraftedItems() {
        Set<ItemIdentifier> crafted = new TreeSet<>();
        for (ItemStack pattern : patternHandler.getConfiguredPatterns()) {
            if (!isPatternCraftingSupported(pattern)) {
                continue;
            }
            AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
            for (IPatternStack result : configuredPattern.getOutputs()) {
                ItemIdentifier item = PatternStackHelper.getRoutingItem(result);
                if (item != null) {
                    crafted.add(item);
                }
            }
        }
        return crafted;
    }

    // ------- DEFAULT OVERRIDES ------ //
    // region DEFAULT OVERRIDES

    @Override
    public boolean interestedInAttachedInventory() {
        return false;
    }

    @Override
    public boolean interestedInUndamagedID() {
        return false;
    }

    @Override
    public boolean recievePassive() {
        return false;
    }

    @Override
    public void getAllItems(Map<ItemIdentifier, Integer> list, List<IFilter> filter) {}

    @Override
    public LogisticsModule getSubModule(int slot) {
        return null;
    }

    @Override
    public Map<FluidIdentifier, Integer> getAvailableFluids() {
        return Collections.emptyMap();
    }

    @Override
    protected ModuleCoordinatesGuiProvider getPipeGuiProvider() {
        return NewGuiHandler.getGui(PatternCraftingPipeGuiProvider.class).setBlockingMode(getBlockingMode().ordinal());
    }

    @Override
    protected ModuleInHandGuiProvider getInHandGuiProvider() {
        return null;
    }

    @Override
    public net.minecraft.util.IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleCrafter");
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        patternInventory.readFromNBT(tag, "PatternCrafting");
        blockingMode = PipeItemsPatternCraftingLogistics.BlockingMode.values()[Math.max(
                0,
                Math.min(
                        PipeItemsPatternCraftingLogistics.BlockingMode.values().length - 1,
                        tag.getInteger("patternBlockingMode")))];
        runningCraft = tag.hasKey("runningCraft") ? tag.getInteger("runningCraft")
                : tag.getInteger("bufferedPatternSlot");
        runningCraftInAdjacent = tag.hasKey("runningCraftInAdjacent") ? tag.getBoolean("runningCraftInAdjacent")
                : runningCraft >= 0;
        ingredientBuffer.readFromNBT(tag);
        requestedIngredient.readFromNBT(tag);
        readLostIngredientsFromNBT(tag);
        pendingStagedCrafting = tag.hasKey(STAGED_CRAFTING_TAG)
                ? (NBTTagCompound) tag.getCompoundTag(STAGED_CRAFTING_TAG).copy()
                : null;
        pendingRequestedIngredientRestoreRetries = !requestedIngredients.isEmpty();
        stagedCraftingRestoreAttempts = 0;
        markHudStateDirty();
        debugEvent(
                "PERSIST",
                "loaded module nbt bufferedSlots=%d requestedSlots=%d lostQueued=%d pendingStaged=%s runningCraft=%d adjacentBatch=%s",
                ingredientBuffer.size(),
                requestedIngredients.size(),
                lostIngredients.size(),
                pendingStagedCrafting != null,
                runningCraft,
                runningCraftInAdjacent);
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        patternInventory.writeToNBT(tag, "PatternCrafting");
        tag.setInteger("patternBlockingMode", blockingMode.ordinal());
        tag.setInteger("runningCraft", runningCraft);
        tag.setInteger("bufferedPatternSlot", runningCraft);
        tag.setBoolean("runningCraftInAdjacent", runningCraftInAdjacent);
        ingredientBuffer.writeToNBT(tag);
        requestedIngredient.writeToNBT(tag);
        writeLostIngredientsToNBT(tag);
        if (pendingStagedCrafting != null) {
            tag.setTag(STAGED_CRAFTING_TAG, pendingStagedCrafting.copy());
        } else {
            NBTTagCompound stagedTag = new NBTTagCompound();
            stagedCrafting.writeToNBT(stagedTag);
            tag.setTag(STAGED_CRAFTING_TAG, stagedTag);
        }
        debugEvent(
                "PERSIST",
                "saved module nbt bufferedSlots=%d requestedSlots=%d lostQueued=%d pendingStaged=%s runningCraft=%d adjacentBatch=%s",
                ingredientBuffer.size(),
                requestedIngredients.size(),
                lostIngredients.size(),
                pendingStagedCrafting != null,
                runningCraft,
                runningCraftInAdjacent);
    }
    // endregion

    // ------- DEBUG ------ //
    // region DEBUG

    @Override
    public void registerPosition(ModulePositionType slot, int positionInt) {
        super.registerPosition(slot, positionInt);
        sinkReply = new SinkReply(FixedPriority.ItemSink, 0, true, false, 1, 0, null);
    }

    void debug(String message, Object... args) {
        if (_service != null) {
            _service.getDebug().log("PatternCrafting: " + message, args);
        }
    }

    public void debugEvent(String category, String message, Object... args) {
        recordDebugEvent(category, formatDebugMessage(message, args));
    }

    void debugEventThrottled(String category, String message, Object... args) {
        debugEventThrottled(category, DEFAULT_THROTTLE_TICKS, message, args);
    }

    void debugEventThrottled(String category, int intervalTicks, String message, Object... args) {
        String formatted = formatDebugMessage(message, args);
        if (intervalTicks <= 0) {
            recordDebugEvent(category, formatted);
            return;
        }
        String key = category + "\n" + formatted;
        long tick = currentDebugTick();
        ThrottledDebugEvent state = throttledDebugEvents.get(key);
        if (state != null && tick - state.lastLoggedTick < intervalTicks) {
            state.suppressed++;
            return;
        }
        if (state == null) {
            state = new ThrottledDebugEvent();
            throttledDebugEvents.put(key, state);
        } else if (state.suppressed > 0) {
            formatted = formatted + " (suppressed repeats=" + state.suppressed + ")";
        }
        state.lastLoggedTick = tick;
        state.suppressed = 0;
        recordDebugEvent(category, formatted);
    }

    public void recordDebugEvent(String category, String message) {
        debug("%s", message);
        CraftingRequestDebugManager.recordPipeEvent(pipe, category, message);
    }

    private String formatDebugMessage(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message == null ? "" : message;
        }
        try {
            return String.format(message, args);
        } catch (RuntimeException ignored) {
            StringBuilder out = new StringBuilder(message == null ? "" : message);
            out.append(" args=");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(args[i]);
            }
            return out.toString();
        }
    }

    private long currentDebugTick() {
        return currentWorldTick();
    }

    private long currentWorldTick() {
        World world = pipe.getWorld();
        return world == null ? 0 : world.getTotalWorldTime();
    }
    // endregion

    private void restoreStagedCraftingIfNeeded() {
        if (pendingStagedCrafting == null) {
            return;
        }
        World world = pipe.getWorld();
        if (world != null && world.isRemote) {
            return;
        }
        if (stagedCrafting.restoreFromNBT(pendingStagedCrafting)) {
            debugEvent("STAGED", "restored staged crafting state after %d attempts", stagedCraftingRestoreAttempts + 1);
            pendingStagedCrafting = null;
            stagedCraftingRestoreAttempts = 0;
            markHudStateDirty();
            return;
        }
        stagedCraftingRestoreAttempts++;
        if (stagedCraftingRestoreAttempts % RESTORE_DEBUG_INTERVAL == 0) {
            debugEvent("STAGED", "waiting to restore staged crafting state attempts=%d", stagedCraftingRestoreAttempts);
        }
    }

    private void scheduleRequestedIngredientRestoreRetriesIfReady() {
        if (!pendingRequestedIngredientRestoreRetries || pendingStagedCrafting != null) {
            return;
        }
        pendingRequestedIngredientRestoreRetries = false;
        for (Map.Entry<Integer, List<IPatternStack>> entry : requestedIngredients.entrySet()) {
            int patternSlot = entry.getKey();
            for (IPatternStack stack : entry.getValue()) {
                if (stack == null || stack.getAmount() <= 0) {
                    continue;
                }
                lostIngredients.add(
                        new DelayedGeneric<>(
                                new Pair<>(stack.copy(), new PatternTargetInformation(patternSlot)),
                                RESTORED_REQUESTED_RETRY_DELAY));
                debugEvent(
                        "REQUEST",
                        "restore queued requested ingredient retry slot=%d ingredient=%s",
                        patternSlot,
                        stack);
            }
        }
    }

    boolean supportsFluidCrafting() {
        ISlotUpgradeManager upgradeManager = getUpgradeManager();
        return upgradeManager != null && upgradeManager.getFluidCrafter() > 0;
    }

    /**
     * Returns false for patterns with fluid entries while the pipe has no fluid crafting upgrade. Such patterns are not
     * offered to the network.
     */
    public boolean isPatternCraftingSupported(ItemStack pattern) {
        return !isFluidCraftingPattern(pattern) || supportsFluidCrafting();
    }

    private boolean isFluidCraftingPattern(ItemStack pattern) {
        if (pattern == null) {
            return false;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        return PatternStackHelper.containsFluid(configuredPattern.getInputs())
                || PatternStackHelper.containsFluid(configuredPattern.getOutputs());
    }

    private void cancelUnsupportedFluidPatternCrafts() {
        // TODO dont execute every tick
        if (supportsFluidCrafting()) {
            return;
        }
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            ItemStack pattern = patternHandler.getConfiguredPatternStack(slot);
            if (!isFluidCraftingPattern(pattern)) {
                continue;
            }
            if (stagedCrafting.hasPattern(slot) || requestedIngredients.containsKey(slot)
                    || ingredientBuffer.asMap().containsKey(slot)) {
                debugEventThrottled("STAGED", "cancel fluid pattern slot=%d: fluid crafting upgrade missing", slot);
                cancelPatternCraft(slot);
            }
        }
    }

    /**
     * Offers already-registered extra outputs to a request tree before new crafting work is considered.
     * <p>
     * Item extras and fluid extras are checked against their own order managers. If an extra is consumed, fulfilment
     * will remove or reduce the destinationless extra order so the byproduct is not extracted twice.
     */
    @Override
    public void canProvide(RequestTreeNode tree, RequestTree root, List<IFilter> filters) {
        IResource requested = tree.getRequestType();
        if (pipe.getPatternFluidOrderManager().hasExtras() && !tree.hasBeenQueried(pipe.getPatternFluidOrderManager())
                && requested instanceof FluidResource) {
            FluidIdentifier fluid = ((FluidResource) requested).getFluid();
            for (LogisticsFluidOrder order : pipe.getPatternFluidOrderManager()) {
                if (order.getType() == ResourceType.EXTRA && order.getFluid().equals(fluid)) {
                    int amount = Math.min(order.getAmount(), tree.getMissingAmount());
                    if (amount > 0) {
                        debug("providing extra fluid %s amount=%d for request %s", fluid, amount, requested);
                        tree.addPromise(new FluidExtraPromise(order.getFluid(), amount, this, true));
                        tree.setQueried(pipe.getPatternFluidOrderManager());
                        return;
                    }
                }
            }
            tree.setQueried(pipe.getPatternFluidOrderManager());
        }
        if (!pipe.getItemOrderManager().hasExtras() || tree.hasBeenQueried(pipe.getItemOrderManager())) {
            return;
        }
        for (LogisticsItemOrder order : pipe.getItemOrderManager()) {
            if (order.getType() == ResourceType.EXTRA
                    && requested.matches(order.getResource().getItem(), IResource.MatchSettings.NORMAL)) {
                int amount = Math.min(order.getAmount(), tree.getMissingAmount());
                if (amount > 0) {
                    debug(
                            "providing extra %s amount=%d for request %s",
                            order.getResource().getItem(),
                            amount,
                            requested);
                    tree.addPromise(new LogisticsExtraPromise(order.getResource().getItem(), amount, this, true));
                    tree.setQueried(pipe.getItemOrderManager());
                    return;
                }
            }
        }
    }

    /**
     * Creates an item output order for a pattern craft or for a previously registered item extra.
     * <p>
     * When an extra promise is used to satisfy a request, its destinationless extra order is removed first; the new
     * order then targets the real requester like a normal craft output.
     */
    @Override
    public LogisticsOrder fullFill(LogisticsPromise promise, IRequestItems destination,
            IAdditionalTargetInformation info) {
        if (promise instanceof LogisticsExtraPromise) {
            pipe.getItemOrderManager().removeExtras(
                    new logisticspipes.request.resources.DictResource(
                            new ItemIdentifierStack(promise.item, promise.numberOfItems),
                            null));
            markHudStateDirty();
        }
        pipe.spawnParticle(Particles.WhiteParticle, 2);
        debugEvent(
                "ORDER",
                "create item output order item=%s amount=%d destination=%s info=%s",
                promise.item,
                promise.numberOfItems,
                destination,
                info);
        return pipe.getItemOrderManager().addOrder(
                new ItemIdentifierStack(promise.item, promise.numberOfItems),
                destination,
                ResourceType.CRAFTING,
                info);
    }

    /**
     * Starts a staged craft from a request-tree branch.
     * <p>
     * The output order stays in this pipe's order manager, while the branch is kept so this module can request only the
     * ingredient sets that currently fit in its buffer or connected inventory.
     */
    @Override
    public IOrderInfoProvider fullFillStagedCrafting(IPromise promise, IResource requestType,
            IAdditionalTargetInformation info, PatternCraftingBranch branch) {
        return stagedCrafting.fulfill(promise, requestType, info, branch);
    }

    /**
     * Creates a fluid output order for a pattern craft or for a previously registered fluid extra.
     * <p>
     * Consumed fluid extras are removed from the destinationless extra-order queue before the new requester-targeted
     * order is added.
     */
    @Override
    public IOrderInfoProvider fullFill(FluidLogisticsPromise promise, IRequestFluid destination, ResourceType type,
            IAdditionalTargetInformation info) {
        ResourceType orderType = type;
        if (promise instanceof FluidExtraPromise) {
            pipe.getPatternFluidOrderManager().removeExtras(promise.getLiquid(), promise.getAmount());
            orderType = ResourceType.CRAFTING;
            markHudStateDirty();
        }
        pipe.spawnParticle(Particles.WhiteParticle, 2);
        debugEvent(
                "ORDER",
                "create fluid output order fluid=%s amount=%d destination=%s info=%s",
                promise.getLiquid(),
                promise.getAmount(),
                destination,
                info);
        return pipe.getPatternFluidOrderManager().addOrder(promise, destination, orderType, info);
    }

    @Override
    public void sendFailed(FluidIdentifier fluid, Integer amount) {
        if (fluid != null && amount != null && amount > 0) {
            debugEvent("FLOW", "fluid send failed fluid=%s amount=%d; queued lost ingredient retry", fluid, amount);
            lostIngredients.add(new DelayedGeneric<>(new Pair<>(new PatternFluidStack(fluid, amount), null), 5000));
        }
    }

    @Override
    public IRouter getRouter() {
        return pipe.getRouter();
    }

    @Override
    public void itemCouldNotBeSend(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        pipe.itemCouldNotBeSend(item, info);
    }

    @Override
    public int getID() {
        return pipe.getID();
    }

    @Override
    public int compareTo(IRequest request) {
        return getID() - request.getID();
    }

    /**
     * Registers item or fluid byproducts as destinationless extra orders.
     * <p>
     * Those orders force the extraction phase to remove extra products from the connected inventory or fluid handler,
     * then route them to storage or drop them if no storage can accept them.
     */
    @Override
    public void registerExtras(IPromise promise) {
        if (promise instanceof FluidLogisticsPromise fluidPromise) {
            debugEvent(
                    "EXTRA",
                    "register fluid extra %s amount=%d",
                    fluidPromise.getLiquid(),
                    fluidPromise.getAmount());
            pipe.getPatternFluidOrderManager().addExtra(fluidPromise.getLiquid(), fluidPromise.getAmount());
            markHudStateDirty();
            return;
        }
        if (promise instanceof LogisticsDictPromise) {
            DictResource resource = ((LogisticsDictPromise) promise).getResource().clone();
            resource.getItemStack().setStackSize(promise.getAmount());
            debugEvent("EXTRA", "register dict extra %s amount=%d", resource.getItem(), promise.getAmount());
            pipe.getItemOrderManager().addExtra(resource);
            markHudStateDirty();
            return;
        }
        debugEvent("EXTRA", "register extra %s amount=%d", promise.getItemType(), promise.getAmount());
        pipe.getItemOrderManager()
                .addExtra(new DictResource(new ItemIdentifierStack(promise.getItemType(), promise.getAmount()), null));
        markHudStateDirty();
    }

    /**
     * Builds a crafting template for the requested item or fluid output.
     * <p>
     * The template records all local ingredients and all non-requested outputs as byproducts. Fluid outputs are matched
     * through their fluid display item identity so the normal request tree can discover them.
     */
    @Override
    public ICraftingTemplate addCrafting(IResource toCraft) {
        return templateBuilder.addCrafting(toCraft);
    }

    @Override
    public boolean canCraft(IResource toCraft) {
        for (ItemIdentifier item : getCraftedItems()) {
            if (toCraft.matches(item, IResource.MatchSettings.NORMAL)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getTodo() {
        return pipe.getItemOrderManager().totalAmountCountInAllOrders();
    }

    @Override
    public List<ItemIdentifierStack> getConfiguredCraftResults() {
        List<ItemIdentifierStack> results = new ArrayList<>();
        for (ItemStack pattern : patternHandler.getConfiguredPatterns()) {
            if (!isPatternCraftingSupported(pattern)) {
                continue;
            }
            AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
            for (IPatternStack output : configuredPattern.getOutputs()) {
                ItemIdentifierStack display = PatternStackHelper.makeDisplayStack(output);
                if (display != null) {
                    results.add(display);
                }
            }
        }
        return results;
    }

    /**
     * Returns the cached HUD snapshot, rebuilding it only after crafting state changed or after a short external-state
     * recheck interval.
     */
    public PatternCraftingHudState getHudState() {
        if (shouldRefreshHudState()) {
            cachedHudState = buildHudState();
            hudStateDirty = false;
            lastHudStateBuildTick = currentWorldTick();
        }
        return cachedHudState;
    }

    /**
     * Reports whether callers that broadcast HUD content should ask for a fresh snapshot.
     */
    public boolean shouldRefreshHudState() {
        return hudStateDirty || isHudStateRecheckDue();
    }

    /**
     * Invalidates the cached HUD snapshot after a crafting-visible state change.
     */
    public void markHudStateDirty() {
        hudStateDirty = true;
    }

    private boolean isHudStateRecheckDue() {
        long tick = currentWorldTick();
        return lastHudStateBuildTick == Long.MIN_VALUE || tick - lastHudStateBuildTick >= HUD_STATE_RECHECK_INTERVAL;
    }

    private PatternCraftingHudState buildHudState() {
        refreshRunningCraftState(getConnectedInventoryTile());
        PatternCraftingHudState state = new PatternCraftingHudState(getEffectiveBlockingMode());
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            ItemStack pattern = getPatternStack(slot);
            if (pattern == null) {
                continue;
            }
            AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
            PatternCraftingHudState.PatternInfo patternInfo = new PatternCraftingHudState.PatternInfo(slot);
            for (int inputSlot = 0; inputSlot < configuredPattern.getIngredientSlotCount(); inputSlot++) {
                IPatternStack ingredient = configuredPattern.getPatternStackInSlot(inputSlot);
                ItemIdentifierStack display = PatternStackHelper.makeDisplayStack(ingredient);
                if (display != null) {
                    patternInfo.getIngredients().add(
                            new PatternCraftingHudState.IngredientInfo(
                                    display,
                                    bufferedIngredientAmount(slot, pattern, ingredient),
                                    inputSlot));
                }
            }
            for (int outputSlot = 0; outputSlot < configuredPattern.getResultSlotCount(); outputSlot++) {
                IPatternStack output = configuredPattern
                        .getPatternStackInSlot(configuredPattern.getResultSlotStart() + outputSlot);
                ItemIdentifierStack display = PatternStackHelper.makeDisplayStack(output);
                if (display != null) {
                    patternInfo.getOutputs().add(
                            new PatternCraftingHudState.OutputInfo(
                                    display,
                                    stagedCrafting.remainingOutputAmount(slot, output),
                                    outputSlot));
                }
            }
            patternInfo.setActive(slot == runningCraft);
            patternInfo.setStatus(getHudStatus(slot, pattern));
            state.getPatterns().add(patternInfo);
        }
        return state;
    }

    private String getHudStatus(int patternSlot, ItemStack pattern) {
        if (!isPatternCraftingSupported(pattern)) {
            return "Waiting: fluid crafting upgrade missing";
        }
        PipeItemsPatternCraftingLogistics.BlockingMode mode = getEffectiveBlockingMode();
        AdjacentTile connected = adjacentInventory.getConnected();
        int bufferedSets = completeBufferedSets(patternSlot);
        if (activeSatelliteBatch != null) {
            return activeSatelliteBatch.patternSlot == patternSlot ? "Doing: waiting on satellites"
                    : "Waiting: satellites reserved";
        }
        if (runningCraft == patternSlot && runningCraftInAdjacent) {
            return "Doing: crafting in target inventory";
        }
        if (mode != PipeItemsPatternCraftingLogistics.BlockingMode.OFF && runningCraft >= 0
                && runningCraft != patternSlot
                && runningCraftInAdjacent
                && connected != null
                && !isInventoryEmpty(connected)) {
            return "Waiting: blocking slot " + (runningCraft + 1) + " is crafting";
        }
        if (bufferedSets > 0) {
            if (connected == null) {
                return "Waiting: no target inventory";
            }
            if (mode == PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING
                    && !adjacentInventory.isEmpty(connected)) {
                return "Waiting: target inventory occupied";
            }
            if (findInsertableBufferedPlan(patternSlot, pattern, bufferedSets) == null) {
                return "Waiting: no target space";
            }
            return "Doing: ready to insert " + formatSets(bufferedSets);
        }
        String pendingIngredient = getHudPendingIngredient(patternSlot, pattern);
        if (pendingIngredient != null) {
            return pendingIngredient;
        }
        int stagedSets = stagedRemainingSets(patternSlot);
        if (stagedSets > 0) {
            if (!canReceiveForPattern(patternSlot)) {
                return runningCraft >= 0 ? "Waiting: blocking slot " + (runningCraft + 1) : "Waiting: buffer space";
            }
            return "Doing: requesting " + formatSets(stagedSets);
        }
        if (totalAmount(ingredientBuffer.asMap().get(patternSlot)) > 0) {
            return "Waiting: buffered ingredients incomplete";
        }
        return "Idle";
    }

    private String getHudPendingIngredient(int patternSlot, ItemStack pattern) {
        for (PatternIngredientTarget target : getIngredientTargets(pattern)) {
            IPatternStack ingredient = target.stack();
            int buffered = bufferedIngredientAmount(patternSlot, pattern, ingredient);
            int requested = requestedIngredientAmount(patternSlot, pattern, ingredient);

            if (requested <= 0) continue;

            ingredient.getAmount();
            if (buffered < ingredient.getAmount()) {
                String status = "Waiting on " + getHudIngredientName(
                        ingredient) + " (" + buffered + "/" + ingredient.getAmount();
                status += ", " + requested + " routed";

                return status + ")";
            }
            return "Waiting on " + getHudIngredientName(ingredient) + " (" + requested + " routed)";
        }
        if (totalAmount(requestedIngredients.get(patternSlot)) > 0) {
            return "Waiting on ingredients";
        }
        return null;
    }

    private String getHudIngredientName(IPatternStack stack) {
        ItemIdentifierStack display = PatternStackHelper.makeDisplayStack(stack);
        return display == null ? "ingredient" : display.getItem().getFriendlyName();
    }

    private String formatSets(int sets) {
        return sets == 1 ? "1 set" : sets + " sets";
    }

    private int stagedRemainingSets(int patternSlot) {
        return stagedCrafting.remainingSets(patternSlot);
    }

    private int totalAmount(List<IPatternStack> stacks) {
        int amount = 0;
        if (stacks == null) {
            return amount;
        }
        for (IPatternStack stack : stacks) {
            if (stack != null) {
                amount += Math.max(0, stack.getAmount());
            }
        }
        return amount;
    }

    /**
     * Appends the current module-side staged crafting state to the crafting request debug dump.
     */
    public void appendDebugState(StringBuilder out) {
        out.append("Pattern crafting pipe at ").append(pipe.getX()).append(", ").append(pipe.getY()).append(", ")
                .append(pipe.getZ()).append(" router=").append(pipe.getRouter().getSimpleID()).append("\n");
        out.append("  mode stored=").append(blockingMode).append(" effective=").append(getEffectiveBlockingMode())
                .append(" fixed=").append(isBlockingModeFixed()).append(" runningCraft=").append(runningCraft)
                .append(" adjacentBatch=").append(runningCraftInAdjacent).append("\n");
        appendConnectedInventoryDebug(out);
        appendPatternDebug(out);
        appendStackMapDebug(out, "buffered ingredients", ingredientBuffer.asMap());
        appendStackMapDebug(out, "requested ingredients", requestedIngredients);
        appendStagedCraftDebug(out);
        appendOrderDebug(out);
        out.append("  lost ingredients queued=").append(lostIngredients.size()).append("\n");
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        debugEvent("FLOW", "ingredient lost item=%s info=%s", item, info);
        if (info instanceof PatternTargetInformation && item != null) {
            FluidStack fluid = SimpleServiceLocator.logisticsFluidManager.getFluidFromContainer(item);
            int patternSlot = ((PatternTargetInformation) info).patternSlot();
            if (fluid != null) {
                PatternFluidStack patternFluid = new PatternFluidStack(FluidIdentifier.get(fluid), fluid.amount);
                requestedIngredient.remove(patternSlot, patternFluid);
                debugEvent(
                        "FLOW",
                        "lost fluid ingredient slot=%d fluid=%s amount=%d removed from requested and queued retry",
                        patternSlot,
                        FluidIdentifier.get(fluid),
                        fluid.amount);
                lostIngredients.add(
                        new DelayedGeneric<>(
                                new Pair<>(new PatternFluidStack(FluidIdentifier.get(fluid), fluid.amount), info),
                                5000));
                return;
            }
            removeRequestedItem(patternSlot, getPatternStack(patternSlot), item.getItem(), item.getStackSize());
            debugEvent(
                    "FLOW",
                    "lost item ingredient slot=%d item=%s amount=%d removed from requested",
                    patternSlot,
                    item.getItem(),
                    item.getStackSize());
        }
        if (item != null) {
            lostIngredients.add(new DelayedGeneric<>(new Pair<>(new PatternItemStack(item.clone()), info), 5000));
            debugEvent("FLOW", "queued lost item retry item=%s info=%s", item, info);
        }
    }

    /**
     * Accepts an ingredient that was routed to this pattern module.
     * <p>
     * Requested ingredients are accepted into the module buffer even when the adjacent inventory is currently busy.
     * Those requests were already staged against this pipe's own capacity, so asking the adjacent inventory again here
     * can bounce valid subcraft results back to storage. Non-requested overflow still uses the normal arrival capacity.
     * The routed stack is reduced to the amount that could not be accepted.
     */
    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        if (!(info instanceof PatternTargetInformation) || item == null || item.getStackSize() <= 0) {
            debugEvent("FLOW", "arrival without pattern target item=%s info=%s", item, info);
            if (item != null && item.getStackSize() > 0) {
                FluidStack fluid = SimpleServiceLocator.logisticsFluidManager.getFluidFromContainer(item);
                int patternSlot = fluid != null ? findFluidArrivalPattern(FluidIdentifier.get(fluid))
                        : findItemArrivalPattern(item.getItem());
                if (patternSlot >= 0) {
                    itemArrived(item, new PatternTargetInformation(patternSlot));
                }
            }
            return;
        }
        int patternSlot = ((PatternTargetInformation) info).patternSlot();
        FluidStack fluid = SimpleServiceLocator.logisticsFluidManager.getFluidFromContainer(item);
        if (shouldRouteCancelledArrivalToStorage(patternSlot, item, fluid)) {
            sendArrivedIngredientToStorage(patternSlot, item, fluid);
            return;
        }
        ItemStack pattern = getPatternStack(patternSlot);
        if (fluid != null) {
            fluidArrived(patternSlot, pattern, item, fluid);
            return;
        }
        if (pattern == null || !patternContains(pattern, item.getItem())) {
            debugEvent(
                    "FLOW",
                    "item arrival rejected slot=%d item=%s pattern=%s contains=%s",
                    patternSlot,
                    item,
                    pattern,
                    pattern != null && patternContains(pattern, item.getItem()));
            return;
        }

        int original = item.getStackSize();
        int requested = requestedItemAmount(patternSlot, pattern, item.getItem());
        int space = spaceForArrivingIngredient(patternSlot, pattern, item.getItem());
        int accepted = Math.min(original, Math.max(requested, space));

        debugEvent(
                "FLOW",
                "item arrived slot=%d item=%s original=%d requested=%d space=%d accepted=%d",
                patternSlot,
                item.getItem(),
                original,
                requested,
                space,
                accepted);
        removeRequestedItem(patternSlot, pattern, item.getItem(), accepted);
        int requestedAfter = requestedItemAmount(patternSlot, pattern, item.getItem());
        if (accepted > 0) {
            ingredientBuffer.add(patternSlot, new PatternItemStack(new ItemIdentifierStack(item.getItem(), accepted)));
            debugEvent(
                    "BUFFER",
                    "item buffered slot=%d item=%s accepted=%d requested=%d->%d buffered=%d completeSets=%d",
                    patternSlot,
                    item.getItem(),
                    accepted,
                    requested,
                    requestedAfter,
                    matchingBufferedItemAmount(patternSlot, pattern, item.getItem()),
                    completeBufferedSets(patternSlot));
            activateRunningCraftFromBuffer(patternSlot);
            pushBufferedIngredientsFor(patternSlot);
        }
        item.setStackSize(original - accepted);
        if (accepted > 0) {
            pipe.getCacheHolder().trigger(CacheTypes.Inventory);
        }
    }

    private boolean shouldRouteCancelledArrivalToStorage(int patternSlot, ItemIdentifierStack item, FluidStack fluid) {
        if (!cancelledPatternSlots.contains(patternSlot) || stagedRemainingSets(patternSlot) > 0) {
            return false;
        }
        if (fluid != null) {
            return requestedIngredient.amount(patternSlot, FluidIdentifier.get(fluid)) <= 0;
        }
        return requestedItemAmount(patternSlot, getPatternStack(patternSlot), item.getItem()) <= 0;
    }

    private void sendArrivedIngredientToStorage(int patternSlot, ItemIdentifierStack item, FluidStack fluid) {
        debugEvent(
                "FLOW",
                "cancelled slot=%d sends late ingredient to storage item=%s fluid=%s amount=%d",
                patternSlot,
                item.getItem(),
                fluid == null ? "<none>" : FluidIdentifier.get(fluid),
                fluid == null ? item.getStackSize() : fluid.amount);
        pipe.sendStack(item.makeNormalStack(), -1, CoreRoutedPipe.ItemSendMode.Normal, null);
        item.setStackSize(0);
        pipe.getCacheHolder().trigger(CacheTypes.Inventory);
    }

    /**
     * Accepts a routed fluid container as a buffered fluid ingredient for one pattern slot.
     * <p>
     * The routed item is consumed only when the full fluid amount can fit in the staged buffer. Partial acceptance
     * would split the opaque LogisticsFluidContainer item and lose the exact routed-fluid accounting.
     */
    private void fluidArrived(int patternSlot, ItemStack pattern, ItemIdentifierStack routedStack,
            FluidStack fluidStack) {
        FluidIdentifier fluid = FluidIdentifier.get(fluidStack);
        if (pattern == null || patternHandler.fluidIngredientAmount(pattern, fluid) <= 0) {
            debugEvent(
                    "FLOW",
                    "fluid arrival rejected slot=%d fluid=%s amount=%d pattern=%s",
                    patternSlot,
                    fluid,
                    fluidStack == null ? 0 : fluidStack.amount,
                    pattern);
            return;
        }

        int original = fluidStack.amount;
        int requested = requestedIngredient.amount(patternSlot, fluid);
        int space = Math.max(requested, spaceForArrivingFluidIngredient(patternSlot, pattern, fluid));
        int accepted = space >= original ? original : 0;
        debugEvent(
                "FLOW",
                "fluid arrived slot=%d fluid=%s original=%d requested=%d space=%d accepted=%d",
                patternSlot,
                fluid,
                original,
                requested,
                space,
                accepted);
        requestedIngredient.remove(patternSlot, new PatternFluidStack(fluid, accepted));
        int requestedAfter = requestedIngredient.amount(patternSlot, fluid);
        if (accepted > 0) {
            ingredientBuffer.add(patternSlot, new PatternFluidStack(fluid, accepted));
            debugEvent(
                    "BUFFER",
                    "fluid buffered slot=%d fluid=%s accepted=%d requested=%d->%d buffered=%d completeSets=%d",
                    patternSlot,
                    fluid,
                    accepted,
                    requested,
                    requestedAfter,
                    ingredientBuffer.amount(patternSlot, fluid),
                    completeBufferedSets(patternSlot));
            activateRunningCraftFromBuffer(patternSlot);
            pushBufferedIngredientsFor(patternSlot);
            routedStack.setStackSize(0);
            pipe.getCacheHolder().trigger(CacheTypes.Inventory);
        }
    }

    /**
     * Finds the best pattern slot for a fluid container that arrived without explicit pattern target information.
     * <p>
     * Requested fluid ingredients win over general capacity so rerouted or retried fluids complete the craft that asked
     * for them first.
     */
    private int findFluidArrivalPattern(FluidIdentifier fluid) {
        int fallback = -1;
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            ItemStack pattern = patternHandler.getConfiguredPatternStack(slot);
            if (pattern == null || patternHandler.fluidIngredientAmount(pattern, fluid) <= 0) {
                continue;
            }
            if (requestedIngredient.amount(slot, fluid) > 0) {
                return slot;
            }
            if (fallback < 0 && canReceiveForPattern(slot)
                    && spaceForPatternFluidIngredient(slot, pattern, fluid) > 0) {
                fallback = slot;
            }
        }
        debug("fluid arrival pattern lookup fluid=%s selected=%d", fluid, fallback);
        return fallback;
    }

    /**
     * Finds the best pattern slot for a routed item whose target information was not available after loading.
     * <p>
     * Saved requested-ingredient state wins so in-flight items from before a world stop keep completing the craft that
     * reserved them. Capacity fallback is used only when exactly one pattern can accept the item, avoiding ambiguous
     * multi-pattern ingredients being consumed by the wrong craft.
     */
    private int findItemArrivalPattern(ItemIdentifier item) {
        int requestedSlot = -1;
        int fallback = -1;
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            ItemStack pattern = patternHandler.getConfiguredPatternStack(slot);
            if (pattern == null || !patternContains(pattern, item)) {
                continue;
            }
            if (requestedItemAmount(slot, pattern, item) > 0) {
                if (requestedSlot >= 0) {
                    debug("item arrival pattern lookup item=%s ambiguous requested slots", item);
                    return -1;
                }
                requestedSlot = slot;
                continue;
            }
            if (canReceiveForPattern(slot) && spaceForPatternIngredient(slot, pattern, item) > 0) {
                if (fallback >= 0) {
                    debug("item arrival pattern lookup item=%s ambiguous", item);
                    return -1;
                }
                fallback = slot;
            }
        }
        if (requestedSlot >= 0) {
            debug("item arrival pattern lookup item=%s selected requested slot=%d", item, requestedSlot);
            return requestedSlot;
        }
        debug("item arrival pattern lookup item=%s selected=%d", item, fallback);
        return fallback;
    }

    /**
     * Calculates how many arriving items can be consumed for one pattern before the transport layer treats them as
     * leftovers. This is intentionally separate from {@link #spaceFor(ItemIdentifier, boolean)} because arriving items
     * can complete a blocking-mode buffer that will be pushed as soon as the connected inventory becomes empty.
     */
    private int spaceForArrivingIngredient(int patternSlot, ItemStack pattern, ItemIdentifier item) {
        int space = spaceForPatternIngredient(patternSlot, pattern, item);
        AdjacentTile connected = adjacentInventory.getConnected();
        if (getEffectiveBlockingMode() == PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING && connected != null
                && adjacentInventory.isEmpty(connected)
                && buildBufferedIngredientPlanAfterAdding(
                        patternSlot,
                        pattern,
                        1,
                        new PatternItemStack(new ItemIdentifierStack(item, space))) != null) {
            space += ingredientAmount(pattern, item);
        }
        debug("arrival item space slot=%d item=%s space=%d", patternSlot, item, space);
        return space;
    }

    /**
     * Calculates whether an arriving fluid container can be accepted for a pattern slot.
     * <p>
     * Fluid containers are accepted all-or-nothing, but blocking mode can reserve one extra craft set when all solid
     * ingredients for that set are already buffered.
     */
    private int spaceForArrivingFluidIngredient(int patternSlot, ItemStack pattern, FluidIdentifier fluid) {
        int space = spaceForPatternFluidIngredient(patternSlot, pattern, fluid);
        AdjacentTile connected = adjacentInventory.getConnected();
        if (getEffectiveBlockingMode() == PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING && connected != null
                && adjacentInventory.isEmpty(connected)
                && buildBufferedIngredientPlanAfterAdding(patternSlot, pattern, 1, new PatternFluidStack(fluid, space))
                        != null
                && itemIngredientsBufferedForOneSet(patternSlot, pattern)) {
            space += patternHandler.fluidIngredientAmount(pattern, fluid);
        }
        debug("arrival fluid space slot=%d fluid=%s space=%d", patternSlot, fluid, space);
        return space;
    }

    /**
     * Checks whether every local item ingredient for one set is already buffered.
     * <p>
     * This is used before accepting an extra blocking-mode fluid set so a fluid-only buffer cannot start a craft
     * without its matching solid ingredients.
     */
    private boolean itemIngredientsBufferedForOneSet(int patternSlot, ItemStack pattern) {
        for (PatternIngredientTarget ingredient : getIngredientTargets(pattern)) {
            if (!PatternStackHelper.isSolid(ingredient.stack())) {
                continue;
            }
            if (bufferedIngredientAmount(patternSlot, pattern, ingredient.stack()) < ingredient.stack().getAmount()) {
                return false;
            }
        }
        return true;
    }

    protected ISlotUpgradeManager getUpgradeManager() {
        if (_service == null) {
            return null;
        }
        return _service.getUpgradeManager(slot, positionInt);
    }

    ForgeDirection getInsertionOrientation(AdjacentTile tile) {
        ForgeDirection insertion = tile.orientation;
        if (getUpgradeManager().hasSneakyUpgrade()) {
            insertion = getUpgradeManager().getSneakyOrientation();
        }
        return insertion;
    }

    int getRunningCraftForHandler() {
        return runningCraft;
    }

    /**
     * Returns the number of items this module can still sink for any configured pattern using the item as an
     * ingredient.
     * <p>
     * Requested ingredients reserve module buffer space for in-flight staged crafts. They remain sinkable even if the
     * adjacent inventory cannot accept another full pattern set yet; otherwise a subrequest from the same pipe can be
     * sent away as a lost item and recursively ask this method again through storage.
     */
    private int spaceFor(ItemIdentifier item, boolean includeInTransit) {
        int count = 0;
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            ItemStack pattern = patternHandler.getConfiguredPatternStack(slot);
            if (pattern == null || !isPatternCraftingSupported(pattern) || ingredientAmount(pattern, item) <= 0) {
                continue;
            }
            int requested = requestedItemAmount(slot, pattern, item);
            if (requested > 0) {
                count = Math.max(count, requested);
            }
            if (!canReceiveForPattern(slot)) {
                continue;
            }
            count = Math.max(count, spaceForPatternIngredient(slot, pattern, item) - requested);
        }
        if (includeInTransit) {
            count -= pipe.countOnRoute(item);
        }
        return Math.max(0, count);
    }

    /**
     * Calculates how much of a fluid ingredient this module can currently sink.
     * <p>
     * This mirrors item capacity but measures millibuckets in the module's pattern buffer instead of item stack counts.
     */
    private int spaceForFluid(FluidIdentifier fluid, boolean includeInTransit) {
        // TODO include in transit
        int count = 0;
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            ItemStack pattern = patternHandler.getConfiguredPatternStack(slot);
            if (pattern == null || !isPatternCraftingSupported(pattern)
                    || patternHandler.fluidIngredientAmount(pattern, fluid) <= 0) {
                continue;
            }
            int requested = requestedIngredient.amount(slot, fluid);
            if (requested > 0) {
                count = Math.max(count, requested);
            }
            if (!canReceiveForPattern(slot)) {
                continue;
            }
            count = Math.max(count, spaceForPatternFluidIngredient(slot, pattern, fluid) - requested);
        }
        return count;
    }

    /**
     * Returns whether a pattern slot is currently allowed to receive more local ingredients.
     * <p>
     * Blocking modes restrict buffering to the active craft or to an empty connected inventory.
     */
    boolean canReceiveForPattern(int patternSlot) {
        if (!isPatternCraftingSupported(patternHandler.getConfiguredPatternStack(patternSlot))) {
            return false;
        }
        refreshSatelliteDispatchBatch();
        if (activeSatelliteBatch != null) {
            return false;
        }
        PipeItemsPatternCraftingLogistics.BlockingMode mode = getEffectiveBlockingMode();
        if (mode == PipeItemsPatternCraftingLogistics.BlockingMode.OFF) {
            return true;
        }
        if (isRunningCraftLocked()) {
            return runningCraft == patternSlot;
        }
        AdjacentTile connected = adjacentInventory.getConnected();
        return connected == null || adjacentInventory.isEmpty(connected);
    }

    /**
     * Calculates item ingredient capacity for one pattern slot, including the number of sets that fit in the adjacent
     * inventory for non-blocking modes.
     */
    private int spaceForPatternIngredient(int patternSlot, ItemStack pattern, ItemIdentifier item) {
        int sets = 1;
        if (getEffectiveBlockingMode() != PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING) {
            sets += adjacentInventory.availablePatternSets(pattern);
        }
        int capacity = sets * ingredientAmount(pattern, item);
        int room = Math.max(0, capacity - matchingBufferedItemAmount(patternSlot, pattern, item));
        int result = maxAcceptedItemAmount(patternSlot, pattern, item, room);
        debug(
                "pattern item capacity slot=%d item=%s sets=%d capacity=%d buffered=%d room=%d",
                patternSlot,
                item,
                sets,
                capacity,
                matchingBufferedItemAmount(patternSlot, pattern, item),
                result);
        return result;
    }

    /**
     * Restricts the amount accepted for flexible-match patterns to a concrete stack count that can still be assigned to
     * a complete recipe set.
     * <p>
     * Exact recipes can accept partial sets freely because every buffered stack already maps to a single input. OreDict
     * and ignore-NBT recipes need the additional check so mixed alternatives are not merged into an unusable buffer.
     */
    private int maxAcceptedItemAmount(int patternSlot, ItemStack pattern, ItemIdentifier item, int room) {
        if (!requiresConcreteIngredientPlanning(pattern)) {
            return room;
        }
        for (int amount = room; amount > 0; amount--) {
            if (buildBufferedIngredientPlanAfterAdding(
                    patternSlot,
                    pattern,
                    1,
                    new PatternItemStack(new ItemIdentifierStack(item, amount))) != null) {
                return amount;
            }
        }
        return 0;
    }

    /**
     * Returns whether this pattern can accept multiple concrete items for the same displayed ingredient.
     */
    private boolean requiresConcreteIngredientPlanning(ItemStack pattern) {
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        return configuredPattern.isOreDictSubstitutionEnabled() || configuredPattern.isIgnoreNbtEnabled();
    }

    /**
     * Calculates fluid ingredient capacity for one pattern slot in millibuckets.
     */
    private int spaceForPatternFluidIngredient(int patternSlot, ItemStack pattern, FluidIdentifier fluid) {
        int sets = 1;
        if (getEffectiveBlockingMode() != PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING) {
            sets += adjacentInventory.availablePatternSets(pattern);
        }
        int capacity = sets * patternHandler.fluidIngredientAmount(pattern, fluid);
        int result = Math.max(0, capacity - ingredientBuffer.amount(patternSlot, fluid));
        debug(
                "pattern fluid capacity slot=%d fluid=%s sets=%d capacity=%d buffered=%d room=%d",
                patternSlot,
                fluid,
                sets,
                capacity,
                ingredientBuffer.amount(patternSlot, fluid),
                result);
        return result;
    }

    /**
     * Dispatches capacity checks for item and fluid pattern ingredients.
     */
    int spaceForPatternIngredient(int patternSlot, ItemStack pattern, IPatternStack ingredient) {
        ItemIdentifierStack solid = PatternStackHelper.asSolidStack(ingredient);
        if (solid != null) {
            return spaceForPatternIngredient(patternSlot, pattern, solid.getItem());
        }
        FluidIdentifier fluid = PatternStackHelper.asFluid(ingredient);
        if (fluid != null) {
            return spaceForPatternFluidIngredient(patternSlot, pattern, fluid);
        }
        return 0;
    }

    private boolean patternContains(ItemStack pattern, ItemIdentifier item) {
        return ingredientAmount(pattern, item) > 0;
    }

    /**
     * Returns all ingredients that have to be buffered by this crafting pipe before one or more pattern sets can be
     * dispatched to the local adjacent target and any configured satellites.
     */
    List<IPatternStack> getAggregatedIngredients(ItemStack pattern) {
        List<IPatternStack> result = new ArrayList<>();
        for (PatternIngredientTarget target : getIngredientTargets(pattern)) {
            PatternStackHelper.addAggregated(result, target.stack());
        }
        return result;
    }

    /**
     * Returns the non-satellite ingredients that are inserted into this crafting pipe's adjacent target.
     */
    List<IPatternStack> getLocalAggregatedIngredients(ItemStack pattern) {
        List<IPatternStack> result = new ArrayList<>();
        for (PatternIngredientTarget target : getLocalIngredientTargets(pattern)) {
            PatternStackHelper.addAggregated(result, target.stack());
        }
        return result;
    }

    /**
     * Returns the local input slots that have to be buffered by this pipe without merging equal-looking ingredients.
     */
    private List<PatternIngredientTarget> getLocalIngredientTargets(ItemStack pattern) {
        List<PatternIngredientTarget> result = new ArrayList<>();
        if (pattern == null) {
            return result;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        for (int slot = 0; slot < configuredPattern.getIngredientSlotCount(); slot++) {
            IPatternStack stack = configuredPattern.getPatternStackInSlot(slot);
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            if (PatternStackHelper.isSolid(stack) && getSatelliteTargetForInputSlot(configuredPattern, slot) != null) {
                continue;
            }
            if (PatternStackHelper.isFluid(stack)
                    && getFluidSatelliteTargetForInputSlot(configuredPattern, slot) != null) {
                continue;
            }
            result.add(new PatternIngredientTarget(slot, stack.copy(), null, null));
        }
        return result;
    }

    /**
     * Resolves the satellite destination for an item ingredient in a pattern.
     * <p>
     * If duplicate input slots contain the same item and only some are assigned to satellites, this method reports the
     * first assigned target. Dispatch still happens per input slot after the main pipe has buffered a complete set.
     */
    IRequestItems getSatelliteTargetForIngredient(ItemStack pattern, ItemIdentifier item) {
        if (pattern == null || item == null) {
            return null;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        for (int slot = 0; slot < configuredPattern.getIngredientSlotCount(); slot++) {
            IPatternStack stack = configuredPattern.getPatternStackInSlot(slot);
            if (!(stack instanceof PatternItemStack) || !ingredientMatchesItem(configuredPattern, stack, item)) {
                continue;
            }
            IRequestItems target = getSatelliteTargetForInputSlot(configuredPattern, slot);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /**
     * Builds ingredient request groups per pattern input slot.
     * <p>
     * Ore dictionary substitutions may choose a different concrete item for each input slot, so solid ingredients must
     * not be merged across slots even when their pattern stacks look equivalent.
     */
    List<PatternIngredientTarget> getIngredientTargets(ItemStack pattern) {
        List<PatternIngredientTarget> result = new ArrayList<>();
        if (pattern == null) {
            return result;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        for (int slot = 0; slot < configuredPattern.getIngredientSlotCount(); slot++) {
            IPatternStack stack = configuredPattern.getPatternStackInSlot(slot);
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            IRequestItems itemTarget = PatternStackHelper.isSolid(stack)
                    ? getSatelliteTargetForInputSlot(configuredPattern, slot)
                    : null;
            IRequestFluid fluidTarget = PatternStackHelper.isFluid(stack)
                    ? getFluidSatelliteTargetForInputSlot(configuredPattern, slot)
                    : null;
            result.add(new PatternIngredientTarget(slot, stack.copy(), itemTarget, fluidTarget));
        }
        return result;
    }

    /**
     * Counts how much of an item ingredient the full pattern needs, including satellite-assigned input slots.
     */
    private int ingredientAmount(ItemStack pattern, ItemIdentifier item) {
        int amount = 0;
        for (PatternIngredientTarget ingredient : getIngredientTargets(pattern)) {
            if (ingredientMatchesItem(pattern, ingredient.stack(), item)) {
                amount += ingredient.stack().getAmount();
            }
        }
        return amount;
    }

    /**
     * Counts how much of an item ingredient still belongs to the local connected inventory after satellite assignments.
     */
    int localIngredientAmount(ItemStack pattern, ItemIdentifier item) {
        int amount = 0;
        for (PatternIngredientTarget ingredient : getLocalIngredientTargets(pattern)) {
            if (ingredientMatchesItem(pattern, ingredient.stack(), item)) {
                amount += ingredient.stack().getAmount();
            }
        }
        return amount;
    }

    private boolean ingredientMatchesItem(ItemStack pattern, IPatternStack ingredient, ItemIdentifier item) {
        return ingredientMatchesItem(ItemPattern.fromStack(pattern), ingredient, item);
    }

    private boolean ingredientMatchesItem(AbstractPattern pattern, IPatternStack ingredient, ItemIdentifier item) {
        ItemIdentifierStack expected = PatternStackHelper.asSolidStack(ingredient);
        if (expected == null || item == null) {
            return false;
        }
        return itemMatchesPatternIngredient(pattern, expected.getItem(), item);
    }

    private boolean itemMatchesPatternIngredient(AbstractPattern pattern, ItemIdentifier expected,
            ItemIdentifier actual) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.equals(actual)) {
            return true;
        }
        if (pattern != null && pattern.isIgnoreNbtEnabled() && expected.equalsWithoutNBT(actual)) {
            return true;
        }
        return pattern != null && pattern.isOreDictSubstitutionEnabled()
                && expected.getDictIdentifiers() != null
                && actual.getDictIdentifiers() != null
                && expected.getDictIdentifiers().canMatch(actual.getDictIdentifiers(), true, false);
    }

    private boolean ingredientMatchesStack(ItemStack pattern, IPatternStack ingredient, IPatternStack buffered) {
        ItemIdentifierStack item = PatternStackHelper.asSolidStack(buffered);
        if (item != null) {
            return ingredientMatchesItem(pattern, ingredient, item.getItem());
        }
        FluidIdentifier fluid = PatternStackHelper.asFluid(buffered);
        return fluid != null && PatternStackHelper.matches(ingredient, fluid);
    }

    private int bufferedIngredientAmount(int patternSlot, ItemStack pattern, IPatternStack ingredient) {
        return matchingBufferedAmount(patternSlot, pattern, ingredient);
    }

    /**
     * Counts in-flight local ingredients using the matching rules stored on the pattern.
     */
    int requestedIngredientAmount(int patternSlot, ItemStack pattern, IPatternStack ingredient) {
        return requestedIngredient
                .amountMatching(patternSlot, requested -> ingredientMatchesStack(pattern, ingredient, requested));
    }

    private int requestedItemAmount(int patternSlot, ItemStack pattern, ItemIdentifier item) {
        return requestedIngredient.amountMatching(patternSlot, requested -> {
            ItemIdentifierStack requestedItem = PatternStackHelper.asSolidStack(requested);
            return requestedItem != null && patternContains(pattern, requestedItem.getItem())
                    && patternContains(pattern, item)
                    && itemMatchesPatternIngredient(ItemPattern.fromStack(pattern), requestedItem.getItem(), item);
        });
    }

    private void removeRequestedItem(int patternSlot, ItemStack pattern, ItemIdentifier item, int amount) {
        requestedIngredient.removeMatching(patternSlot, amount, requested -> {
            ItemIdentifierStack requestedItem = PatternStackHelper.asSolidStack(requested);
            return requestedItem != null && patternContains(pattern, requestedItem.getItem())
                    && patternContains(pattern, item)
                    && itemMatchesPatternIngredient(ItemPattern.fromStack(pattern), requestedItem.getItem(), item);
        });
    }

    private int matchingBufferedAmount(int patternSlot, ItemStack pattern, IPatternStack ingredient) {
        int amount = 0;
        List<IPatternStack> buffered = ingredientBuffer.asMap().get(patternSlot);
        if (buffered == null) {
            return 0;
        }
        for (IPatternStack stack : buffered) {
            if (ingredientMatchesStack(pattern, ingredient, stack)) {
                amount += stack.getAmount();
            }
        }
        return amount;
    }

    private int matchingBufferedItemAmount(int patternSlot, ItemStack pattern, ItemIdentifier item) {
        int amount = 0;
        List<IPatternStack> buffered = ingredientBuffer.asMap().get(patternSlot);
        if (buffered == null) {
            return 0;
        }
        for (IPatternStack stack : buffered) {
            ItemIdentifierStack bufferedItem = PatternStackHelper.asSolidStack(stack);
            if (bufferedItem == null) {
                continue;
            }
            if (patternContains(pattern, bufferedItem.getItem()) && patternContains(pattern, item)
                    && itemMatchesPatternIngredient(ItemPattern.fromStack(pattern), bufferedItem.getItem(), item)) {
                amount += stack.getAmount();
            }
        }
        return amount;
    }

    private List<PatternIngredientAssignment> buildBufferedIngredientPlan(int patternSlot, ItemStack pattern,
            int sets) {
        return buildBufferedIngredientPlan(patternSlot, pattern, getIngredientTargets(pattern), sets, null);
    }

    private List<PatternIngredientAssignment> buildBufferedIngredientPlanAfterAdding(int patternSlot, ItemStack pattern,
            int sets, IPatternStack arrivingStack) {
        return buildBufferedIngredientPlan(patternSlot, pattern, getIngredientTargets(pattern), sets, arrivingStack);
    }

    private List<PatternIngredientAssignment> buildBufferedIngredientPlan(int patternSlot, ItemStack pattern,
            List<PatternIngredientTarget> ingredients, int sets, IPatternStack extraStack) {
        if (sets <= 0 || ingredients.isEmpty()) {
            return Collections.emptyList();
        }
        List<IPatternStack> available = copyBufferedIngredients(patternSlot);
        if (extraStack != null && extraStack.getAmount() > 0) {
            PatternStackHelper.addAggregated(available, extraStack);
        }
        List<PatternIngredientAssignment> assignments = new ArrayList<>();
        for (PatternIngredientTarget ingredient : ingredients) {
            int amount = ingredient.stack().getAmount() * sets;
            IPatternStack selected = takeMatchingStack(pattern, available, ingredient.stack(), amount);
            if (selected == null) {
                return null;
            }
            assignments.add(new PatternIngredientAssignment(ingredient.inputSlot(), selected));
        }
        return assignments;
    }

    private List<IPatternStack> copyBufferedIngredients(int patternSlot) {
        List<IPatternStack> result = new ArrayList<>();
        List<IPatternStack> buffered = ingredientBuffer.asMap().get(patternSlot);
        if (buffered == null) {
            return result;
        }
        for (IPatternStack stack : buffered) {
            if (stack != null && stack.getAmount() > 0) {
                result.add(stack.copy());
            }
        }
        return result;
    }

    private IPatternStack takeMatchingStack(ItemStack pattern, List<IPatternStack> available, IPatternStack ingredient,
            int amount) {
        for (int i = 0; i < available.size(); i++) {
            IPatternStack candidate = available.get(i);
            if (!ingredientMatchesStack(pattern, ingredient, candidate) || candidate.getAmount() < amount) {
                continue;
            }
            IPatternStack selected = PatternStackHelper.copyWithAmount(candidate, amount);
            candidate.addAmount(-amount);
            if (candidate.getAmount() <= 0) {
                available.remove(i);
            }
            return selected;
        }
        return null;
    }

    /**
     * Checks whether one input slot is assigned to a linked and currently known pattern satellite pipe.
     */
    boolean hasLinkedSatelliteAssignment(ItemStack pattern, int inputSlot) {
        if (pattern == null) {
            return false;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        IPatternStack stack = configuredPattern.getPatternStackInSlot(inputSlot);
        if (PatternStackHelper.isFluid(stack)) {
            return getFluidSatelliteTargetForInputSlot(configuredPattern, inputSlot) != null;
        }
        return getSatelliteTargetForInputSlot(configuredPattern, inputSlot) != null;
    }

    /**
     * Returns true when at least one input slot of this pattern is routed to a linked pattern satellite.
     */
    boolean hasLinkedSatelliteAssignments(ItemStack pattern) {
        if (pattern == null) {
            return false;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        for (int slot = 0; slot < configuredPattern.getIngredientSlotCount(); slot++) {
            IPatternStack stack = configuredPattern.getPatternStackInSlot(slot);
            if (PatternStackHelper.isSolid(stack) && getSatelliteTargetForInputSlot(configuredPattern, slot) != null) {
                return true;
            }
            if (PatternStackHelper.isFluid(stack)
                    && getFluidSatelliteTargetForInputSlot(configuredPattern, slot) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the linked pattern satellite assigned to one solid input slot.
     */
    private IRequestItems getSatelliteTargetForInputSlot(AbstractPattern pattern, int inputSlot) {
        if (adjacentInventory.isConnectedToPatternCraftingTable()) {
            return null;
        }
        int satelliteId = pattern.getSatelliteIdForInputSlot(inputSlot);
        String satelliteUuid = pattern.getSatelliteUuidForInputSlot(inputSlot);
        return pipe.resolvePatternSatelliteTarget(satelliteUuid, satelliteId);
    }

    /**
     * Resolves the linked pattern fluid satellite assigned to one fluid input slot.
     */
    private IRequestFluid getFluidSatelliteTargetForInputSlot(AbstractPattern pattern, int inputSlot) {
        if (adjacentInventory.isConnectedToPatternCraftingTable()) {
            return null;
        }
        int satelliteId = pattern.getFluidSatelliteIdForInputSlot(inputSlot);
        String satelliteUuid = pattern.getFluidSatelliteUuidForInputSlot(inputSlot);
        return pipe.resolvePatternFluidSatelliteTarget(satelliteUuid, satelliteId);
    }

    /**
     * Attempts to push complete buffered pattern sets into the selected adjacent inventory or fluid handler.
     * <p>
     * Blocking modes keep one active pattern slot locked only while arrived ingredients are still buffered or while the
     * adjacent target is processing a batch inserted by that slot.
     */
    private void pushBufferedIngredients() {
        refreshSatelliteDispatchBatch();
        if (activeSatelliteBatch != null) {
            debugEventThrottled(
                    "BUFFER",
                    40,
                    "push skipped: satellite batch active slot=%d",
                    activeSatelliteBatch.patternSlot);
            return;
        }
        AdjacentTile connected = getConnectedInventoryTile();
        if (connected == null) {
            debugEventThrottled(
                    "BUFFER",
                    "push skipped: no connected inventory bufferedSlots=%d",
                    ingredientBuffer.size());
            return;
        }
        PipeItemsPatternCraftingLogistics.BlockingMode mode = getEffectiveBlockingMode();
        debug("push tick mode=%s runningCraft=%d bufferedSlots=%d", mode, runningCraft, ingredientBuffer.size());
        if (mode == PipeItemsPatternCraftingLogistics.BlockingMode.OFF) {
            for (int patternSlot : new ArrayList<>(ingredientBuffer.asMap().keySet())) {
                if (completeBufferedSets(patternSlot) <= 0) continue;
                pushBufferedIngredientsFor(patternSlot);
            }
            return;
        }
        refreshRunningCraftState(connected);
        if (runningCraft >= 0) {
            pushBufferedIngredientsFor(runningCraft);
        }
    }

    /**
     * Pushes complete buffered sets for one pattern slot into the connected crafting target.
     */
    private void pushBufferedIngredientsFor(int patternSlot) {
        ItemStack pattern = getPatternStack(patternSlot);
        if (pattern == null) {
            debugEvent("BUFFER", "push slot=%d dropped buffer: pattern missing", patternSlot);
            ingredientBuffer.removeAll(patternSlot);
            return;
        }
        PipeItemsPatternCraftingLogistics.BlockingMode mode = getEffectiveBlockingMode();
        if (mode != PipeItemsPatternCraftingLogistics.BlockingMode.OFF && isRunningCraftLocked()
                && runningCraft != patternSlot) {
            debugEventThrottled(
                    "BUFFER",
                    "push slot=%d skipped: running craft locked by slot=%d",
                    patternSlot,
                    runningCraft);
            return;
        }
        AdjacentTile connected = adjacentInventory.getConnected();
        if (mode == PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING && !adjacentInventory.isEmpty(connected)) {
            debugEventThrottled(
                    "BUFFER",
                    "push slot=%d skipped: blocking mode and adjacent inventory not empty",
                    patternSlot);
            return;
        }
        int bufferedSets = completeBufferedSets(patternSlot);
        int insertableSets = mode == PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING ? 1 : bufferedSets;
        if (mode == PipeItemsPatternCraftingLogistics.BlockingMode.BLOCKING) {
            insertableSets = Math.min(insertableSets, 1);
        }
        int sets = Math.min(bufferedSets, insertableSets);
        PatternDispatchPlan plan = findInsertableBufferedPlan(patternSlot, pattern, sets);
        if (plan == null || !plan.dispatch()) {
            debugEventThrottled(
                    "BUFFER",
                    "push slot=%d failed: bufferedSets=%d insertableSets=%d selectedSets=%d",
                    patternSlot,
                    bufferedSets,
                    insertableSets,
                    sets);
            return;
        }
        sets = insertedSetsFromPlan(pattern, plan.assignments());
        debugEvent(
                "BUFFER",
                "push slot=%d inserted sets=%d bufferedSets=%d insertableSets=%d",
                patternSlot,
                sets,
                bufferedSets,
                insertableSets);
        removeBufferedPlan(patternSlot, plan.assignments());
        if (plan.hasSatellites()) {
            activeSatelliteBatch = plan.createSatelliteBatch(patternSlot);
            setRunningCraft(patternSlot, true);
        } else if (mode != PipeItemsPatternCraftingLogistics.BlockingMode.OFF) {
            setRunningCraft(patternSlot, true);
        }
        debugEvent(
                "BUFFER",
                "push slot=%d buffer after insert remainingSets=%d runningCraft=%d adjacentBatch=%s",
                patternSlot,
                completeBufferedSets(patternSlot),
                runningCraft,
                runningCraftInAdjacent);
        requestIngredientsForStagedCrafts();
    }

    private PatternDispatchPlan findInsertableBufferedPlan(int patternSlot, ItemStack pattern, int maxSets) {
        for (int sets = maxSets; sets > 0; sets--) {
            List<PatternIngredientAssignment> assignments = buildBufferedIngredientPlan(patternSlot, pattern, sets);
            if (assignments == null) {
                continue;
            }
            PatternDispatchPlan dispatchPlan = buildDispatchPlan(pattern, assignments);
            if (dispatchPlan != null && dispatchPlan.canDispatch()) {
                return dispatchPlan;
            }
        }
        return null;
    }

    private PatternDispatchPlan buildDispatchPlan(ItemStack pattern, List<PatternIngredientAssignment> assignments) {
        if (pattern == null || assignments == null || assignments.isEmpty()) {
            return null;
        }
        PatternDispatchPlan plan = new PatternDispatchPlan(pattern, assignments);
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        for (PatternIngredientAssignment assignment : assignments) {
            IPatternStack configuredStack = configuredPattern.getPatternStackInSlot(assignment.inputSlot());
            ItemIdentifierStack item = PatternStackHelper.asSolidStack(assignment.stack());
            if (item != null) {
                IRequestItems target = PatternStackHelper.isSolid(configuredStack)
                        ? getSatelliteTargetForInputSlot(configuredPattern, assignment.inputSlot())
                        : null;
                if (target instanceof PipeItemsPatternSatelliteLogistics satellite) {
                    plan.addItemSatellite(satellite, item.clone());
                } else {
                    plan.addLocal(assignment);
                }
                continue;
            }
            FluidIdentifier fluid = PatternStackHelper.asFluid(assignment.stack());
            if (fluid != null) {
                IRequestFluid target = PatternStackHelper.isFluid(configuredStack)
                        ? getFluidSatelliteTargetForInputSlot(configuredPattern, assignment.inputSlot())
                        : null;
                if (target instanceof PipeFluidPatternSatelliteLogistics satellite) {
                    plan.addFluidSatellite(satellite, fluid, assignment.stack().getAmount());
                } else {
                    plan.addLocal(assignment);
                }
            }
        }
        return plan;
    }

    private int insertedSetsFromPlan(ItemStack pattern, List<PatternIngredientAssignment> plan) {
        if (pattern == null || plan == null || plan.isEmpty()) {
            return 0;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        int sets = Integer.MAX_VALUE;
        for (PatternIngredientAssignment assignment : plan) {
            IPatternStack ingredient = configuredPattern.getPatternStackInSlot(assignment.inputSlot());
            if (ingredient == null || ingredient.getAmount() <= 0) {
                continue;
            }
            sets = Math.min(sets, assignment.stack().getAmount() / ingredient.getAmount());
        }
        return sets == Integer.MAX_VALUE ? 0 : sets;
    }

    private void removeBufferedPlan(int patternSlot, List<PatternIngredientAssignment> plan) {
        for (PatternIngredientAssignment assignment : plan) {
            ingredientBuffer.remove(patternSlot, assignment.stack(), assignment.stack().getAmount());
        }
    }

    /**
     * Counts complete local ingredient sets currently buffered for one pattern slot.
     */
    private int completeBufferedSets(int patternSlot) {
        ItemStack pattern = getPatternStack(patternSlot);
        List<PatternIngredientTarget> ingredients = getIngredientTargets(pattern);
        int sets = ingredients.isEmpty() ? 0 : completeBufferedSets(patternSlot, pattern, ingredients);
        debug("complete buffered sets slot=%d ingredients=%d sets=%d", patternSlot, ingredients.size(), sets);
        return sets;
    }

    private int completeBufferedSets(int patternSlot, ItemStack pattern, List<PatternIngredientTarget> ingredients) {
        int upperBound = Integer.MAX_VALUE;
        for (PatternIngredientTarget ingredient : ingredients) {
            upperBound = Math.min(
                    upperBound,
                    bufferedIngredientAmount(patternSlot, pattern, ingredient.stack())
                            / ingredient.stack().getAmount());
        }
        if (upperBound == Integer.MAX_VALUE) {
            return 0;
        }
        for (int sets = upperBound; sets > 0; sets--) {
            if (buildBufferedIngredientPlan(patternSlot, pattern, sets) != null) {
                return sets;
            }
        }
        return 0;
    }

    /**
     * Requests ingredients for all staged crafting orders that still have room in this module or the adjacent
     * inventory.
     */
    void requestIngredientsForStagedCrafts() {
        debugEventThrottled(
                "SCHED",
                20,
                "request staged crafts trigger bufferedSlots=%d requestedSlots=%d runningCraft=%d adjacentBatch=%s",
                ingredientBuffer.size(),
                requestedIngredients.size(),
                runningCraft,
                runningCraftInAdjacent);
        stagedCrafting.requestIngredients();
    }

    /**
     * Cancels the running request tree that contains {@code patternSlot}.
     * <p>
     * A selected pattern can be a middle node in a larger staged recipe. The staged coordinator resolves that tree and
     * returns every affected pattern slot so local buffers and requested ingredients are cleaned up consistently.
     */
    public boolean cancelPatternCraft(int patternSlot) {
        if (patternSlot < 0 || patternSlot >= patternHandler.size()) {
            return false;
        }
        Set<Integer> slotsToCancel = new HashSet<>(stagedCrafting.cancelPattern(patternSlot));
        boolean changed = !slotsToCancel.isEmpty();
        slotsToCancel.add(patternSlot);
        if (activeSatelliteBatch != null && slotsToCancel.contains(activeSatelliteBatch.patternSlot)) {
            activeSatelliteBatch.retrieveAndRelease();
            activeSatelliteBatch = null;
            changed = true;
        }
        for (int cancelledSlot : slotsToCancel) {
            changed |= requestedIngredient.removeAll(cancelledSlot);
            changed |= flushBufferedIngredientsToStorage(cancelledSlot);
            if (runningCraft == cancelledSlot) {
                setRunningCraft(-1, false);
                changed = true;
            }
        }
        if (changed) {
            cancelledPatternSlots.addAll(slotsToCancel);
            debugEvent("CANCEL", "cancelled pattern slots=%s and flushed buffers", slotsToCancel);
            pipe.getCacheHolder().trigger(CacheTypes.Inventory);
        }
        return changed;
    }

    /**
     * Returns every input currently owned by this pattern pipe to storage.
     * <p>
     * Buffered inputs cannot be cleared independently from active staged orders: those orders have already consumed
     * request-tree capacity for the items. The method therefore cancels all active staged crafts first, then flushes
     * all local input buffers and requested-input reservations.
     */
    public boolean returnStoredInputsToStorage() {
        Set<Integer> slotsToClear = new HashSet<>();
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            slotsToClear.addAll(stagedCrafting.cancelPattern(slot));
        }
        slotsToClear.addAll(ingredientBuffer.keySet());
        slotsToClear.addAll(requestedIngredients.keySet());
        if (runningCraft >= 0) {
            slotsToClear.add(runningCraft);
        }
        if (activeSatelliteBatch != null) {
            slotsToClear.add(activeSatelliteBatch.patternSlot);
            activeSatelliteBatch.retrieveAndRelease();
            activeSatelliteBatch = null;
        }

        boolean changed = !slotsToClear.isEmpty();
        for (int slot : slotsToClear) {
            changed |= requestedIngredient.removeAll(slot);
            changed |= flushBufferedIngredientsToStorage(slot);
        }
        if (runningCraft >= 0) {
            setRunningCraft(-1, false);
            changed = true;
        }
        if (!lostIngredients.isEmpty()) {
            lostIngredients.clear();
            changed = true;
        }
        if (changed) {
            cancelledPatternSlots.addAll(slotsToClear);
            debugEvent("CANCEL", "returned stored pattern inputs slots=%s", slotsToClear);
            pipe.getCacheHolder().trigger(CacheTypes.Inventory);
        }
        return changed;
    }

    void clearCancelledPattern(int patternSlot) {
        cancelledPatternSlots.remove(patternSlot);
    }

    private boolean flushBufferedIngredientsToStorage(int patternSlot) {
        boolean sent = false;
        for (IPatternStack stack : ingredientBuffer.removeAll(patternSlot)) {
            for (ItemStack itemStack : PatternStackBufferHandler.makeItemStacks(stack)) {
                pipe.sendStack(itemStack, -1, CoreRoutedPipe.ItemSendMode.Normal, null);
                sent = true;
                debugEvent("CANCEL", "sent buffered ingredient to storage slot=%d stack=%s", patternSlot, stack);
            }
        }
        return sent;
    }

    private void refreshSatelliteDispatchBatch() {
        if (activeSatelliteBatch == null || !activeSatelliteBatch.isConsumed()) {
            return;
        }
        debugEvent(
                "BUFFER",
                "satellite batch completed slot=%d satellites=%d",
                activeSatelliteBatch.patternSlot,
                activeSatelliteBatch.size());
        activeSatelliteBatch.release();
        activeSatelliteBatch = null;
        markHudStateDirty();
    }

    /**
     * @return a pattern slot whose buffered arrived ingredients can be pushed as a complete set.
     */
    private int findCompleteBufferedPattern() {
        for (int patternSlot : ingredientBuffer.keySet()) {
            ItemStack pattern = getPatternStack(patternSlot);

            if (pattern == null) {
                ingredientBuffer.removeAll(patternSlot);
                continue;
            }

            if (completeBufferedSets(patternSlot) > 0) {
                return patternSlot;
            }
        }
        return -1;
    }

    /**
     * Marks the first arrived ingredient for a slot as the active blocking craft when no other slot is active.
     */
    private void activateRunningCraftFromBuffer(int patternSlot) {
        if (getEffectiveBlockingMode() == PipeItemsPatternCraftingLogistics.BlockingMode.OFF || runningCraft >= 0) {
            return;
        }
        setRunningCraft(patternSlot, false);
        debugEvent("BUFFER", "running craft activated from buffer slot=%d", patternSlot);
    }

    /**
     * Returns whether the active slot is still locked by arrived buffered ingredients or an inserted adjacent batch.
     */
    boolean isRunningCraftLocked() {
        refreshRunningCraftState(getConnectedInventoryTile());
        if (runningCraft < 0) {
            return false;
        }
        // if (hasBufferedIngredients(runningCraft)) {
        // return true;
        // }
        AdjacentTile connected = getConnectedInventoryTile();
        return runningCraftInAdjacent && connected != null && !isInventoryEmpty(connected);
    }

    /**
     * Releases stale blocking state and adopts already-buffered ingredients as the next active slot.
     */
    private void refreshRunningCraftState(AdjacentTile connected) {
        if (getEffectiveBlockingMode() == PipeItemsPatternCraftingLogistics.BlockingMode.OFF) {
            setRunningCraft(-1, false);
            return;
        }
        if (runningCraft >= 0 && getPatternStack(runningCraft) == null) {
            debugEvent("BUFFER", "running craft cleared slot=%d: pattern missing", runningCraft);
            setRunningCraft(-1, false);
        }
        if (runningCraft >= 0 && runningCraftInAdjacent && (connected == null || isInventoryEmpty(connected))) {
            debugEvent("BUFFER", "running craft adjacent batch finished slot=%d", runningCraft);
            setRunningCraft(runningCraft, false);
        }
        if (runningCraft >= 0 && !runningCraftInAdjacent && completeBufferedSets(runningCraft) <= 0) {
            debugEvent("BUFFER", "running craft released slot=%d: no arrived ingredients remain", runningCraft);
            setRunningCraft(-1, false);
        }
        if (runningCraft < 0) {
            setRunningCraft(-1, false);
            int next = findCompleteBufferedPattern();
            if (next != -1) {
                setRunningCraft(next, false);
                debugEvent("BUFFER", "running craft selected buffered slot=%d", runningCraft);
            }
        }
    }

    private void setRunningCraft(int patternSlot, boolean inAdjacent) {
        if (runningCraft == patternSlot && runningCraftInAdjacent == inAdjacent) {
            return;
        }
        runningCraft = patternSlot;
        runningCraftInAdjacent = patternSlot >= 0 && inAdjacent;
        markHudStateDirty();
    }

    /**
     * Refreshes blocking state once per tick after push/request processing.
     */
    private void clearRunningCraftIfFinished() {
        refreshRunningCraftState(getConnectedInventoryTile());
    }

    AdjacentTile getConnectedInventoryTile() {
        return adjacentInventory.getConnected();
    }

    private boolean isInventoryEmpty(AdjacentTile connected) {
        return adjacentInventory.isEmpty(connected);
    }

    /**
     * Determines whether this crafting pipe's outstanding output orders should be reported as destination-buffered.
     * <p>
     * The network-wide can-sink lookup may route back to this same module when a pattern requests a subitem that
     * another pattern in this pipe crafts. Self-destined orders are already represented by
     * {@link #requestedIngredients}, so they are treated as locally buffered here and are not sent through
     * {@link LogisticsManager#canSink} again.
     */
    private boolean areAllOrdersBuffered() {
        if (checkingBufferedOrders) {
            return true;
        }
        checkingBufferedOrders = true;
        try {
            boolean result = true;
            for (LogisticsItemOrder order : pipe.getItemOrderManager()) {
                if (isOrderDestinationThisModule(order)) {
                    debug(
                            "buffer check treats self-destined order as buffered order=%s amount=%d",
                            order.getResource().getItem(),
                            order.getAmount());
                    continue;
                }
                if (order.getDestination() instanceof IItemSpaceControl) {
                    SinkReply reply = LogisticsManager.canSink(
                            order.getDestination().getRouter(),
                            null,
                            true,
                            order.getResource().getItem(),
                            null,
                            true,
                            false);
                    if (reply != null && reply.bufferMode == BufferMode.NONE && reply.maxNumberOfItems >= 1) {
                        debug(
                                "buffer check found unbuffered destination order=%s replyRoom=%d",
                                order.getResource().getItem(),
                                reply.maxNumberOfItems);
                        result = false;
                        break;
                    }
                } else {
                    debug(
                            "buffer check found destination without space control order=%s",
                            order.getResource().getItem());
                    result = false;
                    break;
                }
            }
            debug("buffer check result=%s", result);
            return result;
        } finally {
            checkingBufferedOrders = false;
        }
    }

    boolean isOrderDestinationThisModule(LogisticsItemOrder order) {
        IRequest destination = order.getDestination();
        return destination == this || (destination != null && destination.getRouter() == getRouter());
    }

    boolean isOrderDestinationThisModule(LogisticsFluidOrder order) {
        IRequest destination = order.getDestination();
        return destination == this || (destination != null && destination.getRouter() == getRouter());
    }

    int requestedSamePipeItemAmount(LogisticsItemOrder order) {
        if (!(order.getInformation() instanceof PatternTargetInformation)) {
            return order.getAmount();
        }
        int patternSlot = ((PatternTargetInformation) order.getInformation()).patternSlot();
        return requestedItemAmount(patternSlot, getPatternStack(patternSlot), order.getResource().getItem());
    }

    int requestedSamePipeFluidAmount(LogisticsFluidOrder order) {
        if (!(order.getInformation() instanceof PatternTargetInformation)) {
            return order.getAmount();
        }
        int patternSlot = ((PatternTargetInformation) order.getInformation()).patternSlot();
        return requestedIngredient.amount(patternSlot, order.getFluid());
    }

    private void appendConnectedInventoryDebug(StringBuilder out) {
        AdjacentTile connected = adjacentInventory.getConnected();
        if (connected == null) {
            out.append("  connected inventory: <none>\n");
            return;
        }
        if (connected.tile == null) {
            out.append("  connected inventory: <null tile> side=").append(connected.orientation).append("\n");
            return;
        }
        out.append("  connected inventory: ").append(connected.tile.getClass().getName()).append(" side=")
                .append(connected.orientation).append(" empty=").append(adjacentInventory.isEmpty(connected))
                .append(" patternTable=").append(adjacentInventory.isConnectedToPatternCraftingTable()).append("\n");
    }

    private void appendPatternDebug(StringBuilder out) {
        out.append("  patterns:\n");
        boolean found = false;
        for (int slot = 0; slot < patternHandler.size(); slot++) {
            ItemStack pattern = getPatternStack(slot);
            if (pattern == null) {
                continue;
            }
            found = true;
            out.append("    slot ").append(slot).append("\n");
            AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
            appendPatternSlots(out, configuredPattern, 0, configuredPattern.getIngredientSlotCount(), "      inputs");
            appendPatternSlots(
                    out,
                    configuredPattern,
                    configuredPattern.getResultSlotStart(),
                    configuredPattern.getItemSlotCount(),
                    "      results");
        }
        if (!found) {
            out.append("    <none>\n");
        }
    }

    private void appendPatternSlots(StringBuilder out, AbstractPattern pattern, int start, int end, String label) {
        out.append(label).append(": ");
        boolean found = false;
        for (int slot = start; slot < end; slot++) {
            IPatternStack stack = pattern.getPatternStackInSlot(slot);
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            if (found) {
                out.append(", ");
            }
            out.append("slot ").append(slot).append("=").append(stack);
            found = true;
        }
        if (!found) {
            out.append("<none>");
        }
        out.append("\n");
    }

    private void appendStackMapDebug(StringBuilder out, String label,
            Map<Integer, List<IPatternStack>> stacksByPattern) {
        out.append("  ").append(label).append(":\n");
        if (stacksByPattern.isEmpty()) {
            out.append("    <none>\n");
            return;
        }
        for (Map.Entry<Integer, List<IPatternStack>> entry : stacksByPattern.entrySet()) {
            out.append("    slot ").append(entry.getKey()).append(": ");
            appendInlineStacks(out, entry.getValue());
            out.append("\n");
        }
    }

    private void appendStagedCraftDebug(StringBuilder out) {
        out.append("  staged crafts:\n");
        stagedCrafting.appendDebugState(out, "    ");
    }

    private void appendOrderDebug(StringBuilder out) {
        out.append("  output orders:\n");
        boolean found = false;
        for (LogisticsItemOrder order : pipe.getItemOrderManager()) {
            found = true;
            out.append("    - ").append(order.getType()).append(" ").append(order.getAmount()).append("x ")
                    .append(order.getResource().getItem()).append(" -> router ").append(order.getRouterId())
                    .append(order.isInProgress() ? " in-progress" : "").append(order.isFinished() ? " finished" : "");
            if (order.getInformation() != null) {
                out.append(" info=").append(order.getInformation());
            }
            out.append("\n");
        }
        if (!found) {
            out.append("    <none>\n");
        }
    }

    private void appendInlineStacks(StringBuilder out, List<IPatternStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            out.append("<none>");
            return;
        }
        for (int i = 0; i < stacks.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            IPatternStack stack = stacks.get(i);
            out.append(stack == null ? "<null>" : stack.toString());
        }
    }

    /**
     * Re-requests ingredients whose routed item or fluid container was lost before reaching this module.
     */
    private void retryLostItems() {
        DelayedGeneric<Pair<IPatternStack, IAdditionalTargetInformation>> lost = lostIngredients.poll();
        int rerequested = 0;
        while (lost != null && rerequested < 100) {
            Pair<IPatternStack, IAdditionalTargetInformation> pair = lost.get();
            IPatternStack stack = pair.getValue1();
            int received = requestLostIngredient(stack, pair.getValue2());
            debugEvent(
                    "REQUEST",
                    "lost retry ingredient=%s requested=%d received=%d info=%s",
                    stack,
                    stack.getAmount(),
                    received,
                    pair.getValue2());
            rerequested++;
            if (received < stack.getAmount()) {
                IPatternStack remaining = PatternStackHelper.copyWithAmount(stack, stack.getAmount() - received);
                if (remaining != null) {
                    debugEvent("REQUEST", "lost retry requeued remaining=%s", remaining);
                    lostIngredients.add(
                            new DelayedGeneric<>(
                                    new Pair<>(remaining, pair.getValue2()),
                                    4500 + (int) (Math.random() * 1000)));
                }
            }
            lost = lostIngredients.poll();
        }
    }

    /**
     * Places a partial request for a lost item or fluid ingredient.
     */
    private int requestLostIngredient(IPatternStack stack, IAdditionalTargetInformation info) {
        int originalAmount = stack == null ? 0 : stack.getAmount();
        IPatternStack outstanding = outstandingRequestedRetryStack(stack, info);
        if (outstanding == null || outstanding.getAmount() <= 0) {
            return originalAmount;
        }
        if (info instanceof PatternTargetInformation && hasLivePatternOutputOrderForRequested(
                ((PatternTargetInformation) info).patternSlot(),
                outstanding)) {
            debugEvent(
                    "REQUEST",
                    "lost retry skipped, live pattern output still pending ingredient=%s info=%s",
                    outstanding,
                    info);
            return originalAmount;
        }

        ItemIdentifierStack item = PatternStackHelper.asSolidStack(outstanding);
        if (item != null) {
            debugEvent("REQUEST", "lost retry requesting item=%s info=%s", item, info);
            return originalAmount - outstanding.getAmount() + RequestTree.requestPartial(item.clone(), pipe, info);
        }
        FluidIdentifier fluid = PatternStackHelper.asFluid(outstanding);
        if (fluid != null) {
            debugEvent(
                    "REQUEST",
                    "lost retry requesting fluid=%s amount=%d info=%s",
                    fluid,
                    outstanding.getAmount(),
                    info);
            return originalAmount - outstanding.getAmount()
                    + RequestTree.requestFluidPartial(fluid, outstanding.getAmount(), this, null, info);
        }
        return 0;
    }

    private IPatternStack outstandingRequestedRetryStack(IPatternStack stack, IAdditionalTargetInformation info) {
        if (!(info instanceof PatternTargetInformation) || stack == null || stack.getAmount() <= 0) {
            return stack;
        }
        int patternSlot = ((PatternTargetInformation) info).patternSlot();
        int outstanding = 0;
        ItemIdentifierStack item = PatternStackHelper.asSolidStack(stack);
        if (item != null) {
            outstanding = requestedItemAmount(patternSlot, getPatternStack(patternSlot), item.getItem());
        } else {
            FluidIdentifier fluid = PatternStackHelper.asFluid(stack);
            if (fluid != null) {
                outstanding = requestedIngredient.amount(patternSlot, fluid);
            }
        }
        return PatternStackHelper.copyWithAmount(stack, Math.min(stack.getAmount(), outstanding));
    }

    private boolean hasLivePatternOutputOrderForRequested(int patternSlot, IPatternStack stack) {
        int outstanding = stack == null ? 0 : stack.getAmount();
        if (outstanding <= 0) {
            return false;
        }
        int covered = 0;
        for (IRouter router : SimpleServiceLocator.routerManager.getRouters()) {
            if (router == null || !(router.getPipe() instanceof PipeItemsPatternCraftingLogistics patternPipe)) {
                continue;
            }
            covered += liveItemOutputAmountFor(patternPipe, patternSlot, stack);
            covered += liveFluidOutputAmountFor(patternPipe, patternSlot, stack);
            if (covered >= outstanding) {
                return true;
            }
        }
        return false;
    }

    private int liveItemOutputAmountFor(PipeItemsPatternCraftingLogistics patternPipe, int patternSlot,
            IPatternStack stack) {
        ItemIdentifierStack item = PatternStackHelper.asSolidStack(stack);
        if (item == null) {
            return 0;
        }
        int amount = 0;
        for (LogisticsItemOrder order : patternPipe.getItemOrderManager()) {
            if (order.isFinished() || !item.getItem().equalsForCrafting(order.getResource().getItem())
                    || !isOrderTargetingThisPattern(order, patternSlot)) {
                continue;
            }
            amount += Math.max(0, order.getAmount());
        }
        return amount;
    }

    private int liveFluidOutputAmountFor(PipeItemsPatternCraftingLogistics patternPipe, int patternSlot,
            IPatternStack stack) {
        FluidIdentifier fluid = PatternStackHelper.asFluid(stack);
        if (fluid == null) {
            return 0;
        }
        int amount = 0;
        for (LogisticsFluidOrder order : patternPipe.getPatternFluidOrderManager()) {
            if (order.isFinished() || !fluid.equals(order.getFluid())
                    || !isOrderTargetingThisPattern(order, patternSlot)) {
                continue;
            }
            amount += Math.max(0, order.getAmount());
        }
        return amount;
    }

    private boolean isOrderTargetingThisPattern(LogisticsItemOrder order, int patternSlot) {
        return order.getDestination() != null && order.getDestination().getRouter() == getRouter()
                && order.getInformation() instanceof PatternTargetInformation
                && ((PatternTargetInformation) order.getInformation()).patternSlot() == patternSlot;
    }

    private boolean isOrderTargetingThisPattern(LogisticsFluidOrder order, int patternSlot) {
        return order.getDestination() != null && order.getDestination().getRouter() == getRouter()
                && order.getInformation() instanceof PatternTargetInformation
                && ((PatternTargetInformation) order.getInformation()).patternSlot() == patternSlot;
    }

    private void readLostIngredientsFromNBT(NBTTagCompound tag) {
        lostIngredients.clear();
        NBTTagList lost = tag.getTagList(LOST_INGREDIENTS_TAG, TAG_COMPOUND);
        for (int i = 0; i < lost.tagCount(); i++) {
            NBTTagCompound stackTag = lost.getCompoundTagAt(i);
            IPatternStack stack = IPatternStack.readFromNBT(stackTag);
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            long delay = Math.max(1, stackTag.getLong(LOST_DELAY_TAG));
            lostIngredients.add(new DelayedGeneric<>(new Pair<>(stack, readTargetInformation(stackTag)), delay));
        }
    }

    private void writeLostIngredientsToNBT(NBTTagCompound tag) {
        NBTTagList lost = new NBTTagList();
        for (DelayedGeneric<Pair<IPatternStack, IAdditionalTargetInformation>> queued : lostIngredients) {
            Pair<IPatternStack, IAdditionalTargetInformation> pair = queued.get();
            IPatternStack stack = pair.getValue1();
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            NBTTagCompound stackTag = new NBTTagCompound();
            stack.writeToNBT(stackTag);
            stackTag.setLong(LOST_DELAY_TAG, Math.max(1, queued.getDelay(TimeUnit.NANOSECONDS) / 1000));
            writeTargetInformation(stackTag, pair.getValue2());
            lost.appendTag(stackTag);
        }
        tag.setTag(LOST_INGREDIENTS_TAG, lost);
    }

    private IAdditionalTargetInformation readTargetInformation(NBTTagCompound tag) {
        if (!tag.hasKey(TARGET_PATTERN_SLOT_TAG)) {
            return null;
        }
        int inputSlot = tag.hasKey(TARGET_INPUT_SLOT_TAG) ? tag.getInteger(TARGET_INPUT_SLOT_TAG)
                : PatternTargetInformation.NO_INPUT_SLOT;
        return new PatternTargetInformation(tag.getInteger(TARGET_PATTERN_SLOT_TAG), inputSlot);
    }

    private void writeTargetInformation(NBTTagCompound tag, IAdditionalTargetInformation info) {
        if (info instanceof PatternTargetInformation patternInfo) {
            tag.setInteger(TARGET_PATTERN_SLOT_TAG, patternInfo.patternSlot());
            tag.setInteger(TARGET_INPUT_SLOT_TAG, patternInfo.inputSlot());
        }
    }

    public void onAllowedRemoval() {

        World world = pipe.getWorld();

        if (activeSatelliteBatch != null) {
            activeSatelliteBatch.retrieveAndRelease();
            activeSatelliteBatch = null;
        }
        stagedCrafting.releaseAll();
        requestedIngredients.clear();
        cancelledPatternSlots.clear();
        markHudStateDirty();

        patternInventory.dropContents(world, pipe.getX(), pipe.getY(), pipe.getZ());
        ingredientBuffer.dropContents(world, pipe.getX(), pipe.getY(), pipe.getZ());
    }

    private static class ItemSatelliteAssignment {

        private final PipeItemsPatternSatelliteLogistics satellite;
        private final ItemIdentifierStack stack;

        private ItemSatelliteAssignment(PipeItemsPatternSatelliteLogistics satellite, ItemIdentifierStack stack) {
            this.satellite = satellite;
            this.stack = stack;
        }
    }

    private static class FluidSatelliteAssignment {

        private final PipeFluidPatternSatelliteLogistics satellite;
        private final FluidIdentifier fluid;
        private final int amount;

        private FluidSatelliteAssignment(PipeFluidPatternSatelliteLogistics satellite, FluidIdentifier fluid,
                int amount) {
            this.satellite = satellite;
            this.fluid = fluid;
            this.amount = amount;
        }
    }

    private class PatternDispatchPlan {

        private final ItemStack pattern;
        private final List<PatternIngredientAssignment> assignments;
        private final List<PatternIngredientAssignment> localAssignments = new ArrayList<>();
        private final List<ItemSatelliteAssignment> itemSatelliteAssignments = new ArrayList<>();
        private final List<FluidSatelliteAssignment> fluidSatelliteAssignments = new ArrayList<>();

        private PatternDispatchPlan(ItemStack pattern, List<PatternIngredientAssignment> assignments) {
            this.pattern = pattern;
            this.assignments = new ArrayList<>(assignments);
        }

        private List<PatternIngredientAssignment> assignments() {
            return assignments;
        }

        private void addLocal(PatternIngredientAssignment assignment) {
            localAssignments.add(assignment);
        }

        private void addItemSatellite(PipeItemsPatternSatelliteLogistics satellite, ItemIdentifierStack stack) {
            itemSatelliteAssignments.add(new ItemSatelliteAssignment(satellite, stack));
        }

        private void addFluidSatellite(PipeFluidPatternSatelliteLogistics satellite, FluidIdentifier fluid,
                int amount) {
            fluidSatelliteAssignments.add(new FluidSatelliteAssignment(satellite, fluid, amount));
        }

        private boolean hasSatellites() {
            return !itemSatelliteAssignments.isEmpty() || !fluidSatelliteAssignments.isEmpty();
        }

        private boolean canDispatch() {
            if (!localAssignments.isEmpty()
                    && !adjacentInventory.canInsertPatternIngredients(pattern, localAssignments)) {
                return false;
            }
            for (ItemSatelliteAssignment assignment : itemSatelliteAssignments) {
                if (!assignment.satellite.canReserveFor(pipe)
                        || !assignment.satellite.canAcceptPatternInput(assignment.stack)) {
                    return false;
                }
            }
            for (FluidSatelliteAssignment assignment : fluidSatelliteAssignments) {
                if (!assignment.satellite.canReserveFor(pipe)
                        || !assignment.satellite.canAcceptPatternInput(assignment.fluid, assignment.amount)) {
                    return false;
                }
            }
            return true;
        }

        private boolean dispatch() {
            if (!canDispatch()) {
                return false;
            }
            List<PipeItemsPatternSatelliteLogistics> reservedItemSatellites = new ArrayList<>();
            List<PipeFluidPatternSatelliteLogistics> reservedFluidSatellites = new ArrayList<>();
            if (!reserveSatellites(reservedItemSatellites, reservedFluidSatellites)) {
                releaseSatellites(reservedItemSatellites, reservedFluidSatellites);
                return false;
            }
            if (!localAssignments.isEmpty() && !adjacentInventory.insertPatternIngredients(pattern, localAssignments)) {
                releaseSatellites(reservedItemSatellites, reservedFluidSatellites);
                return false;
            }
            for (ItemSatelliteAssignment assignment : itemSatelliteAssignments) {
                int inserted = assignment.satellite.insertPatternInput(assignment.stack);
                if (inserted != assignment.stack.getStackSize()) {
                    releaseSatellites(reservedItemSatellites, reservedFluidSatellites);
                    return false;
                }
            }
            for (FluidSatelliteAssignment assignment : fluidSatelliteAssignments) {
                int inserted = assignment.satellite.insertPatternInput(assignment.fluid, assignment.amount);
                if (inserted != assignment.amount) {
                    releaseSatellites(reservedItemSatellites, reservedFluidSatellites);
                    return false;
                }
            }
            return true;
        }

        private PatternSatelliteDispatchBatch createSatelliteBatch(int patternSlot) {
            return new PatternSatelliteDispatchBatch(
                    patternSlot,
                    new ArrayList<>(itemSatelliteAssignments),
                    new ArrayList<>(fluidSatelliteAssignments));
        }

        private boolean reserveSatellites(List<PipeItemsPatternSatelliteLogistics> itemSatellites,
                List<PipeFluidPatternSatelliteLogistics> fluidSatellites) {
            for (PipeItemsPatternSatelliteLogistics satellite : uniqueItemSatellites()) {
                if (!satellite.reserveFor(pipe)) {
                    return false;
                }
                itemSatellites.add(satellite);
            }
            for (PipeFluidPatternSatelliteLogistics satellite : uniqueFluidSatellites()) {
                if (!satellite.reserveFor(pipe)) {
                    return false;
                }
                fluidSatellites.add(satellite);
            }
            return true;
        }

        private List<PipeItemsPatternSatelliteLogistics> uniqueItemSatellites() {
            List<PipeItemsPatternSatelliteLogistics> result = new ArrayList<>();
            for (ItemSatelliteAssignment assignment : itemSatelliteAssignments) {
                if (!result.contains(assignment.satellite)) {
                    result.add(assignment.satellite);
                }
            }
            return result;
        }

        private List<PipeFluidPatternSatelliteLogistics> uniqueFluidSatellites() {
            List<PipeFluidPatternSatelliteLogistics> result = new ArrayList<>();
            for (FluidSatelliteAssignment assignment : fluidSatelliteAssignments) {
                if (!result.contains(assignment.satellite)) {
                    result.add(assignment.satellite);
                }
            }
            return result;
        }

        private void releaseSatellites(List<PipeItemsPatternSatelliteLogistics> itemSatellites,
                List<PipeFluidPatternSatelliteLogistics> fluidSatellites) {
            for (PipeItemsPatternSatelliteLogistics satellite : itemSatellites) {
                satellite.releaseReservation(pipe);
            }
            for (PipeFluidPatternSatelliteLogistics satellite : fluidSatellites) {
                satellite.releaseReservation(pipe);
            }
        }
    }

    private class PatternSatelliteDispatchBatch {

        private final int patternSlot;
        private final List<ItemSatelliteAssignment> itemAssignments;
        private final List<FluidSatelliteAssignment> fluidAssignments;

        private PatternSatelliteDispatchBatch(int patternSlot, List<ItemSatelliteAssignment> itemAssignments,
                List<FluidSatelliteAssignment> fluidAssignments) {
            this.patternSlot = patternSlot;
            this.itemAssignments = itemAssignments;
            this.fluidAssignments = fluidAssignments;
        }

        private boolean isConsumed() {
            for (PipeItemsPatternSatelliteLogistics satellite : uniqueItemSatellites()) {
                if (!satellite.isReservationConsumed(pipe)) {
                    return false;
                }
            }
            for (PipeFluidPatternSatelliteLogistics satellite : uniqueFluidSatellites()) {
                if (!satellite.isReservationConsumed(pipe)) {
                    return false;
                }
            }
            return true;
        }

        private int size() {
            return uniqueItemSatellites().size() + uniqueFluidSatellites().size();
        }

        private void retrieveAndRelease() {
            for (ItemSatelliteAssignment assignment : itemAssignments) {
                assignment.satellite.retrieveOrCancelToStorage(assignment.stack.clone(), false);
            }
            for (FluidSatelliteAssignment assignment : fluidAssignments) {
                assignment.satellite.retrieveFluidToStorage(assignment.fluid, assignment.amount);
            }
            release();
        }

        private void release() {
            for (PipeItemsPatternSatelliteLogistics satellite : uniqueItemSatellites()) {
                satellite.releaseReservation(pipe);
            }
            for (PipeFluidPatternSatelliteLogistics satellite : uniqueFluidSatellites()) {
                satellite.releaseReservation(pipe);
            }
        }

        private List<PipeItemsPatternSatelliteLogistics> uniqueItemSatellites() {
            List<PipeItemsPatternSatelliteLogistics> result = new ArrayList<>();
            for (ItemSatelliteAssignment assignment : itemAssignments) {
                if (!result.contains(assignment.satellite)) {
                    result.add(assignment.satellite);
                }
            }
            return result;
        }

        private List<PipeFluidPatternSatelliteLogistics> uniqueFluidSatellites() {
            List<PipeFluidPatternSatelliteLogistics> result = new ArrayList<>();
            for (FluidSatelliteAssignment assignment : fluidAssignments) {
                if (!result.contains(assignment.satellite)) {
                    result.add(assignment.satellite);
                }
            }
            return result;
        }
    }

    private static class ThrottledDebugEvent {

        private long lastLoggedTick = Long.MIN_VALUE;
        private int suppressed;
    }

}
