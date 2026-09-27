package logisticspipes.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import logisticspipes.crafting.pattern.PatternHandler;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternStackHelper;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;
import logisticspipes.request.IPromise;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.FluidResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.FluidLogisticsPromise;
import logisticspipes.routing.order.IOrderInfoProvider;
import logisticspipes.routing.order.LogisticsFluidOrder;
import logisticspipes.routing.order.LogisticsItemOrder;

/**
 * Owns the lifecycle of staged pattern crafting output orders.
 * <p>
 * The coordinator is the first stop after the request tree decides that this pipe should craft something. It validates
 * that the request has a real destination, creates the live output order, records the branch state required to request
 * ingredients later, and asks the scheduler to request any ingredient sets that can fit immediately.
 */
class PatternStagedCraftingCoordinator {

    private static final String STAGED_ORDERS_TAG = "stagedOrders";
    private static final String STANDALONE_ITEM_ORDERS_TAG = "standaloneItemOrders";
    private static final String STANDALONE_FLUID_ORDERS_TAG = "standaloneFluidOrders";
    private static final String PATTERN_SLOT_TAG = "patternSlot";
    private static final String RESULT_AMOUNT_PER_SET_TAG = "resultAmountPerSet";
    private static final String REMAINING_SETS_TAG = "remainingSets";
    private static final String OUTPUT_ORDER_TAG = "outputOrder";
    private static final String INGREDIENT_BRANCHES_TAG = "ingredientBranches";
    private static final int TAG_COMPOUND = 10;

    private final ModulePatternCrafting module;
    private final PipeItemsPatternCraftingLogistics pipe;
    private final PatternHandler patternHandler;
    private final PatternStackRequestHandler requestedIngredient;
    private final List<PatternCraftingOrder> stagedCrafts = new ArrayList<>();
    private final List<PatternCraftingOrder> outputOrders = new ArrayList<>();
    private final PatternStagedCraftingScheduler scheduler;
    private final PatternCraftingCancellationResolver cancellationResolver = new PatternCraftingCancellationResolver();

    PatternStagedCraftingCoordinator(ModulePatternCrafting module, PipeItemsPatternCraftingLogistics pipe,
            PatternHandler patternHandler, PatternStackRequestHandler requestedIngredient,
            AdjacentInventoryHandler adjacentInventory) {
        this.module = module;
        this.pipe = pipe;
        this.patternHandler = patternHandler;
        this.requestedIngredient = requestedIngredient;
        this.scheduler = new PatternStagedCraftingScheduler(module, pipe, adjacentInventory, stagedCrafts);
    }

    IOrderInfoProvider fulfill(IPromise promise, IResource requestType, IAdditionalTargetInformation info,
            PatternCraftingBranch branch) {
        if (!hasRequestTarget(promise, requestType)) {
            module.debugEvent(
                    "STAGED",
                    "staged craft rejected without target promise=%s request=%s info=%s",
                    promise,
                    requestType,
                    info);
            return null;
        }

        module.debugEvent(
                "STAGED",
                "staged craft start promise=%s amount=%d request=%s info=%s branch=%s",
                promise.getItemType(),
                promise.getAmount(),
                requestType,
                info,
                branch == null ? "<none>" : "available");

        IOrderInfoProvider order = promise.fullFill(requestType, info);
        int patternSlot = resolvePatternSlot(promise);
        int resultAmountPerSet = resolveResultAmountPerSet(promise, patternSlot);

        module.debugEvent(
                "STAGED",
                "staged craft output order=%s patternSlot=%d resultAmountPerSet=%d",
                order == null ? "<none>" : order.getAsDisplayItem(),
                patternSlot,
                resultAmountPerSet);

        if (patternSlot >= 0 && branch != null && order != null) {
            registerOrder(patternSlot, resultAmountPerSet, branch, order);
        }
        return order;
    }

    void requestIngredients() {
        scheduler.requestIngredients();
    }

