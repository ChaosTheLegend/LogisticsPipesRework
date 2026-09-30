package logisticspipes.nei;

import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.IRecipeHandler;
import logisticspipes.crafting.PatternCraftingPipeGui;
import logisticspipes.crafting.pattern.PatternGui;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.crafting.NEISetPatternCraftingRecipe;
import logisticspipes.proxy.MainProxy;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.ChatComponentText;

public class LogisticPatternHandler implements IOverlayHandler {

    private LogisticPatternHandler() {}

    public static final LogisticPatternHandler INSTANCE = new LogisticPatternHandler();

    @Override
    public void overlayRecipe(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        if (!(firstGui instanceof PatternGui) && !(firstGui instanceof PatternCraftingPipeGui)) return;

        NEIPatternRecipe imported = NEIPatternRecipe.read(recipe, recipeIndex);
        if (!imported.isValid()) {
            firstGui.mc.thePlayer.addChatMessage(new ChatComponentText("This recipe cannot fit in a Logistics Pattern."));
            return;
        }
        NEISetPatternCraftingRecipe packet = PacketHandler.getPacket(NEISetPatternCraftingRecipe.class)
                .setProcessingPattern(imported.isProcessing())
                .setInputs(imported.getInputs())
                .setIndices(imported.getIndices())
            .setOutputs(imported.getOutputs());
        if (firstGui instanceof PatternGui gui) {
            packet.setPatternInventorySlot(gui.getInventorySlot());
        } else if (firstGui instanceof PatternCraftingPipeGui gui) {
            packet.setPipePatternSlot(gui.getSelectedPatternSlot());
            packet.setTilePos(gui.getPipe().container);
        }
        MainProxy.sendPacketToServer(packet);
    }

    @Override
    public int transferRecipe(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        overlayRecipe(firstGui, recipe, recipeIndex, false);
        return 0;
    }
}
