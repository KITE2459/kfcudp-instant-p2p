package kfc.udp.client.mixin;

import kfc.udp.client.webrtc.P2PBanManager;
//? if >=26.1 {
/*import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
*///?} else {
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 방장 화면에서 참여 메시지("X이(가) 게임에 참여했습니다") 뒤에 (직결 통신)/(중계 통신)
 * 접미사를 붙인다. webrtc 터널이 아닌 참여(방장 본인 등)는 접미사 없이 그대로 통과.
 */
//? if >=26.1 {
/*@Mixin(PlayerList.class)
*///?} else {
@Mixin(PlayerManager.class)
//?}
public abstract class PlayerJoinMessageMixin {

    //? if >=26.1 {
    /*@Redirect(
            method = "placeNewPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V")
    )
    private void kfcudp$appendConnectionType(PlayerList instance, Component message, boolean overlay,
            Connection connection, ServerPlayer player, CommonListenerCookie clientData) {
        Boolean relay = P2PBanManager.connectionTypeOf(player.getUUID());
        Component out = relay == null ? message : message.copy().append(" ").append(Component.translatable(
                relay ? "instant-p2p.msg.join_suffix_relay" : "instant-p2p.msg.join_suffix_direct"));
        instance.broadcastSystemMessage(out, overlay);
    }
    *///?} else {
    @Redirect(
            method = "onPlayerConnect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V")
    )
    private void kfcudp$appendConnectionType(PlayerManager instance, Text message, boolean overlay,
            ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData) {
        Boolean relay = P2PBanManager.connectionTypeOf(player.getUuid());
        Text out = relay == null ? message : message.copy().append(" ").append(Text.translatable(
                relay ? "instant-p2p.msg.join_suffix_relay" : "instant-p2p.msg.join_suffix_direct"));
        instance.broadcast(out, overlay);
    }
    //?}
}
