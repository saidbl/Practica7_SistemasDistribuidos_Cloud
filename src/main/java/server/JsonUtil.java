package server;

import common.HeartbeatRequest;
import common.MetricReport;
import common.Registration;
import common.ResourceType;

public class JsonUtil {

    public static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyPos = json.indexOf(pattern);
        if (keyPos < 0) return null;

        int colon = json.indexOf(":", keyPos);
        int firstQuote = json.indexOf("\"", colon + 1);
        int secondQuote = json.indexOf("\"", firstQuote + 1);

        if (colon < 0 || firstQuote < 0 || secondQuote < 0) return null;
        return json.substring(firstQuote + 1, secondQuote);
    }

    public static Integer extractInt(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyPos = json.indexOf(pattern);
        if (keyPos < 0) return null;

        int colon = json.indexOf(":", keyPos);
        if (colon < 0) return null;

        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        int end = start;
        while (end < json.length() &&
                (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }

        return Integer.parseInt(json.substring(start, end));
    }

    public static Registration parseRegistration(String json) {
        String nodeId = extractString(json, "nodeId");
        String type = extractString(json, "type");
        String role = extractString(json, "role");
        return new Registration(nodeId, ResourceType.valueOf(type), role);
    }

    public static HeartbeatRequest parseHeartbeat(String json) {
        return new HeartbeatRequest(extractString(json, "nodeId"));
    }

    public static MetricReport parseMetric(String json) {
        String nodeId = extractString(json, "nodeId");
        String type = extractString(json, "type");
        Integer value = extractInt(json, "value");
        return new MetricReport(nodeId, ResourceType.valueOf(type), value);
    }

    public static String ok(String msg) {
        return "{\"status\":\"ok\",\"message\":\"" + msg + "\"}";
    }

    public static String error(String msg) {
        return "{\"status\":\"error\",\"message\":\"" + msg + "\"}";
    }
}