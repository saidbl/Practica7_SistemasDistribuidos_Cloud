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
import java.nio.charset.StandardCharsets;

public class WebServiceServer {

    public static void start(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);

        httpServer.createContext("/api/nodes/register", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }

            try {
                String body = readBody(exchange);
                Registration reg = JsonUtil.parseRegistration(body);
                Server.onRegister(reg);
                send(exchange, 200, JsonUtil.ok("Nodo registrado"));
            } catch (Exception e) {
                send(exchange, 400, JsonUtil.error("Invalid registration: " + e.getMessage()));
            }
        });

        httpServer.createContext("/api/nodes/heartbeat", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }

            try {
                String body = readBody(exchange);
                HeartbeatRequest req = JsonUtil.parseHeartbeat(body);
                Server.onHeartbeat(req.getNodeId());
                send(exchange, 200, JsonUtil.ok("Heartbeat recibido"));
            } catch (Exception e) {
                send(exchange, 400, JsonUtil.error("Invalid heartbeat: " + e.getMessage()));
            }
        });

        httpServer.createContext("/api/metrics/report", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }

            try {
                String body = readBody(exchange);
                MetricReport report = JsonUtil.parseMetric(body);
                Server.onMetric(report);
                send(exchange, 200, JsonUtil.ok("Métrica recibida"));
            } catch (Exception e) {
                send(exchange, 400, JsonUtil.error("Invalid metric: " + e.getMessage()));
            }
        });

        httpServer.createContext("/api/nodes", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }
            send(exchange, 200, Server.getNodesJson());
        });

        httpServer.createContext("/api/cluster/status", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, JsonUtil.error("Method Not Allowed"));
                return;
            }
            send(exchange, 200, Server.getClusterStatusJson());
        });

        httpServer.setExecutor(null);
        httpServer.start();

        System.out.println("[HTTP] Web service ready on port " + port);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}