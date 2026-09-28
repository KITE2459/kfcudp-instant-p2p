package kfc.udp.client.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 시그널링 서버가 알려주는 배포 버전과 내 버전을 비교한다.
 * <p>
 * <b>내가 더 낮을 때만</b> 안내를 띄운다. 같거나 높으면 아무 말도 하지 않는다 — 개발 중인 빌드가
 * 배포본보다 앞서는 게 정상이고, 그때마다 경고가 뜨면 방해만 된다.
 * <p>
 * 서버 쪽은 {@code /home/opc/instant-p2p/version.json} 을 읽어 {@code /api/v1/version} 으로 내려준다.
 * 파일이 없거나 깨져 있으면 서버가 빈 객체를 주고, 그러면 이 기능은 그냥 꺼진다 — 버전 안내
 * 때문에 접속이 막히면 안 된다.
 */
public final class ModVersionCheck {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-version");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** null = 아직 모름 또는 확인 불가. true = 내 버전이 배포본보다 낮다. */
    private static volatile Boolean outdated;
    /** 서버가 함께 내려준 추가 안내(비어 있을 수 있다). */
    private static volatile String notice = "";
    private static volatile String latest = "";

    private ModVersionCheck() {}

    public static boolean isOutdated() {
        return Boolean.TRUE.equals(outdated);
    }

    /** 서버가 알려준 최신 버전 — 안내 문구에 넣는다. 모르면 빈 문자열. */
    public static String latestVersion() {
        return latest;
    }

    public static String notice() {
        return notice;
    }

    /** 게임 시작 때 한 번 부른다(네트워크라 별도 스레드). 실패해도 조용히 넘어간다. */
    public static void refreshAsync() {
        Thread t = new Thread(ModVersionCheck::refresh, "instant-p2p-version-check");
        t.setDaemon(true);
        t.start();
    }

    private static void refresh() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(P2PConfig.SIGNALING_HTTP_URL + "/api/v1/version"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;

            String current = VillasMsg.field(res.body(), "current");
            if (current == null || current.isBlank()) return; // 서버에 설정이 없다 = 기능 꺼짐

            latest = current;
            String n = VillasMsg.field(res.body(), "notice");
            notice = n != null ? n : "";

            boolean old = compare(P2PConfig.MOD_VERSION, current) < 0;
            outdated = old;
            // 최신이면 로그도 남기지 않는다 — 정상 상태를 매번 찍을 이유가 없다.
            if (old) LOG.info("[version] 배포본은 {}, 내 버전은 {} — 구버전 안내를 띄운다", current, P2PConfig.MOD_VERSION);
        } catch (Exception e) {
            LOG.debug("[version] 확인 실패(무시): {}", e.getMessage());
        }
    }

    /**
     * 점으로 나눈 숫자 버전 비교 — 서버의 {@code compareVersions} 와 같은 규칙이어야 한다.
     * 자리 수가 달라도 되고("1.3" == "1.3.0"), 숫자가 아닌 꼬리는 무시한다("1.3-rc1" == "1.3").
     */
    static int compare(String a, String b) {
        String[] as = a.split("\\."), bs = b.split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int d = Integer.compare(numAt(as, i), numAt(bs, i));
            if (d != 0) return d;
        }
        return 0;
    }

    private static int numAt(String[] parts, int i) {
        if (i >= parts.length) return 0;
        String s = parts[i].trim();
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
