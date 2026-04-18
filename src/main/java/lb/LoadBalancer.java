package lb;

import common.Message;

import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {

    private static final int LB_PORT = 4000;

    private static final List<Integer> SERVER_PUBLIC_PORTS =
            List.of(5001, 5002, 5003);

    private static final AtomicInteger rr = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.println("[LB] Listening on " + LB_PORT);

        try (ServerSocket ss = new ServerSocket(LB_PORT)) {
            while (true) {
                Socket clientSock = ss.accept();

                Integer serverPort = findServerPort();

                if (serverPort == null) {
                    System.out.println("[LB] No servers available!");
                    clientSock.close();
                    continue;
                }

                try (ObjectOutputStream out =
                             new ObjectOutputStream(clientSock.getOutputStream())) {

                    out.writeObject(new Message("REDIRECT", serverPort));
                    out.flush();
                } finally {
                    clientSock.close();
                }
            }
        }
    }

    private static Integer findServerPort() {
        int size = SERVER_PUBLIC_PORTS.size();
        int idx = Math.floorMod(rr.getAndIncrement(), size);
        return SERVER_PUBLIC_PORTS.get(idx);
    }
}