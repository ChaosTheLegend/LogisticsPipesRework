package logisticspipes.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternStackHelper;
import logisticspipes.interfaces.routing.IRequestFluid;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.routing.order.IOrderInfoProvider;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

class PatternCraftingOrder {

    private static final String PRE_REQUESTED_INGREDIENTS_TAG = "preRequestedIngredients";
    private static final String PRE_INPUT_SLOT_TAG = "inputSlot";
    private static final String PRE_AMOUNT_TAG = "amount";
    private static final String DELIVERY_PATTERN_SLOT_TAG = "patternSlot";
    private static final String DELIVERY_INPUT_SLOT_TAG = "inputSlot";
    private static final String DELIVERY_STACK_TAG = "stack";
    private static final String DELIVERY_TARGET_TYPE_TAG = "targetType";
    private static final String DELIVERY_TARGET_ITEM = "item";
    private static final String DELIVERY_TARGET_FLUID = "fluid";
    private static final String DELIVERY_SATELLITE_ID_TAG = "satelliteId";
    private static final String DELIVERY_SATELLITE_UUID_TAG = "satelliteUuid";
    private static final int TAG_COMPOUND = 10;

    final int patternSlot;
    final int resultAmountPerSet;
    final List<PatternCraftingBranch> ingredientBranches;
    int remainingSets;

    final IOrderInfoProvider outputOrder;
    private final ModulePatternCrafting module;
    private final PatternStackRequestHandler requestedIngredient;
    private final Map<Integer, Integer> preRequestedIngredients = new HashMap<>();

    PatternCraftingOrder(int patternSlot, int resultAmountPerSet, PatternCraftingBranch branch,
            IOrderInfoProvider outputOrder, ModulePatternCrafting module,
            PatternStackRequestHandler requestedIngredient) {
        this.patternSlot = patternSlot;
        this.resultAmountPerSet = Math.max(1, resultAmountPerSet);
        branch.attachDebugModule(module);
        this.ingredientBranches = new ArrayList<>(branch.getSubRequests());
        this.outputOrder = outputOrder;
        this.module = module;
        this.requestedIngredient = requestedIngredient;
        this.remainingSets = initialRemainingSets(branch);
        module.debugEvent(
                "REQUEST",
                "created staged order slot=%d output=%s branch=%s branchRemaining=%d resultAmountPerSet=%d remainingSets=%d ingredientBranches=%d",
                patternSlot,
                outputOrder == null ? "<none>" : outputOrder.getAsDisplayItem(),
                branch.getRequestType(),
                branch.getRemainingAmount(),
                this.resultAmountPerSet,
                this.remainingSets,
                ingredientBranches.size());
    }

    PatternCraftingOrder(int patternSlot, int resultAmountPerSet, int remainingSets,
            List<PatternCraftingBranch> ingredientBranches, IOrderInfoProvider outputOrder,
            ModulePatternCrafting module, PatternStackRequestHandler requestedIngredient) {
        this.patternSlot = patternSlot;
        this.resultAmountPerSet = Math.max(1, resultAmountPerSet);
        this.ingredientBranches = new ArrayList<>(ingredientBranches);
        for (PatternCraftingBranch branch : this.ingredientBranches) {
            branch.attachDebugModule(module);
        }
        this.outputOrder = outputOrder;
        this.module = module;
        this.requestedIngredient = requestedIngredient;
        this.remainingSets = Math.max(0, remainingSets);
        module.debugEvent(
                "REQUEST",
                "restored staged order slot=%d output=%s restoredRemainingSets=%d resultAmountPerSet=%d ingredientBranches=%d",
                patternSlot,
                outputOrder == null ? "<none>" : outputOrder.getAsDisplayItem(),
                remainingSets,
                this.resultAmountPerSet,
                ingredientBranches.size());
    }

    /**
     * Returns true once all required ingredient sets were requested.
     * <p>
     * A branch with no ingredients is also complete here: the output order can still be fulfilled from already-produced
     * items in the connected inventory, but there is no additional subtree work to request.
     */
    boolean isFullyRequested() {
        return remainingSets <= 0 || ingredientBranches.isEmpty();
    }

