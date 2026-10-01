package logisticspipes.network.packets.gui;

import logisticspipes.LogisticsPipes;
import logisticspipes.crafting.PatternCraftingPipeGuiProvider;
import logisticspipes.crafting.pattern.AbstractPattern;
import logisticspipes.crafting.pattern.ItemPattern;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import java.io.IOException;

@Setter
@Getter
@Accessors(chain = true)
public class PatternPipeSlotActionPacket extends CoordinatesPacket {

    private int patternSlot;
    private int action;
    private int outputSlot;

    public PatternPipeSlotActionPacket(int id) {
        super(id);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        patternSlot = data.readInt();
        action = data.readInt();
        outputSlot = data.readInt();
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsTileGenericPipe tile = getPipe(player.worldObj);
        if (tile == null || !(tile.pipe instanceof PipeItemsPatternCraftingLogistics pipe)) {
            return;
        }
        ItemStack pattern = pipe.getPatternModule().getPatternItemStack(patternSlot);
        if (pattern == null || pattern.getItem() != LogisticsPipes.LogisticsPattern) {
            return;
        }
        AbstractPattern configuredPattern = ItemPattern.fromStack(pattern);
        if (action == PatternSlotActionPacket.Action.CLEAR.ordinal()) {
            configuredPattern.clear();
        } else if (action == PatternSlotActionPacket.Action.MULTIPLY_TWO.ordinal()) {
            configuredPattern.multiply(2);
        } else if (action == PatternSlotActionPacket.Action.TOGGLE_PROCESSING.ordinal()) {
            ItemPattern.toggleProcessingPattern(pattern);
            ((PatternCraftingPipeGuiProvider) pipe.getPatternModule().getPipeGuiProviderForModule())
                .setSelectedPatternSlot(patternSlot).setTilePos(pipe.container).open(player);
        } else if (action == PatternSlotActionPacket.Action.TOGGLE_ORE_DICT.ordinal()) {
            configuredPattern.toggleOreDictSubstitution();
        } else if (action == PatternSlotActionPacket.Action.TOGGLE_IGNORE_NBT.ordinal()) {
            configuredPattern.toggleIgnoreNbt();
        }
        if (action == PatternSlotActionPacket.Action.SELECT_MAIN_OUTPUT.ordinal()) {
            configuredPattern.setMainOutputSlot(outputSlot);
        }
        pipe.getPatternModule().markPatternInventoryDirty();
        if (player.openContainer != null) {
            player.openContainer.detectAndSendChanges();
        }
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeInt(patternSlot);
        data.writeInt(action);
        data.writeInt(outputSlot);
    }

    @Override
    public ModernPacket template() {
        return new PatternPipeSlotActionPacket(getId());
    }
}
