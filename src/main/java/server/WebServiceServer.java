package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import common.HeartbeatRequest;
import common.MetricReport;
import common.Registration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class WebServiceServer {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public static void start(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);

        httpServer.createContext("/api/nodes/register", exchange -> {
            if (handleCorsPreflight(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }

            try {
                if (!Server.isLeader()) {
                    forwardToLeader(exchange);
                    return;
                }

                String body = readBody(exchange);
                Registration reg = JsonUtil.parseRegistration(body);
                Server.onRegister(reg);
                sendJson(exchange, 200, JsonUtil.ok("Nodo registrado"));
            } catch (Exception e) {
                sendJson(exchange, 400, JsonUtil.error("Invalid registration: " + e.getMessage()));
            }
        });

        httpServer.createContext("/api/nodes/heartbeat", exchange -> {
            if (handleCorsPreflight(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }

            try {
                if (!Server.isLeader()) {
                    forwardToLeader(exchange);
                    return;
                }

                String body = readBody(exchange);
                HeartbeatRequest req = JsonUtil.parseHeartbeat(body);
                Server.onHeartbeat(req.getNodeId());
                sendJson(exchange, 200, JsonUtil.ok("Heartbeat recibido"));
            } catch (Exception e) {
                sendJson(exchange, 400, JsonUtil.error("Invalid heartbeat: " + e.getMessage()));
            }
        });

        httpServer.createContext("/api/metrics/report", exchange -> {
            if (handleCorsPreflight(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }

            try {
                if (!Server.isLeader()) {
                    forwardToLeader(exchange);
                    return;
                }

                String body = readBody(exchange);
                MetricReport report = JsonUtil.parseMetric(body);
                Server.onMetric(report);
                sendJson(exchange, 200, JsonUtil.ok("Métrica recibida"));
            } catch (Exception e) {
                sendJson(exchange, 400, JsonUtil.error("Invalid metric: " + e.getMessage()));
            }
        });

        httpServer.createContext("/api/nodes", exchange -> {
            if (handleCorsPreflight(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }
            sendJson(exchange, 200, Server.getNodesJson());
        });

        httpServer.createContext("/api/cluster/status", exchange -> {
            if (handleCorsPreflight(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }
            sendJson(exchange, 200, Server.getClusterStatusJson());
        });

        httpServer.createContext("/api/dashboard/summary", exchange -> {
            if (handleCorsPreflight(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }

            // para que todos muestren el mismo dashboard real
            try {
                if (!Server.isLeader()) {
                    forwardToLeader(exchange);
                    return;
                }
            } catch (Exception e) {
                sendJson(exchange, 500, JsonUtil.error("Dashboard forward error: " + e.getMessage()));
                return;
            }

            sendJson(exchange, 200, Server.getDashboardSummaryJson());
        });

        httpServer.createContext("/", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            serveStaticFile(exchange);
        });

        httpServer.setExecutor(null);
        httpServer.start();

        System.out.println("[HTTP] Web service + PWA ready on port " + port);
    }

    private static void forwardToLeader(HttpExchange exchange) throws IOException {
        int leaderPort = Server.getLeaderPublicPort();

        if (leaderPort < 0) {
            sendJson(exchange, 503, JsonUtil.error("Leader not available"));
            return;
        }

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body = readBody(exchange);

        String url = "http://" + Server.getLeaderHost() + ":" + leaderPort + path;
        if (query != null && !query.isBlank()) {
            url += "?" + query;
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5));

            if ("POST".equalsIgnoreCase(method)) {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);

            addCorsHeaders(exchange);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(response.statusCode(), bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }

        } catch (Exception e) {
            sendJson(exchange, 502, JsonUtil.error("Forward to leader failed: " + e.getMessage()));
        }
    }

    private static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        addCorsHeaders(exchange);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void serveStaticFile(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();

        if (requestPath.equals("/")) {
            requestPath = "/pwa/index.html";
        } else if (requestPath.equals("/manifest.json")) {
            requestPath = "/pwa/manifest.json";
        } else if (requestPath.equals("/service-worker.js")) {
            requestPath = "/pwa/service-worker.js";
        } else if (requestPath.equals("/app.js")) {
            requestPath = "/pwa/app.js";
        } else if (requestPath.equals("/styles.css")) {
            requestPath = "/pwa/styles.css";
        } else if (requestPath.equals("/offline.html")) {
            requestPath = "/pwa/offline.html";
        } else if (requestPath.startsWith("/icons/")) {
            requestPath = "/pwa" + requestPath;
        }

        Path filePath = Path.of("src" + requestPath);

        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, notFound.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound);
            }
            return;
        }

        String contentType = guessContentType(filePath.toString());
        byte[] bytes = Files.readAllBytes(filePath);

        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}