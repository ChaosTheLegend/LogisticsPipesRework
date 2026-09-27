package logisticspipes.network.packets.satpipe;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.crafting.PipeFluidPatternSatelliteLogistics;
import logisticspipes.crafting.PipeItemsPatternSatelliteLogistics;
import logisticspipes.network.PacketGuards;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.abstractpackets.StringCoordinatesPacket;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.proxy.MainProxy;

public class PatternSatelliteSetName extends StringCoordinatesPacket {

    public PatternSatelliteSetName(int id) {
        super(id);
    }

    @Override
    public ModernPacket template() {
        return new PatternSatelliteSetName(getId());
    }

    @Override
    public void processPacket(EntityPlayer player) {
        final LogisticsTileGenericPipe pipe = getPipe(player.worldObj);
        if (pipe == null) {
            return;
        }
        // The server copy of this packet comes from the satellite GUI; the client copy echoes the resolved name.
        if (MainProxy.isServer(player.worldObj) && (!(pipe.pipe instanceof CoreRoutedPipe routedPipe)
                || !PacketGuards.canConfigurePipe(player, routedPipe))) {
            return;
        }

        if (pipe.pipe instanceof PipeItemsPatternSatelliteLogistics satellite) {
            satellite.setSatelliteName(getString());
            sendResolvedName(player, satellite.getSatelliteName());
        } else if (pipe.pipe instanceof PipeFluidPatternSatelliteLogistics satellite) {
            satellite.setSatelliteName(getString());
            sendResolvedName(player, satellite.getSatelliteName());
        }
    }

    private void sendResolvedName(EntityPlayer player, String resolvedName) {
        if (MainProxy.isClient(player.worldObj)) {
            return;
        }
        MainProxy.sendPacketToPlayer(
                PacketHandler.getPacket(PatternSatelliteSetName.class)
                        .setString(resolvedName == null ? "" : resolvedName).setPosX(getPosX()).setPosY(getPosY())
                        .setPosZ(getPosZ()),
                player);
    }
}
