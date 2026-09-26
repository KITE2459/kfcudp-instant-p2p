package kfc.udp.client.webrtc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

//? if >=26.1 {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
*///?} else {
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
//?}

/**
 * 방장과 접속자가 주고받는 이 모드 전용 패킷 2종.
 * <p>
 * <b>예전엔 숨은 채팅 마커였다</b>({@code kfcudp:capacity:}, {@code kfcudp:roles:},
 * {@code kfcudp:expeltoken:}, {@code kfcudp:expel:} 등) — 새 패킷을 안 만들고 이미 연결된 바닐라
 * 채팅 경로를 재사용하려던 것이었는데, 시스템 메시지엔 발신자 인증이 없다는 구멍이 있었다.
 * 방 옵션 "명령어 허용"이 켜진 방에서 접속자가 {@code /say kfcudp:roles:<내UUID>=3} 같은 걸 치면
 * 다른 접속자의 클라이언트가 그걸 방장이 보낸 것으로 믿었다 — 남의 화면에서 자기를 개발자로
 * 위장하거나(등급 배지 + 강퇴 면역), {@code kfcudp:expeltoken:FAKE}로 남의 토큰을 덮어써
 * 스트리머의 강퇴 기능 자체를 무력화할 수 있었다. 저격러 대응이 목적인 기능이라 그냥 둘 수 없다.
 * <p>
 * 커스텀 페이로드는 <b>서버만 보낼 수 있고 임의 내용을 뱉게 만드는 명령이 없어서</b> 그 구멍이
 * 통째로 사라진다. 덤으로 세션 토큰({@code kfcudp:expeltoken:})도 필요 없어졌다 — 누가 보냈는지는
 * 연결 자체가 보증하므로, "진짜 차단 버튼을 거쳤는지"를 토큰으로 증명할 이유가 없다.
 * <p>
 * 마커 6개가 패킷 2개로 줄었다:
 * <ul>
 *   <li>{@link RoomState} (방장 → 접속자) — 정원·방장 UUID·방송 허용·등급 목록을 한 번에.
 *       예전엔 capacity/roles/expeltoken 세 마커가 따로 날아와 도착 순서를 신경 써야 했다.</li>
 *   <li>{@link Moderation} (접속자 → 방장) — 추방/해제/강퇴 요청.</li>
 * </ul>
 */
public final class P2PNet {

    private P2PNet() {}

    /** 등급 0은 목록에 넣지 않는다(없으면 0). {@link RoomState#ranks}가 이 규칙을 따른다. */
    public static final int ACTION_EXPEL = 0;
    public static final int ACTION_READMIT = 1;
    public static final int ACTION_KICK = 2;
    /** 등급과 무관하게 누구나 보낼 수 있는 요청 — 방장이 {@link RoomState}를 다시 내려보낸다.
     * 접속자가 자기 접속 완료 시점에 직접 요청하므로, 방장이 JOIN에서 먼저 보내려 할 때 생기는
     * 채널 등록 타이밍 경쟁을 타지 않는다(RoomRoles.register 주석 참고). target은 안 쓴다. */
    public static final int ACTION_REQUEST_STATE = 3;

