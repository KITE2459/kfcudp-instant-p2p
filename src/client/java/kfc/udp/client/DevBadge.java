package kfc.udp.client;

//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?}

import java.util.Set;
import java.util.UUID;

/**
 * 모드 제작자·서포터 표시 — 제작자는 하늘색 " 🛠", 서포터는 금색 " 💬"을 이름 뒤에 붙인다. 붙이는 방식은 이 클래스 하나로 통일한다.
 * <p>
 * 스코어보드 팀 suffix 대신 플레이어 표시 이름(getDisplayName) 자체에 붙인다(mixin.DevNameMixin) — 채팅, 입장·퇴장,
 * 사망 메시지, /say, 머리 위 이름표가 전부 이 이름을 쓴다. 탭 목록은 따로 받는 이름이라 같은 표시 이름을 그대로
 * 넘긴다(mixin.DevBadgeMixin). 팀으로 하면 한 사람은 팀 하나에만 들 수 있어 월드의 팀 구성을 깨고, /team으로
 * 떼어지거나 목록에 보인다 — 표시 이름에 넣으면 명령어 쪽에선 닉네임과 한 덩어리로만 보인다.
 * <p>
 * UUID는 Mojang 인증 값이라 온라인 모드 방에선 흉내 낼 수 없다. 이 클래스는 mixin 패키지 밖에 둔다 — mixin 패키지
 * 안의 클래스는 일반 클래스처럼 불러 쓸 수 없다.
 */
public final class DevBadge {

    /** 특혜(방 정원 무시 + 인원 수에서 빠짐) 스위치 — false면 표시만 남고 특혜는 전부 꺼진다. */
    public static final boolean PERKS_ENABLED = true;

    private static final UUID DEV_UUID = UUID.fromString("163ca181-ebe6-4e4a-85d9-2c651a52d059");
    private static final Set<UUID> SUPPORTER_UUIDS = Set.of(
            UUID.fromString("773e9c04-fe2d-4193-8911-6887a28d1757"),
            UUID.fromString("eb614533-1e04-47df-88d1-ad68e8859022"));

    private DevBadge() {}

    public static boolean hasBadge(UUID id) {
        return DEV_UUID.equals(id) || SUPPORTER_UUIDS.contains(id);
    }

    /** 방 정원을 무시하고 들어오며 인원 수에도 세지 않는다. */
    public static boolean hasPerk(UUID id) {
        return PERKS_ENABLED && hasBadge(id);
    }

    public static boolean isDev(UUID id) {
        return DEV_UUID.equals(id);
    }

    /** 접속 직후 본인에게만 띄우는 안내 문구의 번역 키 — 해당 없으면 null. */
    public static String roleMessageKey(UUID id) {
        if (DEV_UUID.equals(id)) return "instant-p2p.msg.you_are_dev";
        if (SUPPORTER_UUIDS.contains(id)) return "instant-p2p.msg.you_are_supporter";
        return null;
    }

    /**
     * 지금 실제로 instant-p2p로 통신 중인지 — 내가 Custom Room으로 방을 열었거나(KfcudpClient.isRoomActive),
     * webrtc로 남의 방에 접속자로 들어간 상태(WebRtcBridge.getActiveConnectionUsesRelay)일 때만 true다.
     * <p>
     * DevNameMixin/DevBadgeMixin이 이걸로 표시 여부를 가른다 — 이게 없으면 그냥 연 싱글플레이·LAN이나 이 모드와
     * 무관한 일반 서버에서도 UUID만 맞으면 배지가 붙어버린다(이름표·채팅은 클라이언트가 접속한 모든 서버에서,
     * 탭 목록은 호스팅 중인 통합 서버라면 전부 이 검사를 거치기 때문).
     */
    public static boolean isP2pSessionActive() {
        return kfc.udp.client.KfcudpClient.isRoomActive()
                || kfc.udp.client.webrtc.WebRtcBridge.getActiveConnectionUsesRelay() != null;
    }

    //? if >=26.1 {
    /*public static Component decorate(UUID id, Component name) {
        if (DEV_UUID.equals(id)) return name.copy().append(Component.literal(" 🛠").withStyle(ChatFormatting.AQUA));
        if (SUPPORTER_UUIDS.contains(id)) return name.copy().append(Component.literal(" 💬").withStyle(ChatFormatting.GOLD));
        return name;
    }
    *///?} else {
    public static Text decorate(UUID id, Text name) {
        if (DEV_UUID.equals(id)) return name.copy().append(Text.literal(" 🛠").formatted(Formatting.AQUA));
        if (SUPPORTER_UUIDS.contains(id)) return name.copy().append(Text.literal(" 💬").formatted(Formatting.GOLD));
        return name;
    }
    //?}
}