    boolean hasPattern(int patternSlot) {
        for (PatternCraftingOrder order : outputOrders) {
            if (order.patternSlot == patternSlot && !order.outputOrder.isFinished()) {
                return true;
            }
        }
        return false;
    }

    void writeToNBT(NBTTagCompound tag) {
        Set<IOrderInfoProvider> savedOutputOrders = Collections.newSetFromMap(new IdentityHashMap<>());
        NBTTagList stagedOrders = writeStagedOrders(savedOutputOrders);
        NBTTagList standaloneItemOrders = writeStandaloneItemOrders(savedOutputOrders);
        NBTTagList standaloneFluidOrders = writeStandaloneFluidOrders(savedOutputOrders);
        tag.setTag(STAGED_ORDERS_TAG, stagedOrders);
        tag.setTag(STANDALONE_ITEM_ORDERS_TAG, standaloneItemOrders);
        tag.setTag(STANDALONE_FLUID_ORDERS_TAG, standaloneFluidOrders);
        module.debugEvent(
                "PERSIST",
                "saved staged crafting state stagedOrders=%d standaloneItems=%d standaloneFluids=%d trackedOutputOrders=%d",
                stagedOrders.tagCount(),
                standaloneItemOrders.tagCount(),
                standaloneFluidOrders.tagCount(),
                outputOrders.size());
    }

    boolean restoreFromNBT(NBTTagCompound tag) {
        try {
            List<RestoredStagedOrder> restoredStagedOrders = readStagedOrders(
                    tag.getTagList(STAGED_ORDERS_TAG, TAG_COMPOUND));
            List<PatternCraftingPersistence.RestoredOrder> standaloneItemOrders = readOrders(
                    tag.getTagList(STANDALONE_ITEM_ORDERS_TAG, TAG_COMPOUND));
            List<PatternCraftingPersistence.RestoredOrder> standaloneFluidOrders = readOrders(
                    tag.getTagList(STANDALONE_FLUID_ORDERS_TAG, TAG_COMPOUND));
            module.debugEvent(
                    "PERSIST",
                    "restoring staged crafting state stagedOrders=%d standaloneItems=%d standaloneFluids=%d",
                    restoredStagedOrders.size(),
                    standaloneItemOrders.size(),
                    standaloneFluidOrders.size());

            stagedCrafts.clear();
            outputOrders.clear();

            for (PatternCraftingPersistence.RestoredOrder order : standaloneItemOrders) {
                order.create(pipe, module);
            }
            for (PatternCraftingPersistence.RestoredOrder order : standaloneFluidOrders) {
                order.create(pipe, module);
            }
            for (RestoredStagedOrder restored : restoredStagedOrders) {
                IOrderInfoProvider outputOrder = restored.outputOrder.create(pipe, module);
                PatternCraftingOrder order = new PatternCraftingOrder(
                        restored.patternSlot,
                        restored.resultAmountPerSet,
                        restored.remainingSets,
                        restored.ingredientBranches,
                        outputOrder,
                        module,
                        requestedIngredient);
                if (restored.runtimeState != null) {
                    order.readRuntimeState(restored.runtimeState);
                }
                outputOrders.add(order);
                if (!order.isFullyRequested() && !outputOrder.isFinished()) {
                    stagedCrafts.add(order);
                    for (PatternCraftingBranch branch : order.ingredientBranches) {
                        branch.reserveProviderPromises();
                    }
                }
                PatternCraftingMonitorRegistry.register(outputOrder, order);
                module.debugEvent(
                        "STAGED",
                        "restored staged craft slot=%d remainingSets=%d branches=%d output=%s",
                        order.patternSlot,
                        order.remainingSets,
                        order.ingredientBranches.size(),
                        outputOrder.getAsDisplayItem());
            }
            module.markHudStateDirty();
            return true;
        } catch (PatternCraftingPersistence.RestoreNotReadyException ignored) {
            module.debugEventThrottled("PERSIST", "restore staged crafting state postponed: routers not ready");
            return false;
        }
    }

