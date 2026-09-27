package kfc.udp.client.quic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 방장이 방을 열 때마다 새로 만드는 자체 서명 인증서 — QUIC은 TLS가 필수라 없으면 아예 못 뜬다.
 * <p>
 * <b>왜 직접 DER을 쓰는가</b> — JDK에는 인증서를 <i>만드는</i> 공개 API가 없다
 * ({@link CertificateFactory}는 파싱 전용). {@code sun.security.x509}는 export되지 않아
 * JVM 인자가 필요하고, BouncyCastle은 8MB짜리라 네이티브를 버리고 온 이유를 되돌린다.
 * 그래서 필요한 최소한의 ASN.1만 직접 조립한다 — 아래 {@code seq}/{@code tlv} 몇 줄이 전부고,
 * 서명과 키 생성은 JDK가 한다.
 * <p>
 * <b>신뢰는 인증서가 아니라 지문에서 온다</b> — CA가 없으니 인증서 내용(CN 등)은 아무 의미가 없다.
 * 방장이 {@link #fingerprint}를 시그널링으로 건네고 접속자가 그걸 대조한다. WebRTC가 SDP에
 * DTLS 지문을 실어 보내던 것과 똑같은 모델이다.
 */
public final class QuicCert {

    /** 방 하나 수명이면 충분하다 — 방을 열 때마다 새로 만든다. */
    private static final int VALID_DAYS = 2;

    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};                    // 2.5.4.3
    private static final byte[] OID_ECDSA_SHA256 =                                          // 1.2.840.10045.4.3.2
            {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};

    /** kwik에 넘길 KeyStore의 별칭·암호 — 메모리에만 있고 밖으로 나가지 않으니 고정값이어도 된다. */
    static final String ALIAS = "instant-p2p";
    static final char[] PASSWORD = "instant-p2p".toCharArray();

    private QuicCert() {}

    /** 방장용 키·인증서 한 쌍. */
    public record Identity(KeyStore keyStore, String fingerprint) {}

    /** 새 EC 키를 만들고 자체 서명 인증서를 발급해 kwik이 바로 쓸 수 있는 KeyStore로 싼다. */
    public static Identity generate() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1")); // RSA보다 작고 빠르다(생성 ~20ms)
        KeyPair kp = gen.generateKeyPair();

        X509Certificate cert = selfSigned(kp);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASSWORD, new Certificate[]{cert});
        return new Identity(ks, fingerprint(cert));
    }

    /** 접속자가 대조할 값 — 인증서 DER 전체의 SHA-256을 대문자 hex로. */
    public static String fingerprint(X509Certificate cert) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    // ── 자체 서명 X.509 v3 조립 ──────────────────────────────────────────────

    private static X509Certificate selfSigned(KeyPair kp) throws Exception {
        byte[] algId = seq(OID_ECDSA_SHA256);
        // issuer == subject (자체 서명). CN 값은 대조에 안 쓰이므로 고정 문자열.
        byte[] name = seq(set(seq(OID_CN, utf8("instant-p2p"))));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        byte[] tbs = seq(
                tlv(0xA0, integer(BigInteger.TWO)),            // version [0] EXPLICIT = v3
                integer(new BigInteger(64, new SecureRandom()).add(BigInteger.ONE)), // serial(양수 보장)
                algId,
                name,
                seq(utcTime(now.minusHours(1)), utcTime(now.plusDays(VALID_DAYS))),  // 시계 오차 여유 1시간
                name,
                kp.getPublic().getEncoded());                  // SubjectPublicKeyInfo — JDK가 이미 DER로 준다

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(kp.getPrivate());
        signer.update(tbs);
        byte[] der = seq(tbs, algId, bitString(signer.sign()));

        // JDK 자신의 파서로 되읽는다 — 우리가 쓴 DER이 실제로 유효한지 여기서 걸러진다.
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    // ── 최소한의 DER 쓰기 ────────────────────────────────────────────────────

    /** 길이 필드 — 인증서는 64KB를 넘지 않으므로 2바이트까지면 충분하다. */
    private static byte[] len(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        if (n < 0x100) return new byte[]{(byte) 0x81, (byte) n};
        return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
    }

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.writeBytes(p);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.writeBytes(len(b.length));
        out.writeBytes(b);
        return out.toByteArray();
    }

    private static byte[] seq(byte[]... parts) { return tlv(0x30, parts); }
    private static byte[] set(byte[]... parts) { return tlv(0x31, parts); }
    private static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }
    private static byte[] utf8(String s) { return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] utcTime(ZonedDateTime t) {
        String s = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
                .format(t.withZoneSameInstant(ZoneOffset.UTC));
        return tlv(0x17, s.getBytes(StandardCharsets.US_ASCII));
    }

    /** BIT STRING은 첫 바이트가 "남는 비트 수" — 바이트 정렬이라 항상 0. */
    private static byte[] bitString(byte[] raw) {
        byte[] padded = new byte[raw.length + 1];
        System.arraycopy(raw, 0, padded, 1, raw.length);
        return tlv(0x03, padded);
    }
}
