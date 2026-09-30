package logisticspipes.network.packets.crafting;

import logisticspipes.LogisticsPipes;
import logisticspipes.crafting.pattern.AbstractPattern;
import logisticspipes.crafting.pattern.DefaultPattern;
import logisticspipes.crafting.pattern.ItemPattern;
import logisticspipes.crafting.pattern.PatternContainer;
import logisticspipes.crafting.pattern.ProcessingPattern;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Accessors(chain = true)
public class NEISetPatternCraftingRecipe extends CoordinatesPacket {

    private List<IPatternStack> inputs = new ArrayList<>();
    private List<Integer> indices = new ArrayList<>();
    private List<IPatternStack> outputs = new ArrayList<>();
    private int patternInventorySlot = -1;
    private int pipePatternSlot = -1;
    private boolean processingPattern;

    public NEISetPatternCraftingRecipe(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {

        if (pipePatternSlot >= 0) {
            LogisticsTileGenericPipe tile = getPipe(player.worldObj);
            if (tile == null || !(tile.pipe instanceof PipeItemsPatternCraftingLogistics pipe)) return;
            ItemStack patternStack = pipe.getPatternModule().getPatternItemStack(pipePatternSlot);
            importRecipe(player, patternStack, inputs, indices, outputs, pipe.getPatternModule()::markPatternInventoryDirty);
        } else {
            importRecipe(player, patternInventorySlot, inputs, indices, outputs);
        }
    }

    public void importRecipe(EntityPlayer player, int patternInventorySlot, @NonNull List<IPatternStack> inputs,
            @NonNull List<Integer> indices, @NonNull List<IPatternStack> outputs) {
        if (patternInventorySlot < 0 || patternInventorySlot >= player.inventory.mainInventory.length) return;

        ItemStack patternStack = player.inventory.mainInventory[patternInventorySlot];
        importRecipe(player, patternStack, inputs, indices, outputs, player.inventory::markDirty);
    }

    private void importRecipe(EntityPlayer player, ItemStack patternStack, List<IPatternStack> inputs,
                              List<Integer> indices, List<IPatternStack> outputs, Runnable markDirty) {
        if (patternStack == null || patternStack.getItem() != LogisticsPipes.LogisticsPattern) return;

        if (!(player.openContainer instanceof PatternContainer container)
            || !container.isEditingPattern(patternStack)) return;

        // Validate before changing the type or clearing the existing recipe.
        AbstractPattern target = processingPattern ? new ProcessingPattern(patternStack)
            : new DefaultPattern(patternStack);
        if (outputs.isEmpty() || !target.canSetInputsAndOutputs(inputs, indices, outputs)) return;
        ItemPattern.setProcessingPattern(patternStack, processingPattern);
        AbstractPattern pattern = ItemPattern.fromStack(patternStack);
        pattern.setInputsAndOutputs(inputs, indices, outputs);
        markDirty.run();
        container.reloadFromPattern(pattern);
    }

    @Override
    public ModernPacket template() {
        return new NEISetPatternCraftingRecipe(getId());
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);

        data.writeInt(patternInventorySlot);
        data.writeInt(pipePatternSlot);
        data.writeBoolean(processingPattern);
        data.writeList(inputs, (data1, object) -> {
            var nbt = new NBTTagCompound();
            object.writeToNBT(nbt);
            data1.writeNBTTagCompound(nbt);
        });

        var indicesNBT = new NBTTagCompound();
        for (int i = 0; i < indices.size(); i++) {
            indicesNBT.setInteger(String.valueOf(i), indices.get(i));
        }
        data.writeNBTTagCompound(indicesNBT);

        data.writeList(outputs, (data1, object) -> {
            var nbt = new NBTTagCompound();
            object.writeToNBT(nbt);
            data1.writeNBTTagCompound(nbt);
        });
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);

        patternInventorySlot = data.readInt();
        pipePatternSlot = data.readInt();
        processingPattern = data.readBoolean();
        indices.clear();
        inputs = data.readList(data1 -> IPatternStack.readFromNBT(data1.readNBTTagCompound()));
        var indicesNBT = data.readNBTTagCompound();
        for (int i = 0; i < inputs.size(); i++) {
            indices.add(indicesNBT.getInteger(String.valueOf(i)));
        }
        outputs = data.readList(data1 -> IPatternStack.readFromNBT(data1.readNBTTagCompound()));
    }
}
