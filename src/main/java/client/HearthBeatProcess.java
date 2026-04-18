package client;

import common.ResourceType;

public class HearthBeatProcess {

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
                  "role":"HEARTBEAT"
                }
                """.formatted(nodeId, type.name());

        client.postJson("http://" + host + ":" + serverPort + "/api/nodes/register", registerJson);

        while (true) {
            try {
                String heartbeatJson = """
                        {
                          "nodeId":"%s"
                        }
                        """.formatted(nodeId);

                client.postJson("http://" + host + ":" + serverPort + "/api/nodes/heartbeat", heartbeatJson);

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
                                  "role":"HEARTBEAT"
                                }
                                """.formatted(nodeId, type.name());

                        client.postJson("http://" + host + ":" + serverPort + "/api/nodes/register", registerJsonReconnect);

                    } catch (Exception ex) {
                        System.out.println("[CLIENT] retrying connection...");
                        Thread.sleep(2000);
                    }
                }
            }

            Thread.sleep(2000);
        }
    }
}