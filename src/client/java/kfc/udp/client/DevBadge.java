package kfc.udp.client;

//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?}

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
 * <p>
 * 실제 UUID 목록은 더 이상 여기 하드코딩돼 있지 않다 — {@link kfc.udp.client.webrtc.Roles}가
 * mc-signaling에서 받아온다(그쪽 클래스 주석 참고). 이 클래스는 그 목록을 이름 표시용으로
 * 소비하는 자리만 그대로 유지한다.
 */
public final class DevBadge {

    /** 특혜(방 정원 무시 + 인원 수에서 빠짐) 스위치 — false면 표시만 남고 특혜는 전부 꺼진다. */
    public static final boolean PERKS_ENABLED = true;

    private DevBadge() {}

    public static boolean hasBadge(UUID id) {
        return kfc.udp.client.webrtc.Roles.hasBadge(id);
    }

    /** 이름에 배지가 붙는 대상 전체 — 개발자·서포터·방송인·방장. hasBadge와 달리 방 정원 특혜는 안 준다. */
    public static boolean shouldDecorate(UUID id) {
        return roleSuffix(id) != null || isHostPlayer(id);
    }

    /** 지금 이 클라이언트 기준으로 이 UUID가 방장인지 — KfcudpClient.currentHostUuid 클래스 주석 참고. */
    public static boolean isHostPlayer(UUID id) {
        return id != null && id.equals(kfc.udp.client.KfcudpClient.currentHostUuid());
    }

    /** 방 정원을 무시하고 들어오며 인원 수에도 세지 않는다. */
    public static boolean hasPerk(UUID id) {
        return PERKS_ENABLED && hasBadge(id);
    }

    /**
     * 역할 번역 키 접미사 — {@code "dev"}/{@code "supporter"}/{@code "streamer"}, 셋 다 아니면 null.
     * <p>
     * <b>역할 우선순위(개발자 &gt; 서포터 &gt; 방송인)를 정하는 곳은 여기 하나뿐이다.</b> 한 사람이
     * 여러 역할을 동시에 가질 수 있어서(서포터이면서 방송인 등) 이 순서가 화면마다 어긋나면 같은
     * 사람이 화면마다 다른 역할로 보인다 — 실제로 ESC 일시정지 화면만 개발자 → 방송인 → 서포터
     * 순으로 복붙돼 있어서, 서포터 겸 방송인에게 "스트리머"라고 떴다. 새로 역할을 쓰는 곳이
     * 생기면 직접 isDev/isSupporter/isStreamer를 늘어놓지 말고 이걸 쓸 것.
     * {@code ExpelManager.priority}(3/2/1)도 같은 순서다.
     */
    public static String roleSuffix(UUID id) {
        // 접속자로 남의 방에 있는 동안은 방장이 내려준 등급이 먼저다 — 판정하는 쪽(방장)과 그리는
        // 쪽(나)이 서로 다른 roles.json 사본을 보면 화면과 실제가 어긋난다(RoomRoles 클래스 주석).
        Integer pushed = kfc.udp.client.webrtc.RoomRoles.rankOrNull(id);
        if (pushed != null) {
            return switch (pushed) {
                case 3 -> "dev";
                case 2 -> "supporter";
                case 1 -> "streamer";
                default -> null;
            };
        }
        if (kfc.udp.client.webrtc.Roles.isDev(id)) return "dev";
        if (kfc.udp.client.webrtc.Roles.isSupporter(id)) return "supporter";
        if (kfc.udp.client.webrtc.Roles.isStreamer(id)) return "streamer";
        return null;
    }

    /** 접속 직후 본인에게만 띄우는 안내 문구의 번역 키 — 해당 없으면 null. */
    public static String roleMessageKey(UUID id) {
        String suffix = roleSuffix(id);
        return suffix == null ? null : "instant-p2p.msg.you_are_" + suffix;
    }

    /** ESC 일시정지 화면의 역할 표기 번역 키 — 해당 없으면 null(=표기 안 함). */
    public static String rolePauseKey(UUID id) {
        String suffix = roleSuffix(id);
        return suffix == null ? null : "instant-p2p.pause.role_" + suffix;
    }

    /** 이름 뒤에 붙는 역할 이모지 — {@link #roleSuffix} 기준. */
    private static String roleEmoji(String suffix) {
        return switch (suffix) {
            case "dev" -> "🛠";
            case "supporter" -> "💬";
            default -> "🎧";
        };
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
    /*// 역할 색 — 이름 뒤 배지와 ESC 화면 표기가 같은 색을 쓰게 한다.
    public static ChatFormatting roleColor(UUID id) {
        String suffix = roleSuffix(id);
        if (suffix == null) return ChatFormatting.WHITE;
        return switch (suffix) {
            case "dev" -> ChatFormatting.AQUA;
            case "supporter" -> ChatFormatting.GOLD;
            default -> ChatFormatting.RED;
        };
    }

    public static Component decorate(UUID id, Component name) {
        // 방장이면 등급 배지 대신 방장 표시 하나만 — 등급자가 자기 방을 열었을 때 둘 다 붙어
        // 지저분해 보이는 걸 막는다(예: 개발자가 방장이면 "🛠 📶"가 아니라 "📶"만).
        if (isHostPlayer(id)) return name.copy().append(Component.literal(" 📶").withStyle(ChatFormatting.GREEN));
        String suffix = roleSuffix(id);
        if (suffix == null) return name;
        return name.copy().append(Component.literal(" " + roleEmoji(suffix)).withStyle(roleColor(id)));
    }
    *///?} else {
    /** 역할 색 — 이름 뒤 배지와 ESC 화면 표기가 같은 색을 쓰게 한다. */
    public static Formatting roleColor(UUID id) {
        String suffix = roleSuffix(id);
        if (suffix == null) return Formatting.WHITE;
        return switch (suffix) {
            case "dev" -> Formatting.AQUA;
            case "supporter" -> Formatting.GOLD;
            default -> Formatting.RED;
        };
    }

    public static Text decorate(UUID id, Text name) {
        // 방장이면 등급 배지 대신 방장 표시 하나만 — 등급자가 자기 방을 열었을 때 둘 다 붙어
        // 지저분해 보이는 걸 막는다(예: 개발자가 방장이면 "🛠 📶"가 아니라 "📶"만).
        if (isHostPlayer(id)) return name.copy().append(Text.literal(" 📶").formatted(Formatting.GREEN));
        String suffix = roleSuffix(id);
        if (suffix == null) return name;
        return name.copy().append(Text.literal(" " + roleEmoji(suffix)).formatted(roleColor(id)));
    }
    //?}
}
