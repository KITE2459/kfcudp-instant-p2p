package kfc.udp.client.mixin;

import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.UnknownHostException;

@Mixin(MultiplayerServerListPinger.class)
public class ServerAddressMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void kfcudp$handleSpecialPing(
            ServerInfo entry, Runnable saver, Runnable pingCallback, CallbackInfo ci)
            throws UnknownHostException {

        if (entry == null || entry.address == null) return;

        String realAddress = null;
        if (entry.address.startsWith("webrtc.")) {
            realAddress = entry.address.substring("webrtc.".length());
        } else if (entry.address.startsWith("quic.")) {
            realAddress = entry.address.substring("quic.".length());
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
}