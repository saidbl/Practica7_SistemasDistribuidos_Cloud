package lb;

import common.Message;

import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {

    private static final int LB_PORT = 4000;

    private static final List<String> SERVER_TARGETS = List.of(
            "server1:5001",
            "server2:5002",
            "server3:5003"
    );

    private static final AtomicInteger rr = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.println("[LB] Listening on " + LB_PORT);

        try (ServerSocket ss = new ServerSocket(LB_PORT)) {
            while (true) {
                Socket clientSock = ss.accept();

                String target = findServerTarget();

                try (ObjectOutputStream out =
                             new ObjectOutputStream(clientSock.getOutputStream())) {

                    System.out.println("[LB] Redirecting client to " + target);
                    out.writeObject(new Message("REDIRECT", target));
                    out.flush();

                } catch (Exception e) {
                    System.out.println("[LB] Error redirecting client: " + e.getMessage());
                } finally {
                    try {
                        clientSock.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static String findServerTarget() {
        int idx = Math.floorMod(rr.getAndIncrement(), SERVER_TARGETS.size());
        return SERVER_TARGETS.get(idx);
    }
}