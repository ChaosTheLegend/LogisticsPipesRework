package logisticspipes.nei;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.ChatComponentText;

import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.IRecipeHandler;
import logisticspipes.crafting.pattern.PatternGui;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.crafting.NEISetPatternCraftingRecipe;
import logisticspipes.proxy.MainProxy;

public class LogisticPatternHandler implements IOverlayHandler {

    private LogisticPatternHandler() {}

    public static final LogisticPatternHandler INSTANCE = new LogisticPatternHandler();

    @Override
    public void overlayRecipe(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        if (!(firstGui instanceof PatternGui gui)) return;

        NEIPatternRecipe imported = NEIPatternRecipe.read(recipe, recipeIndex);
        if (!imported.isValid()) {
            gui.mc.thePlayer.addChatMessage(new ChatComponentText("This recipe cannot fit in a Logistics Pattern."));
            return;
        }
        MainProxy.sendPacketToServer(
            PacketHandler.getPacket(NEISetPatternCraftingRecipe.class)
                .setPatternInventorySlot(gui.getInventorySlot())
                .setProcessingPattern(imported.isProcessing())
                .setInputs(imported.getInputs())
                .setIndices(imported.getIndices())
                .setOutputs(imported.getOutputs()));
    }

    @Override
    public int transferRecipe(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        overlayRecipe(firstGui, recipe, recipeIndex, false);
        return 0;
    }
}
