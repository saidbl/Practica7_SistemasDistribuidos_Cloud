package client;

import common.ResourceType;

import java.util.Random;

public class MetricProcess {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int publicPort = Integer.parseInt(args[1]);
        String nodeId = args[2];
        ResourceType type = ResourceType.valueOf(args[3]);

        HttpJsonClient client = new HttpJsonClient();
        Integer serverPort = null;

        while (serverPort == null) {
            try {
                serverPort = client.getRedirectPort(host, publicPort);
                System.out.println("[CLIENT] Connected to server " + serverPort);
            } catch (Exception e) {
                System.out.println("[CLIENT] Waiting for available server...");
                Thread.sleep(2000);
            }
        }

        String registerJson = """
                {
                  "nodeId":"%s",
                  "type":"%s",
                  "role":"METRIC"
                }
                """.formatted(nodeId, type.name());

        client.postJson("http://" + host + ":" + serverPort + "/api/nodes/register", registerJson);

        Random r = new Random();

        while (true) {
            try {
                int value = generateMetric(type, r);

                String metricJson = """
                        {
                          "nodeId":"%s",
                          "type":"%s",
                          "value":%d
                        }
                        """.formatted(nodeId, type.name(), value);

                client.postJson("http://" + host + ":" + serverPort + "/api/metrics/report", metricJson);

            } catch (Exception e) {
                System.out.println("[CLIENT] Server unreachable, reconnecting...");
                serverPort = null;

                while (serverPort == null) {
                    try {
                        serverPort = client.getRedirectPort(host, publicPort);
                        System.out.println("[CLIENT] Reconnected to server " + serverPort);

                        String registerJsonReconnect = """
                                {
                                  "nodeId":"%s",
                                  "type":"%s",
                                  "role":"METRIC"
                                }
                                """.formatted(nodeId, type.name());

                        client.postJson("http://" + host + ":" + serverPort + "/api/nodes/register", registerJsonReconnect);

                    } catch (Exception ex) {
                        System.out.println("[CLIENT] retrying connection...");
                        Thread.sleep(2000);
                    }
                }
            }

            Thread.sleep(3000);
        }
    }

    private static int generateMetric(ResourceType type, Random r) {
        return switch (type) {
            case VM -> r.nextInt(100);
            case CONTAINER -> r.nextInt(120);
            case DATABASE -> r.nextInt(150);
            case API_GATEWAY -> r.nextInt(300);
            case STORAGE_NODE -> r.nextInt(100);
        };
    }
}