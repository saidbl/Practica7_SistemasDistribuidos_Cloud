package client;

import client.HttpJsonClient.RedirectInfo;
import common.ResourceType;

public class HearthBeatProcess {

    public static void main(String[] args) throws Exception {
        String lbHost = args[0];
        int lbPort = Integer.parseInt(args[1]);
        String serverHost = args[2];
        int serverPort = Integer.parseInt(args[3]);
        String nodeId = args[4];
        ResourceType type = ResourceType.valueOf(args[5]);

        HttpJsonClient client = new HttpJsonClient();

        register(client, serverHost, serverPort, nodeId, type, "HEARTBEAT");

        while (true) {
            try {
                String heartbeatJson = """
                        {
                          "nodeId":"%s"
                        }
                        """.formatted(nodeId);

                client.postJson("http://" + serverHost + ":" + serverPort + "/api/nodes/heartbeat", heartbeatJson);

            } catch (Exception e) {
                System.out.println("[CLIENT] Heartbeat server unreachable, reconnecting...");
                RedirectInfo redirect = reconnect(client, lbHost, lbPort, nodeId, type, "HEARTBEAT");
                serverHost = redirect.host;
                serverPort = redirect.port;
            }

            Thread.sleep(2000);
        }
    }

    private static void register(HttpJsonClient client, String host, int serverPort,
                                 String nodeId, ResourceType type, String role) throws Exception {
        String registerJson = """
                {
                  "nodeId":"%s",
                  "type":"%s",
                  "role":"%s"
                }
                """.formatted(nodeId, type.name(), role);

        client.postJson("http://" + host + ":" + serverPort + "/api/nodes/register", registerJson);
    }

    private static RedirectInfo reconnect(HttpJsonClient client, String lbHost, int lbPort,
                                          String nodeId, ResourceType type, String role) throws Exception {
        while (true) {
            try {
                RedirectInfo redirect = client.getRedirect(lbHost, lbPort);
                System.out.println("[CLIENT] Reconnected to server " + redirect.host + ":" + redirect.port);
                register(client, redirect.host, redirect.port, nodeId, type, role);
                return redirect;
            } catch (Exception ex) {
                System.out.println("[CLIENT] retrying connection...");
                Thread.sleep(2000);
            }
        }
    }

}