    /**
     * Counts the recipe sets that still need ingredient requests for this staged slice.
     * <p>
     * Output orders may be split at amounts that are not recipe-set aligned. The extra items produced by an earlier
     * slice remain in the adjacent inventory and can satisfy the next output order without another ingredient set, so
     * the staged ingredient work is capped by the branch capacity that was allocated to this slice.
     */
    private int initialRemainingSets(PatternCraftingBranch branch) {
        int outputSets = (branch.getRequestType().getRequestedAmount() + resultAmountPerSet - 1) / resultAmountPerSet;
        return capRemainingSets(outputSets);
    }

    private int capRemainingSets(int sets) {
        ItemStack pattern = module.getPatternStack(patternSlot);
        if (pattern == null) {
            return sets;
        }
        int available = availableSetsFromBranches(pattern);
        int capped = Math.min(sets, available);
        if (capped != sets) {
            module.debugEvent(
                    "REQUEST",
                    "order capped remaining sets slot=%d requestedSets=%d branchAvailableSets=%d cappedSets=%d",
                    patternSlot,
                    sets,
                    available,
                    capped);
        }
        return capped;
    }

    /**
     * Calculates how many pattern sets can still be produced from the remaining request-tree branches.
     */
    int availableSetsFromBranches(ItemStack pattern) {
        int sets = Integer.MAX_VALUE;
        for (PatternIngredientTarget ingredient : module.getIngredientTargets(pattern)) {
            int available = availableFromBranches(ingredient) + preRequestedAmount(ingredient.inputSlot());
            sets = Math.min(sets, available / ingredient.stack().getAmount());
        }
        return sets == Integer.MAX_VALUE ? 0 : Math.max(0, sets);
    }

    /**
     * Requests ingredients for up to {@code sets} pattern sets and records those in-flight ingredients as reserved
     * module buffer space.
     */
    int requestIngredients(ItemStack pattern, int sets) {
        int requestedSets = sets;
        List<RequestedIngredient> requestedIngredients = new ArrayList<>();
        module.debugEvent(
                "REQUEST",
                "order request ingredients slot=%d requestedSetsStart=%d remainingSets=%d output=%s",
                patternSlot,
                sets,
                remainingSets,
                outputOrder == null ? "<none>" : outputOrder.getAsDisplayItem());
        for (PatternIngredientTarget ingredient : module.getIngredientTargets(pattern)) {
            int amountPerSet = ingredient.stack().getAmount();
            int requestedBefore = preRequestedAmount(ingredient.inputSlot());
            int missing = Math.max(0, amountPerSet * requestedSets - requestedBefore);
            int requested = missing <= 0 ? 0
                    : requestFromBranches(ingredient.stack(), missing, ingredient.inputSlot(), null, null);
            if (requested > 0) {
                addPreRequestedIngredient(ingredient.inputSlot(), requested);
            }
            requestedIngredients.add(new RequestedIngredient(ingredient, requested));
            module.debugEvent(
                    "REQUEST",
                    "order requested ingredient slot=%d ingredient=%s satellite=%s requested=%d preRequested=%d amountPerSet=%d",
                    patternSlot,
                    ingredient.stack(),
                    ingredient.hasSatelliteTarget(),
                    requested,
                    preRequestedAmount(ingredient.inputSlot()),
                    amountPerSet);
            requestedSets = Math.min(requestedSets, preRequestedAmount(ingredient.inputSlot()) / amountPerSet);
        }
        for (RequestedIngredient requested : requestedIngredients) {
            if (requested.amount <= 0) {
                continue;
            }
            requestedIngredient.add(
                    patternSlot,
                    PatternStackHelper.copyWithAmount(requested.ingredient.stack(), requested.amount));
            module.debugEvent(
                    "BUFFER",
                    "order reserved requested ingredient slot=%d inputSlot=%d ingredient=%s requested=%d satellite=%s",
                    patternSlot,
                    requested.ingredient.inputSlot(),
                    requested.ingredient.stack(),
                    requested.amount,
                    requested.ingredient.hasSatelliteTarget());
        }
        commitPreRequested(pattern, requestedSets);
        remainingSets -= requestedSets;
        module.debugEvent(
                "REQUEST",
                "order request ingredients slot=%d requestedSetsFinal=%d remainingSets=%d",
                patternSlot,
                requestedSets,
                remainingSets);
        return requestedSets;
    }

