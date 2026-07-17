package kfc.udp.client.webrtc;

import java.util.ArrayList;
import java.util.List;

/**
 * VILLASframework signaling 서버 메시지 빌드/파싱 유틸.
 * <p>
 * 서버(cmd/server, pkg/msg.go) 스키마:
 * <pre>
 *   {"control":{"peer_id":N,"peers":[{"name":..,"id":..,"remote":..,..}]}}
 *   {"description":{"spd":"...","type":"offer|answer"}}   ← 필드명이 "spd" (서버 오타 그대로)
 *   {"candidate":{"spd":"candidate:...","mid":"0"}}
 *   {"servers":[{"url":..,"user":..,"pass":..,"realm":..,"expires":..}]}
 * </pre>
 * 주의: 서버 수신 한도 4096바이트(maxMessageSize), 접속 직후 클라이언트가
 * signals 메시지({} 가능)를 1회 먼저 보내야 하며, 릴레이 메시지에는 발신자 정보가 없다.
 */
final class VillasMsg {

    /** 접속 직후 보내는 signals 초기 메시지 */
    static String hello() { return "{}"; }

    static String description(String type, String sdp) {
        return "{\"description\":{\"spd\":\"" + escape(sdp) + "\",\"type\":\"" + type + "\"}}";
    }

    static String candidate(String candidate, String mid) {
        return "{\"candidate\":{\"spd\":\"" + escape(candidate) + "\",\"mid\":\"" + escape(mid) + "\"}}";
    }

    static boolean has(String json, String key) {
        return json.contains("\"" + key + "\"");
    }

    /** control.peers 배열 → [name, remote(연결 안 됐으면 null)] 목록 */
    static List<String[]> peers(String json) {
        List<String[]> out = new ArrayList<>();
        int k = json.indexOf("\"peers\"");
        if (k < 0) return out;
        int lb = json.indexOf('[', k);
        if (lb < 0) return out;
        int depth = 0, objStart = -1;
        for (int i = lb + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') {
                if (depth++ == 0) objStart = i;
            } else if (ch == '}') {
                if (--depth == 0) {
                    String obj = json.substring(objStart, i + 1);
                    out.add(new String[]{ field(obj, "name"), field(obj, "remote") });
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    /** servers 배열 → [url, user, pass] 목록 */
    static List<String[]> servers(String json) {
        List<String[]> out = new ArrayList<>();
        int k = json.indexOf("\"servers\"");
        if (k < 0) return out;
        int lb = json.indexOf('[', k);
        if (lb < 0) return out;
        int depth = 0, objStart = -1;
        for (int i = lb + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') {
                if (depth++ == 0) objStart = i;
            } else if (ch == '}') {
                if (--depth == 0) {
                    String obj = json.substring(objStart, i + 1);
                    out.add(new String[]{ field(obj, "url"), field(obj, "user"), field(obj, "pass") });
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    /** {"key":{...}} 내부 오브젝트 추출 */
    static String object(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int brace = json.indexOf('{', idx + k.length());
        if (brace < 0) return null;
        int depth = 0, end = brace;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) { end++; break; } }
            end++;
        }
        return json.substring(brace, end);
    }

    /** 문자열/숫자 필드 추출 (+ 이스케이프 해제) */
    static String field(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + k.length());
        if (colon < 0) return null;
        int vs = colon + 1;
        while (vs < json.length() && json.charAt(vs) == ' ') vs++;
        if (vs < json.length() && json.charAt(vs) != '"') {
            int e = vs;
            while (e < json.length() && ",}]".indexOf(json.charAt(e)) < 0) e++;
            return json.substring(vs, e).trim();
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = start + 1;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
            end++;
        }
        return json.substring(start + 1, end)
                .replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r\n", "\\r\\n").replace("\n", "\\n").replace("\r", "\\r");
    }

    private VillasMsg() {}
}