    int remainingSets(int patternSlot) {
        int sets = 0;
        for (PatternCraftingOrder order : stagedCrafts) {
            if (order.patternSlot == patternSlot && !order.outputOrder.isFinished()) {
                sets += Math.max(0, order.remainingSets);
            }
        }
        return sets;
    }

    int remainingOutputAmount(int patternSlot, IPatternStack output) {
        int amount = 0;
        for (PatternCraftingOrder order : new ArrayList<>(outputOrders)) {
            if (order.outputOrder.isFinished()) {
                outputOrders.remove(order);
                continue;
            }
            if (order.patternSlot != patternSlot) {
                continue;
            }
            if (order.outputOrder.getAsDisplayItem() == null) {
                continue;
            }
            if (PatternStackHelper.matches(output, order.outputOrder.getAsDisplayItem().getItem())) {
                amount += Math.max(0, order.outputOrder.getAsDisplayItem().getStackSize());
            }
        }
        return amount;
    }

    void appendDebugState(StringBuilder out, String prefix) {
        if (stagedCrafts.isEmpty() && outputOrders.isEmpty()) {
            out.append(prefix).append("<none>\n");
            return;
        }
        out.append(prefix).append("active staged orders:\n");
        if (stagedCrafts.isEmpty()) {
            out.append(prefix).append("  <none>\n");
        }
        for (PatternCraftingOrder order : stagedCrafts) {
            order.appendDebugState(out, prefix + "  ");
        }
        out.append(prefix).append("tracked output orders:\n");
        if (outputOrders.isEmpty()) {
            out.append(prefix).append("  <none>\n");
        }
        for (PatternCraftingOrder order : outputOrders) {
            out.append(prefix).append("  - slot=").append(order.patternSlot).append(" active=")
                    .append(stagedCrafts.contains(order)).append(" remainingSets=").append(order.remainingSets)
                    .append(" output=")
                    .append(order.outputOrder == null ? "<none>" : order.outputOrder.getAsDisplayItem())
                    .append(order.outputOrder != null && order.outputOrder.isFinished() ? " finished" : "")
                    .append("\n");
        }
    }

    void releaseAll() {
        for (PatternCraftingOrder order : new ArrayList<>(outputOrders)) {
            module.debugEvent(
                    "CANCEL",
                    "removal releases staged order slot=%d remainingSets=%d",
                    order.patternSlot,
                    order.remainingSets);
            order.releaseReservations();
        }
        stagedCrafts.clear();
        outputOrders.clear();
        module.markHudStateDirty();
    }

