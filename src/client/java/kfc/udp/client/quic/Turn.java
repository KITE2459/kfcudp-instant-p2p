package kfc.udp.client.quic;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * TURN(RFC 5766/8656) 최소 구현 — Allocate / CreatePermission / ChannelBind / Refresh 와 ChannelData 프레이밍.
 * <p>
 * 홀펀칭이 안 되는 경우(양쪽이 대칭 NAT 등)에 coturn 을 거쳐 돌아가는 경로를 만든다. 우리가 필요한 건
 * 딱 네 가지 요청과 4바이트 프레이밍뿐이라 라이브러리를 쓰지 않는다.
 * <p>
 * <b>STUN 과 달리 인증이 필수다.</b> Allocate 는 처음에 반드시 {@code 401 Unauthorized} 로 REALM/NONCE 를
 * 돌려주고, 그걸 담아 다시 보내야 한다(long-term credential). 서명 키는
 * {@code MD5(username:realm:password)} 이고 MESSAGE-INTEGRITY 는 그 키로 계산한 HMAC-SHA1 이다.
 * <p>
 * <b>왜 ChannelBind 인가</b> — Send/Data indication 은 패킷마다 36바이트 헤더가 붙는데, ChannelData 는
 * <b>4바이트</b>다. 마크 트래픽은 작은 패킷이 많아 이 차이가 그대로 대역폭·CPU 로 나타난다. 우리가
 * 방금 coturn 에서 시스콜을 깎은 것과 같은 이유다.
 */
final class Turn {

    private static final int HEADER = 20;
    private static final int MAGIC = 0x2112A442;

    // 메서드
    static final int ALLOCATE = 0x0003;
    static final int REFRESH = 0x0004;
    static final int CREATE_PERMISSION = 0x0008;
    static final int CHANNEL_BIND = 0x0009;

    // 클래스 비트 — type = (method & 0xFFF) | class
    private static final int CLASS_REQUEST = 0x0000;
    private static final int CLASS_SUCCESS = 0x0100;
    private static final int CLASS_ERROR = 0x0110;

    // 속성
    private static final int ATTR_USERNAME = 0x0006;
    private static final int ATTR_MESSAGE_INTEGRITY = 0x0008;
    private static final int ATTR_ERROR_CODE = 0x0009;
    private static final int ATTR_REALM = 0x0014;
    private static final int ATTR_NONCE = 0x0015;
    private static final int ATTR_XOR_PEER_ADDRESS = 0x0012;
    private static final int ATTR_XOR_RELAYED_ADDRESS = 0x0016;
    private static final int ATTR_REQUESTED_TRANSPORT = 0x0019;
    private static final int ATTR_LIFETIME = 0x000D;
    private static final int ATTR_CHANNEL_NUMBER = 0x000C;

    /** Data indication — 상대가 우리 relayed 주소로 보낸 것이 이 형태로 도착한다. */
    private static final int DATA_INDICATION = 0x0017;
    private static final int ATTR_DATA = 0x0013;

    /** 채널 번호로 쓸 수 있는 범위 — 상대마다 하나씩 배정한다(한 번호에 상대 하나만 붙는다). */
    static final int CHANNEL_MIN = 0x4000;
    static final int CHANNEL_MAX = 0x7FFF;

    private Turn() {}

    /** 401 이 돌려준 것들 — 다음 요청에 그대로 실어야 한다. */
    record Challenge(String realm, String nonce) {}

    /** 요청 하나를 만드는 데 필요한 인증 상태. realm/nonce 가 null 이면 서명 없이(=첫 Allocate) 만든다. */
    record Credentials(String username, String password, String realm, String nonce) {
        boolean signed() { return realm != null && nonce != null; }
    }

    // ── 요청 만들기 ──────────────────────────────────────────────────────────

    static byte[] allocate(byte[] txId, Credentials cred, int lifetimeSec) {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        // REQUESTED-TRANSPORT: UDP(17) 를 상위 바이트에 두고 나머지는 0
        attrs.writeBytes(attr(ATTR_REQUESTED_TRANSPORT, new byte[]{17, 0, 0, 0}));
        attrs.writeBytes(attr(ATTR_LIFETIME, int32(lifetimeSec)));
        return finish(ALLOCATE | CLASS_REQUEST, txId, attrs.toByteArray(), cred);
    }

    static byte[] refresh(byte[] txId, Credentials cred, int lifetimeSec) {
        return finish(REFRESH | CLASS_REQUEST, txId, attr(ATTR_LIFETIME, int32(lifetimeSec)), cred);
    }

    static byte[] createPermission(byte[] txId, Credentials cred, InetSocketAddress peer) {
        return finish(CREATE_PERMISSION | CLASS_REQUEST, txId, xorAddr(ATTR_XOR_PEER_ADDRESS, peer, txId), cred);
    }

