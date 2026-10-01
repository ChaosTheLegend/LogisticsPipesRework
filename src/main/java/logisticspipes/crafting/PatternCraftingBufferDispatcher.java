package logisticspipes.crafting;

import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;
import logisticspipes.utils.AdjacentTile;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;

/** Moves complete buffered ingredient sets into the local crafting target and linked satellites. */
final class PatternCraftingBufferDispatcher {

    private final ModulePatternCrafting module;
    private final PatternStackBufferHandler ingredientBuffer;
    private final AdjacentInventoryHandler adjacentInventory;
    private final PatternCraftingBlockingHandler blockingHandler;
    private final PatternSatelliteDispatchHandler satelliteDispatchHandler;
    private final PatternCraftingIngredientPlanner ingredientPlanner;

    PatternCraftingBufferDispatcher(ModulePatternCrafting module, PatternStackBufferHandler ingredientBuffer,
                                    AdjacentInventoryHandler adjacentInventory, PatternCraftingBlockingHandler blockingHandler,
                                    PatternSatelliteDispatchHandler satelliteDispatchHandler,
                                    PatternCraftingIngredientPlanner ingredientPlanner) {
        this.module = module;
        this.ingredientBuffer = ingredientBuffer;
        this.adjacentInventory = adjacentInventory;
        this.blockingHandler = blockingHandler;
        this.satelliteDispatchHandler = satelliteDispatchHandler;
        this.ingredientPlanner = ingredientPlanner;
    }

    void refreshSatelliteBatches() {
        blockingHandler.hasSatelliteBatches();
    }

    void pushBufferedIngredients() {
        refreshSatelliteBatches();
        AdjacentTile connected = adjacentInventory.getConnected();
        if (connected == null) {
            module.debugEventThrottled(
                "BUFFER",
                "push skipped: no connected inventory bufferedSlots=%d",
                ingredientBuffer.size());
            return;
        }
        PipeItemsPatternCraftingLogistics.BlockingMode mode = module.getEffectiveBlockingMode();
        module.debug(
            "push tick mode=%s runningCraft=%d bufferedSlots=%d",
            mode,
            blockingHandler.runningCraft(),
            ingredientBuffer.size());
        if (mode == PipeItemsPatternCraftingLogistics.BlockingMode.OFF) {
            for (int patternSlot : new ArrayList<>(ingredientBuffer.asMap().keySet())) {
                for (PatternCraftingReference owner : ingredientBuffer.owners(patternSlot)) {
                    if (completeBufferedSets(owner, patternSlot) > 0) {
                        pushBufferedIngredientsFor(owner, patternSlot);
                    }
                }
            }
            return;
        }
        blockingHandler.refreshRunningCraftState(connected);
        if (blockingHandler.runningCraft() >= 0) {
            pushBufferedIngredientsFor(
                blockingHandler.runningCraftReference(),
                blockingHandler.runningCraft());
        }
    }

    void pushBufferedIngredientsFor(int patternSlot) {
        pushBufferedIngredientsFor(findCompleteBufferedOwner(patternSlot), patternSlot);
    }

    private void pushBufferedIngredientsFor(PatternCraftingReference ownerReference, int patternSlot) {
        if (ownerReference == null) {
            return;
        }
        ItemStack pattern = module.getPatternStack(patternSlot);
        if (pattern == null) {
            module.debugEvent("BUFFER", "push slot=%d dropped buffer: pattern missing", patternSlot);
            ingredientBuffer.removeAll(patternSlot);
            return;
        }
        PipeItemsPatternCraftingLogistics.BlockingMode mode = module.getEffectiveBlockingMode();
        if (blockingHandler.shouldSkipPushFor(patternSlot)) {
            module.debugEventThrottled(
                "BUFFER",
                "push slot=%d skipped: running craft locked by slot=%d",
                patternSlot,
                blockingHandler.runningCraft());
            return;
        }
        AdjacentTile connected = adjacentInventory.getConnected();
        if (mode != PipeItemsPatternCraftingLogistics.BlockingMode.OFF && !adjacentInventory.isEmpty(connected)) {
            module.debugEventThrottled("BUFFER", "push slot=%d skipped: blocking target is not ready", patternSlot);
            return;
        }
        int bufferedSets = completeBufferedSets(ownerReference, patternSlot);
        PatternSatelliteDispatchHandler.DispatchPlan plan = satelliteDispatchHandler
            .findInsertableBufferedPlan(ownerReference, patternSlot, pattern, bufferedSets);
        if (plan == null || !plan.dispatch()) {
            module.debugEventThrottled("BUFFER", "push slot=%d failed: bufferedSets=%d", patternSlot, bufferedSets);
            return;
        }
        int insertedSets = satelliteDispatchHandler.insertedSetsFromPlan(pattern, plan.assignments());
        module.debugEvent(
            "BUFFER",
            "push slot=%d inserted sets=%d bufferedSets=%d",
            patternSlot,
            insertedSets,
            bufferedSets);
        for (PatternIngredientAssignment assignment : plan.assignments()) {
            ingredientBuffer.remove(ownerReference, assignment.stack(), assignment.stack().getAmount());
        }
        blockingHandler.markDispatched(patternSlot, ownerReference, plan.satelliteBatch());
        PatternCraftingOrder order = PatternCraftingInstanceRegistry.find(ownerReference);
        if (order != null) {
            order.ingredientsDispatched(insertedSets);
        }
        module.debugEvent(
            "BUFFER",
            "push slot=%d buffer after insert remainingSets=%d runningCraft=%d adjacentBatch=%s",
            patternSlot,
            completeBufferedSets(patternSlot),
            blockingHandler.runningCraft(),
            blockingHandler.runningCraftInAdjacent());
        module.requestIngredientsForStagedCrafts();
    }

    int completeBufferedSets(int patternSlot) {
        ItemStack pattern = module.getPatternStack(patternSlot);
        int sets = ingredientPlanner.completeBufferedSets(patternSlot, pattern);
        module.debug("complete buffered sets slot=%d sets=%d", patternSlot, sets);
        return sets;
    }

    private int completeBufferedSets(PatternCraftingReference owner, int patternSlot) {
        ItemStack pattern = module.getPatternStack(patternSlot);
        return ingredientPlanner.completeBufferedSets(owner, patternSlot, pattern);
    }

    PatternCraftingReference findCompleteBufferedOwner(int patternSlot) {
        for (PatternCraftingReference owner : ingredientBuffer.owners(patternSlot)) {
            if (completeBufferedSets(owner, patternSlot) > 0) {
                return owner;
            }
        }
        return null;
    }

    int findCompleteBufferedPattern() {
        for (int patternSlot : ingredientBuffer.keySet()) {
            if (module.getPatternStack(patternSlot) == null) {
                ingredientBuffer.removeAll(patternSlot);
                continue;
            }
            if (completeBufferedSets(patternSlot) > 0) {
                return patternSlot;
            }
        }
        return -1;
    }

    void refreshRunningCraftState() {
        blockingHandler.refreshRunningCraftState(adjacentInventory.getConnected());
    }
}