    Set<Integer> cancelPattern(int patternSlot) {
        Set<Integer> cancelledSlots = new HashSet<>();
        int directOrders = countActiveOutputOrders(patternSlot);
        List<PatternCraftingOrder> ordersToCancel = cancellationResolver.resolve(patternSlot, outputOrders);
        if (ordersToCancel.size() > directOrders) {
            module.debugEvent(
                    "CANCEL",
                    "cancel slot=%d expanded to request tree directOrders=%d resolvedOrders=%d",
                    patternSlot,
                    directOrders,
                    ordersToCancel.size());
        }
        Set<PatternCraftingOrder> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PatternCraftingOrder order : ordersToCancel) {
            cancelOrderAndChildren(order, visited, cancelledSlots);
        }
        if (!cancelledSlots.isEmpty()) {
            module.markHudStateDirty();
        }
        return cancelledSlots;
    }

    private void cancelOrderAndChildren(PatternCraftingOrder order, Set<PatternCraftingOrder> visited,
            Set<Integer> cancelledSlots) {
        if (order == null || !visited.add(order)) {
            return;
        }
        for (PatternCraftingOrder child : order.getChildStagedOrders()) {
            cancelOrderAndChildren(child, visited, cancelledSlots);
        }
        module.debugEvent(
                "CANCEL",
                "cancel staged order slot=%d remainingSets=%d",
                order.patternSlot,
                order.remainingSets);
        cancelledSlots.add(order.patternSlot);
        order.releaseReservations();
        removeOutputOrder(order.outputOrder);
        if (order.outputOrder != null) {
            PatternCraftingMonitorRegistry.unregister(order.outputOrder);
        }
        stagedCrafts.remove(order);
        outputOrders.remove(order);
    }

    private int countActiveOutputOrders(int patternSlot) {
        int count = 0;
        for (PatternCraftingOrder order : outputOrders) {
            if (order.patternSlot == patternSlot && order.outputOrder != null && !order.outputOrder.isFinished()) {
                count++;
            }
        }
        return count;
    }

    private void registerOrder(int patternSlot, int resultAmountPerSet, PatternCraftingBranch branch,
            IOrderInfoProvider order) {
        PatternCraftingOrder stagedOrder = new PatternCraftingOrder(
                patternSlot,
                resultAmountPerSet,
                branch,
                order,
                module,
                requestedIngredient);
        stagedCrafts.add(stagedOrder);
        outputOrders.add(stagedOrder);
        PatternCraftingMonitorRegistry.register(order, stagedOrder);
        module.clearCancelledPattern(patternSlot);
        module.markHudStateDirty();
        module.debugEvent(
                "STAGED",
                "staged craft registered slot=%d remainingSets=%d ingredientBranches=%d output=%s branch=%s branchRemaining=%d",
                patternSlot,
                stagedOrder.remainingSets,
                stagedOrder.ingredientBranches.size(),
                order.getAsDisplayItem(),
                branch.getRequestType(),
                branch.getRemainingAmount());
        scheduler.requestIngredients(patternSlot);
    }

    private void removeOutputOrder(IOrderInfoProvider order) {
        if (order instanceof LogisticsItemOrder) {
            pipe.getItemOrderManager().removeOrder((LogisticsItemOrder) order);
        } else if (order instanceof LogisticsFluidOrder) {
            pipe.getPatternFluidOrderManager().removeOrder((LogisticsFluidOrder) order);
        }
    }

    private boolean hasRequestTarget(IPromise promise, IResource requestType) {
        if (promise instanceof FluidLogisticsPromise) {
            return requestType instanceof FluidResource && ((FluidResource) requestType).getTarget() != null;
        }
        return getRequestTarget(requestType) != null;
    }

    private IRequestItems getRequestTarget(IResource requestType) {
        if (requestType instanceof ItemResource) {
            return ((ItemResource) requestType).getTarget();
        }
        if (requestType instanceof DictResource) {
            return ((DictResource) requestType).getTarget();
        }
        return null;
    }

    private int resolvePatternSlot(IPromise promise) {
        if (promise instanceof PatternCraftingPromise) {
            return ((PatternCraftingPromise) promise).getPatternSlot();
        }
        if (promise instanceof PatternFluidCraftingPromise) {
            return ((PatternFluidCraftingPromise) promise).getPatternSlot();
        }
        return patternHandler.findPatternSlotForResult(promise.getItemType());
    }

    private int resolveResultAmountPerSet(IPromise promise, int patternSlot) {
        if (promise instanceof PatternCraftingPromise) {
            return ((PatternCraftingPromise) promise).getResultAmountPerSet();
        }
        if (promise instanceof PatternFluidCraftingPromise) {
            return ((PatternFluidCraftingPromise) promise).getResultAmountPerSet();
        }
        return Math.max(1, patternHandler.resultAmount(patternSlot, promise.getItemType()));
    }

    private NBTTagList writeStagedOrders(Set<IOrderInfoProvider> savedOutputOrders) {
        NBTTagList list = new NBTTagList();
        for (PatternCraftingOrder order : outputOrders) {
            if (order.outputOrder == null || order.outputOrder.isFinished()) {
                continue;
            }
            NBTTagCompound orderTag = new NBTTagCompound();
            orderTag.setInteger(PATTERN_SLOT_TAG, order.patternSlot);
            orderTag.setInteger(RESULT_AMOUNT_PER_SET_TAG, order.resultAmountPerSet);
            orderTag.setInteger(REMAINING_SETS_TAG, Math.max(0, order.remainingSets));
            NBTTagCompound outputTag = new NBTTagCompound();
            if (!PatternCraftingPersistence.writeOrder(outputTag, order.outputOrder)) {
                continue;
            }
            orderTag.setTag(OUTPUT_ORDER_TAG, outputTag);
            NBTTagList branches = new NBTTagList();
            for (PatternCraftingBranch branch : order.ingredientBranches) {
                NBTTagCompound branchTag = new NBTTagCompound();
                branch.writeToNBT(branchTag);
                branches.appendTag(branchTag);
            }
            orderTag.setTag(INGREDIENT_BRANCHES_TAG, branches);
            order.writeRuntimeState(orderTag);
            list.appendTag(orderTag);
            savedOutputOrders.add(order.outputOrder);
        }
        return list;
    }

    private NBTTagList writeStandaloneItemOrders(Set<IOrderInfoProvider> savedOutputOrders) {
        NBTTagList list = new NBTTagList();
        for (LogisticsItemOrder order : pipe.getItemOrderManager()) {
            if (order.isFinished() || savedOutputOrders.contains(order)) {
                continue;
            }
            NBTTagCompound orderTag = new NBTTagCompound();
            if (PatternCraftingPersistence.writeOrder(orderTag, order)) {
                list.appendTag(orderTag);
            }
        }
        return list;
    }

    private NBTTagList writeStandaloneFluidOrders(Set<IOrderInfoProvider> savedOutputOrders) {
        NBTTagList list = new NBTTagList();
        for (LogisticsFluidOrder order : pipe.getPatternFluidOrderManager()) {
            if (order.isFinished() || savedOutputOrders.contains(order)) {
                continue;
            }
            NBTTagCompound orderTag = new NBTTagCompound();
            if (PatternCraftingPersistence.writeOrder(orderTag, order)) {
                list.appendTag(orderTag);
            }
        }
        return list;
    }

    private List<RestoredStagedOrder> readStagedOrders(NBTTagList list) {
        List<RestoredStagedOrder> result = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound orderTag = list.getCompoundTagAt(i);
            RestoredStagedOrder order = new RestoredStagedOrder();
            order.patternSlot = orderTag.getInteger(PATTERN_SLOT_TAG);
            order.resultAmountPerSet = orderTag.getInteger(RESULT_AMOUNT_PER_SET_TAG);
            order.remainingSets = orderTag.getInteger(REMAINING_SETS_TAG);
            order.outputOrder = PatternCraftingPersistence.readOrder(orderTag.getCompoundTag(OUTPUT_ORDER_TAG));
            order.runtimeState = orderTag;
            NBTTagList branches = orderTag.getTagList(INGREDIENT_BRANCHES_TAG, TAG_COMPOUND);
            for (int branch = 0; branch < branches.tagCount(); branch++) {
                order.ingredientBranches.add(PatternCraftingBranch.readFromNBT(branches.getCompoundTagAt(branch)));
            }
            result.add(order);
        }
        return result;
    }

    private List<PatternCraftingPersistence.RestoredOrder> readOrders(NBTTagList list) {
        List<PatternCraftingPersistence.RestoredOrder> result = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) {
            result.add(PatternCraftingPersistence.readOrder(list.getCompoundTagAt(i)));
        }
        return result;
    }

    private static class RestoredStagedOrder {

        private int patternSlot;
        private int resultAmountPerSet;
        private int remainingSets;
        private PatternCraftingPersistence.RestoredOrder outputOrder;
        private NBTTagCompound runtimeState;
        private final List<PatternCraftingBranch> ingredientBranches = new ArrayList<>();
    }
}
