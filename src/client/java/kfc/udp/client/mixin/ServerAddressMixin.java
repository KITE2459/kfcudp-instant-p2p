package kfc.udp.client.mixin;

//? if >=26.1 {
/*import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
*///?} else {
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.MultiplayerServerListPinger;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.UnknownHostException;

//? if >=26.1 {
/*@Mixin(ServerStatusPinger.class)
*///?} else {
@Mixin(MultiplayerServerListPinger.class)
//?}
public class ServerAddressMixin {

    //? if >=26.1 {
    /*@Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void kfcudp$handleSpecialPing(
            ServerData entry, Runnable saver, Runnable pingCallback,
            net.minecraft.server.network.EventLoopGroupHolder backend, CallbackInfo ci)
            throws UnknownHostException {

        if (entry == null || entry.ip == null) return;

        String realAddress = null;
        if (entry.ip.startsWith("webrtc.")) {
            realAddress = entry.ip.substring("webrtc.".length());
        } else if (entry.ip.startsWith("kcp.")) {
            realAddress = entry.ip.substring("kcp.".length());
        }

        if (realAddress == null) return;

        ci.cancel();

        ServerData temp = new ServerData(entry.name, realAddress, ServerData.Type.OTHER);

        try {
            ((ServerStatusPinger)(Object)this).pingServer(temp, saver, () -> {
                entry.ping             = temp.ping;
                entry.motd            = temp.motd;
                entry.status = temp.status;
                entry.playerList = temp.playerList;
                entry.players          = temp.players;
                entry.protocol  = temp.protocol;
                entry.version          = temp.version;
                entry.setState(temp.state());
                pingCallback.run();
            }, backend);
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception ignored) {}
    }
    *///?}
    //? if >=1.21.11 <26.1 {
    /*@Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void kfcudp$handleSpecialPing(
            ServerInfo entry, Runnable saver, Runnable pingCallback,
            net.minecraft.network.NetworkingBackend backend, CallbackInfo ci)
            throws UnknownHostException {

        if (entry == null || entry.address == null) return;

        String realAddress = null;
        if (entry.address.startsWith("webrtc.")) {
            realAddress = entry.address.substring("webrtc.".length());
        } else if (entry.address.startsWith("kcp.")) {
            realAddress = entry.address.substring("kcp.".length());
        }

        if (realAddress == null) return;

        ci.cancel();

        ServerInfo temp = new ServerInfo(entry.name, realAddress, ServerInfo.ServerType.OTHER);

        try {
            ((MultiplayerServerListPinger)(Object)this).add(temp, saver, () -> {
                entry.ping             = temp.ping;
                entry.label            = temp.label;
                entry.playerCountLabel = temp.playerCountLabel;
                entry.playerListSummary = temp.playerListSummary;
                entry.players          = temp.players;
                entry.protocolVersion  = temp.protocolVersion;
                entry.version          = temp.version;
                entry.setStatus(temp.getStatus());
                pingCallback.run();
            }, backend);
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception ignored) {}
    }
    *///?}
    //? if <1.21.11 {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void kfcudp$handleSpecialPing(
            ServerInfo entry, Runnable saver, Runnable pingCallback, CallbackInfo ci)
            throws UnknownHostException {

        if (entry == null || entry.address == null) return;

        String realAddress = null;
        if (entry.address.startsWith("webrtc.")) {
            realAddress = entry.address.substring("webrtc.".length());
        } else if (entry.address.startsWith("kcp.")) {
            realAddress = entry.address.substring("kcp.".length());
        }

        if (realAddress == null) return;

        ci.cancel();

        ServerInfo temp = new ServerInfo(entry.name, realAddress, ServerInfo.ServerType.OTHER);

        try {
            ((MultiplayerServerListPinger)(Object)this).add(temp, saver, () -> {
                entry.ping             = temp.ping;
                entry.label            = temp.label;
                entry.playerCountLabel = temp.playerCountLabel;
                entry.playerListSummary = temp.playerListSummary;
                entry.players          = temp.players;
                entry.protocolVersion  = temp.protocolVersion;
                entry.version          = temp.version;
                entry.setStatus(temp.getStatus());
                pingCallback.run();
            });
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception ignored) {}
    }
    //?}
}
