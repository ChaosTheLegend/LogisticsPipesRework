package logisticspipes.network.packets.block;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

import logisticspipes.crafting.PatternLogisticsCraftingTableTileEntity;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.PacketGuards;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;

public class PatternCraftingTableUpdate extends CoordinatesPacket {

    private NBTTagCompound updatePayload;

    public PatternCraftingTableUpdate(int id) {
        super(id);
    }

    public PatternCraftingTableUpdate setUpdatePayload(NBTTagCompound updatePayload) {
        this.updatePayload = updatePayload;
        return this;
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeNBTTagCompound(updatePayload);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        updatePayload = data.readNBTTagCompound();
    }

    @Override
    public void processPacket(EntityPlayer player) {
        // Server-to-client only: the payload replaces the table inventories.
        if (!PacketGuards.isOnClient(player)) {
            return;
        }
        PatternLogisticsCraftingTableTileEntity tile = getTile(
                player.worldObj,
                PatternLogisticsCraftingTableTileEntity.class);
        if (tile != null && updatePayload != null) {
            tile.readUpdatePayload(updatePayload);
        }
    }

    @Override
    public ModernPacket template() {
        return new PatternCraftingTableUpdate(getId());
    }
}
