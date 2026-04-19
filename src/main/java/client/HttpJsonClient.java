package client;

import common.Message;

import java.io.ObjectInputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class HttpJsonClient {

    private final HttpClient httpClient;

    public HttpJsonClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public RedirectInfo getRedirect(String host, int publicPort) throws Exception {
        try (Socket s = new Socket(host, publicPort)) {
            ObjectInputStream in = new ObjectInputStream(s.getInputStream());
            Message m = (Message) in.readObject();

            if (!"REDIRECT".equals(m.getType())) {
                throw new RuntimeException("Expected REDIRECT");
            }

            String target = (String) m.getPayload();
            String[] parts = target.split(":");
            if (parts.length != 2) {
                throw new RuntimeException("Invalid redirect target: " + target);
            }

            return new RedirectInfo(parts[0], Integer.parseInt(parts[1]));
        }
    }

    public void postJson(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );

        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + response.body());
        }
    }

    public String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );

        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + response.body());
        }

        return response.body();
    }

    public static class RedirectInfo {
        public final String host;
        public final int port;

        public RedirectInfo(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}