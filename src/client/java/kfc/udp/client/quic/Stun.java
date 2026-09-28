package kfc.udp.client.quic;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * STUN(RFC 5389) 최소 구현 — Binding Request/Response만.
 * <p>
 * 필요한 게 두 가지뿐이라 라이브러리를 쓰지 않는다:
 * <ul>
 *   <li><b>공인 주소 알아내기</b> — coturn에 Binding Request를 보내고 XOR-MAPPED-ADDRESS를 읽는다.</li>
 *   <li><b>연결성 확인(홀펀칭)</b> — 상대 후보로 Request를 쏘고 Response가 오면 그 경로가 열린 것이다.
 *       동시에 상대가 보낸 Request에는 Response를 돌려줘야 상대도 자기 쪽 경로를 확인할 수 있다.</li>
 * </ul>
 * <b>MESSAGE-INTEGRITY(HMAC)는 넣지 않았다.</b> 진짜 ICE는 ice-pwd로 체크를 인증하는데, 여기서는
 * 위조된 체크가 성공해도 결과가 "엉뚱한 후보를 골라 QUIC 핸드셰이크가 실패"일 뿐이다 — 실제 인증은
 * 그 위의 TLS 1.3 + 인증서 지문 대조가 한다({@link QuicClient} 주석). 그리고 응답은 상대가 시그널링으로
 * 알려준 후보 주소에서 온 것만 받아들인다({@link QuicIce}).
 */
final class Stun {

    private static final int HEADER = 20;
    private static final int MAGIC = 0x2112A442;

    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_SUCCESS = 0x0101;
    private static final int XOR_MAPPED_ADDRESS = 0x0020;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Stun() {}

    /** 첫 2비트가 00이면 STUN — QUIC은 fixed bit 때문에 0x40이 선다(RFC 7983 다중화 규칙). */
    static boolean looksLikeStun(byte[] buf, int off, int len) {
        return len >= HEADER && (buf[off] & 0xC0) == 0;
    }

    static int messageType(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
    }

    static boolean isRequest(byte[] buf, int off) { return messageType(buf, off) == BINDING_REQUEST; }
    static boolean isSuccess(byte[] buf, int off) { return messageType(buf, off) == BINDING_SUCCESS; }

    /** 12바이트 트랜잭션 ID — 응답을 우리 요청과 짝지을 유일한 수단이다. */
    static byte[] newTransactionId() {
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        return id;
    }

    static byte[] transactionId(byte[] buf, int off) {
        return Arrays.copyOfRange(buf, off + 8, off + 20);
    }

    /** 속성 없는 Binding Request — 공인 주소를 알아낼 때(STUN 서버 대상) 쓴다. */
    static byte[] bindingRequest(byte[] txId) {
        byte[] m = new byte[HEADER];
        m[0] = 0x00; m[1] = 0x01;          // type
        m[2] = 0x00; m[3] = 0x00;          // length = 0
        writeInt(m, 4, MAGIC);
        System.arraycopy(txId, 0, m, 8, 12);
        return m;
    }

    /**
     * QUIC Initial 과 같은 크기로 채운 Binding Request — <b>연결성 확인에는 이걸 써야 한다</b>.
     * <p>
     * 20바이트 체크만으로 경로를 고르면, 작은 패킷은 통과하지만 1200바이트는 버리는 경로를
     * "열렸다"고 판정해 버린다. 실제로 그랬다: srflx 가 213ms 에 응답했는데 QUIC 핸드셰이크는
     * 그 경로로 끝까지 안 붙었고, 타임아웃을 다 쓴 뒤 relay 로 바뀌었다. 검증 패킷을 QUIC 과 같은
     * 크기로 만들면 그런 경로를 애초에 고르지 않는다.
     * <p>
     * 채우는 수단은 <b>comprehension-optional 속성</b>(타입 0x8000 이상)이다 — RFC 5389 §15 에 따라
     * 받는 쪽이 모르면 그냥 무시하므로 상대 구현이 무엇이든 안전하다.
     */
    static byte[] paddedBindingRequest(byte[] txId) {
        int payload = QUIC_INITIAL_SIZE - HEADER - 4; // 속성 헤더 4바이트를 뺀 나머지
        byte[] m = new byte[HEADER + 4 + payload];
        m[0] = 0x00; m[1] = 0x01;
        int len = 4 + payload;
        m[2] = (byte) (len >> 8); m[3] = (byte) len;
        writeInt(m, 4, MAGIC);
        System.arraycopy(txId, 0, m, 8, 12);
        // 우리끼리 쓰는 패딩 속성 — 0x8000 이상이라 모르는 쪽은 무시한다.
        m[HEADER] = (byte) 0xC0; m[HEADER + 1] = 0x00;
        m[HEADER + 2] = (byte) (payload >> 8); m[HEADER + 3] = (byte) payload;
        return m;
    }

