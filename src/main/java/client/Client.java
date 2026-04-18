package client;

import common.ResourceType;

import java.io.IOException;
import java.util.Random;
import java.util.UUID;

public class Client {

    public static void main(String[] args) throws IOException {
        String host = (args.length >= 1) ? args[0] : "localhost";
        int port = (args.length >= 2) ? Integer.parseInt(args[1]) : 4000;

        ResourceType[] types = ResourceType.values();
        ResourceType type = types[new Random().nextInt(types.length)];

        String nodeId = type + "-" + UUID.randomUUID().toString().substring(0, 4);

        System.out.println("CLIENT LAUNCHER PID: " + ProcessHandle.current().pid());
        System.out.println("nodeId=" + nodeId + " type=" + type);

        String cp = "target/classes";

        new ProcessBuilder("java", "-cp", cp,
                "client.MetricProcess",
                host, String.valueOf(port),
                nodeId, type.name()
        ).inheritIO().start();

        new ProcessBuilder("java", "-cp", cp,
                "client.HearthBeatProcess",
                host, String.valueOf(port),
                nodeId, type.name()
        ).inheritIO().start();
    }
}