    static byte[] channelBind(byte[] txId, Credentials cred, int channel, InetSocketAddress peer) {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        attrs.writeBytes(attr(ATTR_CHANNEL_NUMBER, new byte[]{(byte) (channel >> 8), (byte) channel, 0, 0}));
        attrs.writeBytes(xorAddr(ATTR_XOR_PEER_ADDRESS, peer, txId));
        return finish(CHANNEL_BIND | CLASS_REQUEST, txId, attrs.toByteArray(), cred);
    }

    // ── 응답 읽기 ────────────────────────────────────────────────────────────

    static int method(byte[] b, int off) { return messageType(b, off) & 0x0FFF; }
    static boolean isSuccess(byte[] b, int off) { return (messageType(b, off) & 0x0110) == CLASS_SUCCESS; }
    static boolean isError(byte[] b, int off) { return (messageType(b, off) & 0x0110) == CLASS_ERROR; }

    private static int messageType(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** 401/438 응답에서 REALM/NONCE 를 꺼낸다. 없으면 null. */
    static Challenge challenge(byte[] b, int off, int len) {
        String realm = text(find(b, off, len, ATTR_REALM), b);
        String nonce = text(find(b, off, len, ATTR_NONCE), b);
        return (realm != null && nonce != null) ? new Challenge(realm, nonce) : null;
    }

    /** 응답의 NONCE — 438(Stale Nonce) 뒤 새 nonce 를 꺼낼 때 쓴다. 없으면 null. */
    static String nonce(byte[] b, int off, int len) {
        return text(find(b, off, len, ATTR_NONCE), b);
    }

    static int errorCode(byte[] b, int off, int len) {
        int[] a = find(b, off, len, ATTR_ERROR_CODE);
        if (a == null || a[1] < 4) return -1;
        return (b[a[0] + 2] & 0x07) * 100 + (b[a[0] + 3] & 0xFF);
    }

    /** Allocate 성공 응답의 XOR-RELAYED-ADDRESS — 상대가 우리에게 보낼 주소다. */
    static InetSocketAddress relayedAddress(byte[] b, int off, int len, byte[] txId) {
        int[] a = find(b, off, len, ATTR_XOR_RELAYED_ADDRESS);
        return a != null ? parseXorAddr(b, a[0], a[1], txId) : null;
    }

    // ── Data indication ──────────────────────────────────────────────────────

    /**
     * 상대가 우리 relayed 주소로 보낸 첫 패킷은 Data indication 으로 도착한다 — 채널을 붙이기
     * 전이라 그렇다. 여기서 <b>상대의 실제 주소를 처음 알게 된다</b>: 대칭 NAT 라면 시그널링으로
     * 받은 후보와 포트가 다르므로, 채널을 붙일 대상은 반드시 이 값이어야 한다.
     */
    static boolean isDataIndication(byte[] b, int off, int len) {
        return len >= HEADER && messageType(b, off) == DATA_INDICATION;
    }

    /** Data indication 의 XOR-PEER-ADDRESS — 상대의 실제 주소. */
    static InetSocketAddress dataPeer(byte[] b, int off, int len, byte[] txId) {
        int[] a = find(b, off, len, ATTR_XOR_PEER_ADDRESS);
        return a != null ? parseXorAddr(b, a[0], a[1], txId) : null;
    }

    /** Data indication 의 DATA 속성 위치 {offset, length}. 없으면 null. */
    static int[] dataPayload(byte[] b, int off, int len) {
        return find(b, off, len, ATTR_DATA);
    }

    // ── ChannelData 프레이밍 ─────────────────────────────────────────────────

    /** 채널 데이터인가 — 첫 2비트가 01(0x40~0x7F)이면 그렇다. STUN(00)·QUIC 과 구분된다. */
    static boolean isChannelData(byte[] b, int off, int len) {
        return len >= 4 && (b[off] & 0xC0) == 0x40;
    }

    static int channelOf(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static int channelDataLength(byte[] b, int off) {
        return ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 페이로드를 4바이트 채널 헤더로 감싼다. UDP 라 4바이트 패딩은 필요 없다. */
    static byte[] wrapChannelData(int channel, byte[] payload, int off, int len) {
        byte[] out = new byte[4 + len];
        out[0] = (byte) (channel >> 8); out[1] = (byte) channel;
        out[2] = (byte) (len >> 8); out[3] = (byte) len;
        System.arraycopy(payload, off, out, 4, len);
        return out;
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    /** 속성 목록 뒤에 (필요하면) MESSAGE-INTEGRITY 를 붙여 완성한다. */
    private static byte[] finish(int type, byte[] txId, byte[] attrs, Credentials cred) {
        if (!cred.signed()) return message(type, txId, attrs);

        ByteArrayOutputStream withAuth = new ByteArrayOutputStream();
        withAuth.writeBytes(attrs);
        withAuth.writeBytes(attr(ATTR_USERNAME, cred.username().getBytes(StandardCharsets.UTF_8)));
        withAuth.writeBytes(attr(ATTR_REALM, cred.realm().getBytes(StandardCharsets.UTF_8)));
        withAuth.writeBytes(attr(ATTR_NONCE, cred.nonce().getBytes(StandardCharsets.UTF_8)));
        byte[] body = withAuth.toByteArray();

        // MESSAGE-INTEGRITY 는 "자기 자신까지 포함한 길이"를 헤더에 써 놓고 계산한다(RFC 5389 15.4).
        byte[] partial = message(type, txId, body, body.length + 4 + 20);
        byte[] mac = hmacSha1(longTermKey(cred), partial);

        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.writeBytes(body);
        full.writeBytes(attr(ATTR_MESSAGE_INTEGRITY, mac));
        return message(type, txId, full.toByteArray());
    }

    /** long-term credential 키 = MD5(username:realm:password). */
    private static byte[] longTermKey(Credentials c) {
        try {
            String s = c.username() + ":" + c.realm() + ":" + c.password();
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] message(int type, byte[] txId, byte[] attrs) {
        return message(type, txId, attrs, attrs.length);
    }

    /** declaredLength 를 따로 받는 건 MESSAGE-INTEGRITY 계산 때문이다(위 finish 주석). */
    private static byte[] message(int type, byte[] txId, byte[] attrs, int declaredLength) {
        byte[] m = new byte[HEADER + attrs.length];
        m[0] = (byte) (type >> 8); m[1] = (byte) type;
        m[2] = (byte) (declaredLength >> 8); m[3] = (byte) declaredLength;
        m[4] = (byte) (MAGIC >>> 24); m[5] = (byte) (MAGIC >>> 16);
        m[6] = (byte) (MAGIC >>> 8);  m[7] = (byte) MAGIC;
        System.arraycopy(txId, 0, m, 8, 12);
        System.arraycopy(attrs, 0, m, HEADER, attrs.length);
        return m;
    }

    /** TLV + 4바이트 정렬 패딩. */
    private static byte[] attr(int type, byte[] value) {
        int pad = (4 - (value.length % 4)) % 4;
        byte[] out = new byte[4 + value.length + pad];
        out[0] = (byte) (type >> 8); out[1] = (byte) type;
        out[2] = (byte) (value.length >> 8); out[3] = (byte) value.length;
        System.arraycopy(value, 0, out, 4, value.length);
        return out;
    }

    private static byte[] int32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** XOR-PEER-ADDRESS 등 — 포트는 쿠키 상위 16비트, 주소는 쿠키 전체와 XOR. */
    private static byte[] xorAddr(int type, InetSocketAddress addr, byte[] txId) {
        byte[] ip = addr.getAddress().getAddress();
        if (ip.length != 4) throw new IllegalArgumentException("IPv4 만 지원");
        byte[] v = new byte[8];
        v[0] = 0; v[1] = 0x01;
        int xport = addr.getPort() ^ (MAGIC >>> 16);
        v[2] = (byte) (xport >> 8); v[3] = (byte) xport;
        for (int i = 0; i < 4; i++) v[4 + i] = (byte) (ip[i] ^ (byte) (MAGIC >>> (24 - 8 * i)));
        return attr(type, v);
    }

    private static InetSocketAddress parseXorAddr(byte[] b, int off, int len, byte[] txId) {
        if (len < 8) return null;
        int port = (((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF)) ^ (MAGIC >>> 16);
        byte[] ip = new byte[4];
        for (int i = 0; i < 4; i++) ip[i] = (byte) (b[off + 4 + i] ^ (byte) (MAGIC >>> (24 - 8 * i)));
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
        } catch (Exception e) {
            return null;
        }
    }

    /** 속성을 찾아 {값 offset, 값 length} 를 돌려준다. 없으면 null. */
    private static int[] find(byte[] b, int off, int len, int wanted) {
        int end = off + len;
        int p = off + HEADER;
        while (p + 4 <= end) {
            int type = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            int alen = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            int body = p + 4;
            if (body + alen > end) return null;
            if (type == wanted) return new int[]{body, alen};
            p = body + ((alen + 3) & ~3);
        }
        return null;
    }

    private static String text(int[] a, byte[] b) {
        return a != null ? new String(Arrays.copyOfRange(b, a[0], a[0] + a[1]), StandardCharsets.UTF_8) : null;
    }
}