    /**
     * Releases provider reservations still owned by this staged order.
     */
    void releaseReservations() {
        module.debugEvent(
                "REQUEST",
                "order release reservations slot=%d branches=%d remainingSets=%d output=%s",
                patternSlot,
                ingredientBranches.size(),
                remainingSets,
                outputOrder == null ? "<none>" : outputOrder.getAsDisplayItem());
        for (PatternCraftingBranch branch : ingredientBranches) {
            branch.releaseProviderPromises();
        }
    }

    /**
     * Returns child staged orders that this order launched through its ingredient branches.
     */
    List<PatternCraftingOrder> getChildStagedOrders() {
        List<PatternCraftingOrder> result = new ArrayList<>();
        for (PatternCraftingBranch branch : ingredientBranches) {
            collectChildStagedOrders(branch, result);
        }
        return result;
    }

    /**
     * Persists runtime-only scheduler state that is not part of the original request tree.
     */
    void writeRuntimeState(NBTTagCompound tag) {
        NBTTagList preRequested = new NBTTagList();
        for (Map.Entry<Integer, Integer> entry : preRequestedIngredients.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setInteger(PRE_INPUT_SLOT_TAG, entry.getKey());
            entryTag.setInteger(PRE_AMOUNT_TAG, entry.getValue());
            preRequested.appendTag(entryTag);
        }
        if (preRequested.tagCount() > 0) {
            tag.setTag(PRE_REQUESTED_INGREDIENTS_TAG, preRequested);
        }
    }

    /**
     * Restores runtime scheduler state saved with a staged order.
     */
    void readRuntimeState(NBTTagCompound tag) {
        preRequestedIngredients.clear();
        NBTTagList preRequested = tag.getTagList(PRE_REQUESTED_INGREDIENTS_TAG, TAG_COMPOUND);
        for (int i = 0; i < preRequested.tagCount(); i++) {
            NBTTagCompound entryTag = preRequested.getCompoundTagAt(i);
            int amount = entryTag.getInteger(PRE_AMOUNT_TAG);
            if (amount > 0) {
                preRequestedIngredients.put(entryTag.getInteger(PRE_INPUT_SLOT_TAG), amount);
            }
        }
    }

    /**
     * Appends this staged order and its ingredient branches to the crafting request debug dump.
     */
    void appendDebugState(StringBuilder out, String prefix) {
        out.append(prefix).append("- Pattern slot ").append(patternSlot).append(" remainingSets=").append(remainingSets)
                .append(" resultAmountPerSet=").append(resultAmountPerSet).append(" outputOrder=")
                .append(outputOrder == null ? "<none>" : outputOrder.getAsDisplayItem()).append(" branches=")
                .append(ingredientBranches.size()).append("\n");
        if (!preRequestedIngredients.isEmpty()) {
            out.append(prefix).append("  preRequested=").append(preRequestedIngredients).append("\n");
        }
        for (PatternCraftingBranch branch : ingredientBranches) {
            branch.appendDebugState(out, prefix + "  ");
        }
    }

    /**
     * Builds a renderer node whose count follows the live output order amount.
     */
    PatternCraftingMonitorNode toMonitorNode(Set<PatternCraftingOrder> visitedOrders) {
        visitedOrders.add(this);
        ItemIdentifierStack display = outputOrder.getAsDisplayItem().clone();
        display.setStackSize(Math.max(0, display.getStackSize()));
        PatternCraftingMonitorNode node = new PatternCraftingMonitorNode(
                display,
                0,
                display.getStackSize(),
                outputOrder.isInProgress() || !outputOrder.getProgresses().isEmpty());
        for (PatternCraftingBranch branch : ingredientBranches) {
            node.addChild(branch.toMonitorNode(visitedOrders));
        }
        return node;
    }

    /**
     * Returns the amount still available for one ingredient across matching branches.
     */
    private int availableFromBranches(PatternIngredientTarget ingredient) {
        int available = 0;
        for (PatternCraftingBranch branch : ingredientBranches) {
            if (branchMatches(branch, ingredient)) {
                available += branch.getRemainingAmount();
            }
        }
        return available;
    }

    private int preRequestedAmount(int inputSlot) {
        return Math.max(0, preRequestedIngredients.getOrDefault(inputSlot, 0));
    }

    private void addPreRequestedIngredient(int inputSlot, int amount) {
        if (amount <= 0) {
            return;
        }
        preRequestedIngredients.put(inputSlot, preRequestedAmount(inputSlot) + amount);
    }

