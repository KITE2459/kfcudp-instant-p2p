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
 * 모드 제작자 표시 — 제작자 UUID의 이름 뒤에 하늘색 " 🛠"을 붙인다. 붙이는 방식은 이 클래스 하나로 통일한다.
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

    private static final UUID DEV_UUID = UUID.fromString("163ca181-ebe6-4e4a-85d9-2c651a52d059");

    private DevBadge() {}

    public static boolean isDev(UUID id) {
        return DEV_UUID.equals(id);
    }

    //? if >=26.1 {
    /*public static Component decorate(Component name) {
        return name.copy().append(Component.literal(" 🛠").withStyle(ChatFormatting.AQUA));
    }
    *///?} else {
    public static Text decorate(Text name) {
        return name.copy().append(Text.literal(" 🛠").formatted(Formatting.AQUA));
    }
    //?}
}
