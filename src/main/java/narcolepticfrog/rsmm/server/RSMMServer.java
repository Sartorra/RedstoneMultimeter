package narcolepticfrog.rsmm.server;

import com.google.common.base.Charsets;
import io.netty.buffer.Unpooled;
import narcolepticfrog.rsmm.events.*;
import narcolepticfrog.rsmm.network.RSMMCPacket;
import narcolepticfrog.rsmm.network.RSMMSPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/**
 * This is the main server-side class for the redstone multimeter mod. It is responsible for handling
 * events generated by the minecraft server, for handling meter-related commands, and for managing
 * player subscriptions to meter groups.
 */
public class RSMMServer implements StateChangeListener, PistonPushListener, TickStartListener,
        PlayerConnectionListener, ServerPacketListener {

    public RSMMServer() {
        StateChangeEventDispatcher.addListener(this);
        PistonPushEventDispatcher.addListener(this);
        TickStartEventDispatcher.addListener(this);
        PlayerConnectionEventDispatcher.addListener(this);
        ServerPacketEventDispatcher.addListener(this);
    }

    /**
     * Maps a meter group name to the corresponding {@code MeterGroup} instance.
     */
    private LinkedHashMap<String, MeterGroup> meterGroups = new LinkedHashMap<>();

    /**
     * Maps player UUIDs to the name of the {@code MeterGroup} they are subscribed to.
     * Players can only subscribe to one meter group at a time.
     */
    private HashMap<UUID, String> playerSubscriptions = new HashMap<>();

    /**
     * Given an {@code EntityPlayerMP}, returns the {@code MeterGroup} that player is subscribed to.
     * If the player is not already subscribed to any meter group, they are subscribed to a meter group
     * with the same name as them. If the {@code MeterGroup} they subcribe to has not been created yet,
     * it is created here.
     * @param player The player entity.
     * @return The meter group that {@code player} is subscribed to.
     */
    private MeterGroup getOrCreateMeterGroup(EntityPlayerMP player) {
        UUID playerUUID = player.getUniqueID();
        if (!playerSubscriptions.containsKey(playerUUID)) {
            playerSubscriptions.put(playerUUID, player.getName());
        }
        String groupName = playerSubscriptions.get(playerUUID);
        if (!meterGroups.containsKey(groupName)) {
            meterGroups.put(groupName, new MeterGroup(this, groupName));
        }
        return meterGroups.get(groupName);
    }

    /**
     * The same as {@code getOrCreateMeterGroup}, except it returns {@code null} if either the player
     * is not subscribed to a meter group, or if the meter group they subscribe to is null.
     */
    private MeterGroup getMeterGroup(EntityPlayerMP player) {
        UUID playerUUID = player.getUniqueID();
        if (!playerSubscriptions.containsKey(playerUUID)) {
            return null;
        }
        String groupName = playerSubscriptions.get(playerUUID);
        if (!meterGroups.containsKey(groupName)) {
            return null;
        }
        return meterGroups.get(groupName);
    }

    /**
     * Returns the names of the current groups in order of creation time.
     */
    public Set<String> getGroupNames() {
        return meterGroups.keySet();
    }

    /**
     * A reference to the minecraft server.
     */
    private MinecraftServer minecraftServer;

    public MinecraftServer getMinecraftServer() {
        if (minecraftServer == null) {
            minecraftServer = Minecraft.getMinecraft().getIntegratedServer();
        }
        return minecraftServer;
    }

    /**
     * If {@code player} has registered on the RSMM plugin channel, then send {@code packet} to them.
     * @param player The player to receieve the packet.
     * @param packet The packet to be sent.
     */
    public void sendToPlayer(EntityPlayerMP player, RSMMCPacket packet) {
        HasClientChannels clientChannels = (HasClientChannels)player;
        if (clientChannels.getClientChannels().contains("RSMM")) {
            player.connection.sendPacket(new SPacketCustomPayload("RSMM", packet.toBuffer()));
        }
    }

    /**
     * Unsubscribes {@code player} from their current meter group and subscribes them to {@code groupName}.
     */
    public void subscribePlayerToGroup(EntityPlayerMP player, String groupName) {
        playerSubscriptions.put(player.getUniqueID(), groupName);
        getOrCreateMeterGroup(player).addPlayer(player);
    }

    public void unsubscribePlayerFromGroup(EntityPlayerMP player) {
        MeterGroup oldGroup = getMeterGroup(player);
        if (oldGroup != null) {
            oldGroup.removePlayer(player);
            if (oldGroup.getNumPlayers() == 0 && oldGroup.getNumMeters() == 0) {
                meterGroups.remove(oldGroup.getName());
            }
        }
    }


    /* ----- Meter Command Support ----- */

    public void changePlayerSubscription(EntityPlayerMP player, String groupName) {
        unsubscribePlayerFromGroup(player);
        subscribePlayerToGroup(player, groupName);
    }

    public int getNumMeters(EntityPlayerMP player) {
        MeterGroup mg = getMeterGroup(player);
        if (mg == null) {
            return 0;
        } else {
            return mg.getNumMeters();
        }
    }

    public void renameMeter(EntityPlayerMP player, int meterId, String name) {
        MeterGroup mg = getMeterGroup(player);
        if (mg != null) {
            mg.renameMeter(meterId, name);
        }
    }

    public void renameLastMeter(EntityPlayerMP player, String name) {
        MeterGroup mg = getMeterGroup(player);
        if (mg != null) {
            mg.renameLastMeter(name);
        }
    }

    public void recolorMeter(EntityPlayerMP player, int meterId, int color) {
        MeterGroup mg = getMeterGroup(player);
        if (mg != null) {
            mg.recolorMeter(meterId, color);
        }
    }

    public void recolorLastMeter(EntityPlayerMP player, int color) {
        MeterGroup mg = getMeterGroup(player);
        if (mg != null) {
            mg.recolorLastMeter(color);
        }
    }

    public void removeAllMeters(EntityPlayerMP player) {
        MeterGroup mg = getMeterGroup(player);
        if (mg != null) {
            mg.removeAllMeters();
        }
    }

    /* ----- Event Handlers ----- */

    @Override
    public void onPistonPush(World w, BlockPos pos, EnumFacing direction) {
        // Forward PistonPush events to each meter group
        for (MeterGroup mg : meterGroups.values()) {
            mg.onPistonPush(w, pos, direction);
        }
    }

    @Override
    public void onStateChange(World world, BlockPos pos) {
        // Forward the StateChange event to each meter group.
        for (MeterGroup mg : meterGroups.values()) {
            mg.onStateChange(world, pos);
        }
    }

    @Override
    public void onTickStart(int tick) {
        // Forward the TickStart event to each meter group.
        for (MeterGroup mg : meterGroups.values()) {
            mg.onTickStart(tick);
        }
    }

    @Override
    public void onPlayerConnect(EntityPlayerMP player) {
        // Register the RSMM plugin channel with the player.
        player.connection.sendPacket(new SPacketCustomPayload("REGISTER", new PacketBuffer(Unpooled.wrappedBuffer("RSMM".getBytes(Charsets.UTF_8)))));
    }

    @Override
    public void onPlayerDisconnect(EntityPlayerMP player) {
        unsubscribePlayerFromGroup(player);
    }

    @Override
    public void onCustomPayload(EntityPlayerMP sender, String channel, PacketBuffer data) {
        if ("RSMM".equals(channel)) {
            RSMMSPacket packet = RSMMSPacket.fromBuffer(data);
            if (packet == null) return;
            packet.process(getOrCreateMeterGroup(sender));
        }
    }

    @Override
    public void onChannelRegister(EntityPlayerMP sender, List<String> channels) {
        // Once the client has registered that it is on the RSMM channel, add them to their subscribed meter group.
        if (channels.contains("RSMM")) {
            getOrCreateMeterGroup(sender).addPlayer(sender);
        }
    }

    @Override
    public void onChannelUnregister(EntityPlayerMP sender, List<String> channels) {}

}