    //? if >=26.1 {
    /*// ── 방장 -> 접속자: 방 상태 한 묶음 ──────────────────────────────────────
    public record RoomState(int maxPlayers, UUID hostUuid, boolean allowBroadcast,
                            Map<UUID, Integer> ranks) implements CustomPacketPayload {

        // createType(String)은 문자열을 namespace 없는 path로 보고 minecraft: 를 붙인다 —
        // "instant-p2p:room_state"를 그대로 넘기면 minecraft:instant-p2p:room_state가 되어
        // path에 ':' 가 들어가 IdentifierException으로 게임이 안 뜬다. Identifier를 직접 만든다.
        public static final CustomPacketPayload.Type<RoomState> ID = new CustomPacketPayload.Type<>(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("instant-p2p", "room_state"));
        public static final StreamCodec<FriendlyByteBuf, RoomState> CODEC =
                CustomPacketPayload.codec(RoomState::write, RoomState::new);

        private RoomState(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readUUID(), buf.readBoolean(), readRanks(buf));
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.maxPlayers);
            buf.writeUUID(this.hostUuid);
            buf.writeBoolean(this.allowBroadcast);
            buf.writeVarInt(this.ranks.size());
            for (Map.Entry<UUID, Integer> e : this.ranks.entrySet()) {
                buf.writeUUID(e.getKey());
                buf.writeVarInt(e.getValue());
            }
        }

        private static Map<UUID, Integer> readRanks(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            Map<UUID, Integer> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                UUID id = buf.readUUID();
                out.put(id, buf.readVarInt());
            }
            return Map.copyOf(out);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    // ── 접속자 -> 방장: 추방/해제/강퇴 요청 ──────────────────────────────────
    public record Moderation(int action, UUID target) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Moderation> ID = new CustomPacketPayload.Type<>(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("instant-p2p", "moderation"));
        public static final StreamCodec<FriendlyByteBuf, Moderation> CODEC =
                CustomPacketPayload.codec(Moderation::write, Moderation::new);

        private Moderation(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readUUID());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.action);
            buf.writeUUID(this.target);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }
    *///?} else {
    // ── 방장 -> 접속자: 방 상태 한 묶음 ──────────────────────────────────────
    public record RoomState(int maxPlayers, UUID hostUuid, boolean allowBroadcast,
                            Map<UUID, Integer> ranks) implements CustomPayload {

        // CustomPayload.id(String)은 문자열을 namespace 없는 path로 보고 minecraft: 를 붙인다 —
        // "instant-p2p:room_state"를 그대로 넘기면 minecraft:instant-p2p:room_state가 되어
        // path에 ':' 가 들어가 IdentifierException으로 게임이 안 뜬다. Identifier를 직접 만든다.
        public static final CustomPayload.Id<RoomState> ID = new CustomPayload.Id<>(
                net.minecraft.util.Identifier.of("instant-p2p", "room_state"));
        public static final PacketCodec<PacketByteBuf, RoomState> CODEC =
                CustomPayload.codecOf(RoomState::write, RoomState::new);

        private RoomState(PacketByteBuf buf) {
            this(buf.readVarInt(), buf.readUuid(), buf.readBoolean(), readRanks(buf));
        }

        private void write(PacketByteBuf buf) {
            buf.writeVarInt(this.maxPlayers);
            buf.writeUuid(this.hostUuid);
            buf.writeBoolean(this.allowBroadcast);
            buf.writeVarInt(this.ranks.size());
            for (Map.Entry<UUID, Integer> e : this.ranks.entrySet()) {
                buf.writeUuid(e.getKey());
                buf.writeVarInt(e.getValue());
            }
        }

        private static Map<UUID, Integer> readRanks(PacketByteBuf buf) {
            int n = buf.readVarInt();
            Map<UUID, Integer> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                UUID id = buf.readUuid();
                out.put(id, buf.readVarInt());
            }
            return Map.copyOf(out);
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ── 접속자 -> 방장: 추방/해제/강퇴 요청 ──────────────────────────────────
    public record Moderation(int action, UUID target) implements CustomPayload {

        public static final CustomPayload.Id<Moderation> ID = new CustomPayload.Id<>(
                net.minecraft.util.Identifier.of("instant-p2p", "moderation"));
        public static final PacketCodec<PacketByteBuf, Moderation> CODEC =
                CustomPayload.codecOf(Moderation::write, Moderation::new);

        private Moderation(PacketByteBuf buf) {
            this(buf.readVarInt(), buf.readUuid());
        }

        private void write(PacketByteBuf buf) {
            buf.writeVarInt(this.action);
            buf.writeUuid(this.target);
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
    //?}

    /**
     * 페이로드 타입 등록 — KfcudpClient.onInitializeClient에서 <b>수신 핸들러보다 먼저</b> 1회 부른다.
     * 방장 노릇과 접속자 노릇을 같은 모드가 둘 다 하므로 두 방향을 한자리에서 등록한다.
     */
    // PayloadTypeRegistry의 접근자 이름이 Fabric networking-api 6.x(=26.1부터)에서
    // playS2C/playC2S -> clientboundPlay/serverboundPlay로 바뀌었다(jar 전수 확인).
    // 나머지(ServerPlayNetworking.send, registerGlobalReceiver, ClientPlayNetworking.send)는
    // 두 에라가 같은 모양이라 분기가 필요 없다.
    //? if >=26.1 {
    /*public static void registerTypes() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(RoomState.ID, RoomState.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Moderation.ID, Moderation.CODEC);
    }
    *///?} else {
    public static void registerTypes() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(RoomState.ID, RoomState.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(Moderation.ID, Moderation.CODEC);
    }
    //?}
}