    private void commitPreRequested(ItemStack pattern, int sets) {
        if (sets <= 0) {
            return;
        }
        for (PatternIngredientTarget ingredient : module.getIngredientTargets(pattern)) {
            int inputSlot = ingredient.inputSlot();
            int remaining = preRequestedAmount(inputSlot) - ingredient.stack().getAmount() * sets;
            if (remaining <= 0) {
                preRequestedIngredients.remove(inputSlot);
            } else {
                preRequestedIngredients.put(inputSlot, remaining);
            }
        }
    }

    private void collectChildStagedOrders(PatternCraftingBranch branch, List<PatternCraftingOrder> result) {
        for (IOrderInfoProvider order : branch.getLiveOrders()) {
            PatternCraftingOrder stagedOrder = PatternCraftingMonitorRegistry.find(order);
            if (stagedOrder != null && stagedOrder != this && !result.contains(stagedOrder)) {
                result.add(stagedOrder);
            }
        }
        for (PatternCraftingBranch child : branch.getSubRequests()) {
            collectChildStagedOrders(child, result);
        }
    }

    /**
     * Places provider or staged crafting orders for an ingredient, consuming the matching branch state as it goes.
     *
     * @return the amount that was requested
     */
    private int requestFromBranches(IPatternStack ingredient, int amount, int inputSlot,
            IRequestItems itemTargetOverride, IRequestFluid fluidTargetOverride) {
        int requested = 0;
        for (PatternCraftingBranch branch : ingredientBranches) {
            if (requested >= amount) {
                break;
            }
            if (!branchMatches(branch, ingredient, inputSlot)) {
                continue;
            }
            int before = branch.getRemainingAmount();
            if (PatternStackHelper.isFluid(ingredient)) {
                int branchRequested = branch.request(
                        amount - requested,
                        fluidTargetOverride,
                        new PatternTargetInformation(patternSlot, inputSlot));
                requested += branchRequested;
                module.debugEvent(
                        "REQUEST",
                        "branch fluid request slot=%d ingredient=%s target=%s branch=%s branchRemaining=%d->%d requested=%d total=%d/%d",
                        patternSlot,
                        ingredient,
                        fluidTargetOverride,
                        branch.getRequestType(),
                        before,
                        branch.getRemainingAmount(),
                        branchRequested,
                        requested,
                        amount);
            } else {
                int branchRequested = branch.request(
                        amount - requested,
                        itemTargetOverride,
                        new PatternTargetInformation(patternSlot, inputSlot));
                requested += branchRequested;
                module.debugEvent(
                        "REQUEST",
                        "branch item request slot=%d ingredient=%s target=%s branch=%s branchRemaining=%d->%d requested=%d total=%d/%d",
                        patternSlot,
                        ingredient,
                        itemTargetOverride,
                        branch.getRequestType(),
                        before,
                        branch.getRemainingAmount(),
                        branchRequested,
                        requested,
                        amount);
            }
        }
        return requested;
    }

    /**
     * Checks whether a staged branch can provide the requested item or fluid ingredient.
     */
    private boolean branchMatches(PatternCraftingBranch branch, PatternIngredientTarget ingredient) {
        return branchMatches(branch, ingredient.stack(), ingredient.inputSlot());
    }

    private boolean branchMatches(PatternCraftingBranch branch, IPatternStack ingredient, int inputSlot) {
        if (!branchTargetsInputSlot(branch, inputSlot)) {
            return false;
        }
        FluidIdentifier fluid = PatternStackHelper.asFluid(ingredient);
        if (fluid != null) {
            return branch.matches(fluid);
        }
        ItemIdentifier item = PatternStackHelper.getRoutingItem(ingredient);
        return item != null && branch.matches(item);
    }

    private boolean branchTargetsInputSlot(PatternCraftingBranch branch, int inputSlot) {
        if (branch.getTargetInformation() instanceof PatternTargetInformation target) {
            return target.inputSlot() == inputSlot || target.inputSlot() == PatternTargetInformation.NO_INPUT_SLOT;
        }
        return true;
    }

    private static class RequestedIngredient {

        private final PatternIngredientTarget ingredient;
        private final int amount;

        private RequestedIngredient(PatternIngredientTarget ingredient, int amount) {
            this.ingredient = ingredient;
            this.amount = amount;
        }
    }

}
