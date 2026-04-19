package client;

import common.Message;
import common.ResourceType;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.util.Random;
import java.util.UUID;

public class Client {

    public static void main(String[] args) throws Exception {
        String lbHost = (args.length >= 1) ? args[0] : "localhost";
        int lbPort = (args.length >= 2) ? Integer.parseInt(args[1]) : 4000;

        ResourceType[] types = ResourceType.values();
        ResourceType type = types[new Random().nextInt(types.length)];

        String nodeId = type + "-" + UUID.randomUUID().toString().substring(0, 4);

        System.out.println("CLIENT LAUNCHER PID: " + ProcessHandle.current().pid());
        System.out.println("nodeId=" + nodeId + " type=" + type);

        String cp = System.getProperty("java.class.path");

        RedirectInfo redirect;
        try {
            redirect = getRedirect(lbHost, lbPort);
            System.out.println("[CLIENT] Assigned server " + redirect.host + ":" + redirect.port + " for node " + nodeId);
        } catch (Exception e) {
            throw new IOException("Could not get server from Load Balancer", e);
        }

        Process metricProc = new ProcessBuilder("java", "-cp", cp,
                "client.MetricProcess",
                lbHost,
                String.valueOf(lbPort),
                redirect.host,
                String.valueOf(redirect.port),
                nodeId,
                type.name()
        ).inheritIO().start();

        Process heartbeatProc = new ProcessBuilder("java", "-cp", cp,
                "client.HearthBeatProcess",
                lbHost,
                String.valueOf(lbPort),
                redirect.host,
                String.valueOf(redirect.port),
                nodeId,
                type.name()
        ).inheritIO().start();

        metricProc.waitFor();
        heartbeatProc.waitFor();
    }

    private static RedirectInfo getRedirect(String host, int publicPort) throws Exception {
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

    private static class RedirectInfo {
        final String host;
        final int port;

        RedirectInfo(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}