    /**
     * QUIC 은 Initial 을 최소 1200바이트로 패딩한다(RFC 9000 §14.1). 그 크기가 지나갈 수 있는
     * 경로여야 핸드셰이크가 된다.
     */
    private static final int QUIC_INITIAL_SIZE = 1200;

    /** 요청에 대한 성공 응답 — 보낸 쪽이 자기 공인 주소를 알 수 있게 XOR-MAPPED-ADDRESS를 실어준다. */
    static byte[] bindingSuccess(byte[] txId, InetSocketAddress reflexive) {
        byte[] addr = reflexive.getAddress().getAddress();
        if (addr.length != 4) return null;                  // IPv4만 다룬다
        int attrLen = 8;                                    // 예약1 + family1 + port2 + addr4
        byte[] m = new byte[HEADER + 4 + attrLen];
        m[0] = 0x01; m[1] = 0x01;                           // Binding Success Response
        m[2] = (byte) ((4 + attrLen) >> 8); m[3] = (byte) (4 + attrLen);
        writeInt(m, 4, MAGIC);
        System.arraycopy(txId, 0, m, 8, 12);

        int p = HEADER;
        m[p] = 0x00; m[p + 1] = 0x20;                       // XOR-MAPPED-ADDRESS
        m[p + 2] = 0x00; m[p + 3] = (byte) attrLen;
        m[p + 4] = 0x00;
        m[p + 5] = 0x01;                                    // family = IPv4
        int xport = reflexive.getPort() ^ (MAGIC >>> 16);    // 포트는 쿠키 상위 16비트와 XOR
        m[p + 6] = (byte) (xport >> 8); m[p + 7] = (byte) xport;
        for (int i = 0; i < 4; i++) {                       // 주소는 쿠키 전체와 XOR
            m[p + 8 + i] = (byte) (addr[i] ^ (byte) (MAGIC >>> (24 - 8 * i)));
        }
        return m;
    }

    /** 응답에서 XOR-MAPPED-ADDRESS를 꺼낸다. 없으면 null. */
    static InetSocketAddress mappedAddress(byte[] buf, int off, int len) {
        int end = off + len;
        int p = off + HEADER;
        while (p + 4 <= end) {
            int type = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF);
            int alen = ((buf[p + 2] & 0xFF) << 8) | (buf[p + 3] & 0xFF);
            int body = p + 4;
            if (type == XOR_MAPPED_ADDRESS && alen >= 8 && body + 8 <= end) {
                int port = (((buf[body + 2] & 0xFF) << 8) | (buf[body + 3] & 0xFF)) ^ (MAGIC >>> 16);
                byte[] addr = new byte[4];
                for (int i = 0; i < 4; i++) {
                    addr[i] = (byte) (buf[body + 4 + i] ^ (byte) (MAGIC >>> (24 - 8 * i)));
                }
                try {
                    return new InetSocketAddress(java.net.InetAddress.getByAddress(addr), port);
                } catch (Exception e) {
                    return null;
                }
            }
            p = body + ((alen + 3) & ~3); // 속성은 4바이트 정렬
        }
        return null;
    }

    static DatagramPacket packet(byte[] msg, InetSocketAddress to) {
        return new DatagramPacket(msg, msg.length, to);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v;
    }
}
