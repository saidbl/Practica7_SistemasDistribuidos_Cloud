package launcher;

public class SystemLauncher {

    public static void main(String[] args) throws Exception {
        String cp = "target/classes";

        System.out.println("=== STARTING DISTRIBUTED SYSTEM ===");

        new ProcessBuilder("java", "-cp", cp, "lb.LoadBalancer")
                .inheritIO()
                .start();

        Thread.sleep(1500);

        new ProcessBuilder("java", "-cp", cp,
                "server.Server", "1", "5001", "7001")
                .inheritIO()
                .start();

        Thread.sleep(1200);

        new ProcessBuilder("java", "-cp", cp,
                "server.Server", "2", "5002", "7002")
                .inheritIO()
                .start();

        Thread.sleep(1200);

        new ProcessBuilder("java", "-cp", cp,
                "server.Server", "3", "5003", "7003")
                .inheritIO()
                .start();

        Thread.sleep(3000);

        for (int i = 0; i < 6; i++) {
            new ProcessBuilder("java", "-cp", cp,
                    "client.Client", "localhost", "4000")
                    .inheritIO()
                    .start();

            Thread.sleep(700);
        }

        System.out.println("=== SYSTEM RUNNING ===");
        System.out.println("LB socket redirect on 4000");
        System.out.println("Servers HTTP: 5001, 5002, 5003");
        System.out.println("Cluster ports: 7001, 7002, 7003");
    }
}