package server;

import common.*;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Server {

    private static final int MAX_VM = 5;
    private static final int MAX_CONTAINER = 8;
    private static final int MAX_DATABASE = 3;
    private static final int MAX_API_GATEWAY = 4;
    private static final int MAX_STORAGE_NODE = 4;

    private static final long HEARTBEAT_TIMEOUT_MS = 8000;
    private static final long LEADER_TIMEOUT_MS = 20000;
    private static final long COOLDOWN_MS = 5000;

    private static final Map<String, ResourceType> nodeType = new ConcurrentHashMap<>();
    private static final Map<String, Integer> lastValue = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private static final Map<ResourceType, Long> lastScaleTime = new ConcurrentHashMap<>();
    private static final Map<ResourceType, CopyOnWriteArrayList<String>> cluster = new ConcurrentHashMap<>();
    private static final Map<ResourceType, Deque<Integer>> metricWindows = new ConcurrentHashMap<>();
    private static final Map<String, NodeStatus> nodeStatus = new ConcurrentHashMap<>();

    private static final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    private static final BlockingQueue<Message> messageQueue = new ArrayBlockingQueue<>(10000);
    private static final ExecutorService consumerPool = Executors.newFixedThreadPool(4);
    private static final ExecutorService clusterConnPool = Executors.newCachedThreadPool();

    private static final int WINDOW_SIZE = 5;

    private static int SERVER_ID;
    private static int PUBLIC_PORT;
    private static int CLUSTER_PORT;

    private static final List<ClusterPeer> PEERS = new ArrayList<>();
    private static volatile int leaderId = -1;
    private static volatile boolean isLeader = false;
    private static volatile long lastLeaderHeartbeat = 0L;
    private static volatile boolean electionInProgress = false;

    private static volatile long nextSeq = 1;
    private static volatile long lastAppliedSeq = 0;
    private static final TreeMap<Long, ReplicatedEvent> pendingEvents = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java server.Server <serverId> <publicPort> <clusterPort>");
            return;
        }

        System.out.println("ARGS -> serverId=" + args[0] + ", publicPort=" + args[1] + ", clusterPort=" + args[2]);
        
        SERVER_ID = Integer.parseInt(args[0]);
        PUBLIC_PORT = Integer.parseInt(args[1]);
        CLUSTER_PORT = Integer.parseInt(args[2]);

        System.out.println("\n=== SERVER " + SERVER_ID + " PID: " + ProcessHandle.current().pid() + " ===");
        System.out.println("Public HTTP: " + PUBLIC_PORT + " | Cluster: " + CLUSTER_PORT);

        for (ResourceType t : ResourceType.values()) {
            cluster.put(t, new CopyOnWriteArrayList<>());
            metricWindows.put(t, new ArrayDeque<>());
        }

        List<ClusterPeer> all = List.of(
                new ClusterPeer(1, "localhost", 7001),
                new ClusterPeer(2, "localhost", 7002),
                new ClusterPeer(3, "localhost", 7003)
        );
        for (ClusterPeer p : all) {
            if (p.id != SERVER_ID) PEERS.add(p);
        }

        System.out.println("Peers: " + PEERS);

        WebServiceServer.start(PUBLIC_PORT);

        new Thread(Server::runClusterListener, "ClusterListener").start();
        new Thread(Server::monitorHeartbeats, "ClientHeartbeatMonitor").start();
        new Thread(Server::monitorLeader, "LeaderMonitor").start();

        for (int i = 0; i < 4; i++) {
            consumerPool.submit(Server::consumeMessages);
        }
    }

    private static void consumeMessages() {
        while (true) {
            try {
                Message msg = messageQueue.take();

                switch (msg.getType()) {
                    case "REGISTER" -> onRegister((Registration) msg.getPayload());
                    case "HEARTBEAT" -> onHeartbeat((String) msg.getPayload());
                    case "METRIC" -> onMetric((MetricReport) msg.getPayload());
                    case "REPL_EVENT" -> onReplicatedEvent((ReplicatedEvent) msg.getPayload());
                    default -> {
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void onRegister(Registration reg) {
        stateLock.writeLock().lock();
        try {
            nodeStatus.put(reg.getNodeId(), NodeStatus.ALIVE);
            nodeType.put(reg.getNodeId(), reg.getType());

            cluster.get(reg.getType()).addIfAbsent(reg.getNodeId());
            lastHeartbeat.put(reg.getNodeId(), System.currentTimeMillis());

            System.out.println("[REGISTER] node=" + reg.getNodeId()
                    + " type=" + reg.getType()
                    + " role=" + reg.getRole()
                    + " | total(" + reg.getType() + ")=" + cluster.get(reg.getType()).size());

        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public static void onHeartbeat(String nodeId) {
        lastHeartbeat.put(nodeId, System.currentTimeMillis());
    }

    public static void onMetric(MetricReport r) {
        stateLock.writeLock().lock();
        try {
            lastValue.put(r.getNodeId(), r.getValue());

            Deque<Integer> window = metricWindows.get(r.getType());
            window.addLast(r.getValue());
            if (window.size() > WINDOW_SIZE) {
                window.removeFirst();
            }

            double avg = window.stream().mapToInt(i -> i).average().orElse(0);
            String state = calculateState(r.getType(), (int) avg);

            System.out.println("[METRIC] " + r.getType() + " " + r.getNodeId()
                    + " value=" + r.getValue()
                    + " state=" + state);

            applyScalingPolicy(r.getType(), state);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private static String calculateState(ResourceType type, int v) {
        return switch (type) {
            case VM -> (v > 80) ? "CRITICAL" : (v < 30) ? "LOW" : "NORMAL";
            case CONTAINER -> (v > 70) ? "CRITICAL" : (v < 20) ? "LOW" : "NORMAL";
            case DATABASE -> (v >= 100) ? "CRITICAL" : "NORMAL";
            case API_GATEWAY -> (v > 200) ? "CRITICAL" : (v < 80) ? "LOW" : "NORMAL";
            case STORAGE_NODE -> (v >= 85) ? "CRITICAL" : "NORMAL";
        };
    }

    private static void applyScalingPolicy(ResourceType type, String state) {
        if (!isLeader) return;

        long now = System.currentTimeMillis();
        long last = lastScaleTime.getOrDefault(type, 0L);
        if (now - last < COOLDOWN_MS) return;

        if ("CRITICAL".equals(state)) {
            lastScaleTime.put(type, now);
            int count = switch (type) {
                case VM -> 1;
                case CONTAINER -> 2;
                case DATABASE -> 1;
                case API_GATEWAY -> 1;
                case STORAGE_NODE -> 1;
            };

            ReplicatedEvent ev = new ReplicatedEvent(nextSeq++, "SCALE_UP", new ScaleCommand(type, count));
            applyEventLocally(ev);
            replicateEvent(ev);
        }

        if ("LOW".equals(state)) {
            lastScaleTime.put(type, now);
            int count = switch (type) {
                case VM, CONTAINER, API_GATEWAY -> 1;
                default -> 0;
            };

            if (count > 0) {
                ReplicatedEvent ev = new ReplicatedEvent(nextSeq++, "SCALE_DOWN", new ScaleCommand(type, count));
                applyEventLocally(ev);
                replicateEvent(ev);
            }
        }
    }

    private static void scaleUpLocal(ResourceType type, int count) {
        int maxLimit = switch (type) {
            case VM -> MAX_VM;
            case CONTAINER -> MAX_CONTAINER;
            case DATABASE -> MAX_DATABASE;
            case API_GATEWAY -> MAX_API_GATEWAY;
            case STORAGE_NODE -> MAX_STORAGE_NODE;
        };

        CopyOnWriteArrayList<String> list = cluster.get(type);

        if (list.size() >= maxLimit) {
            System.out.println("[SCALE-UP BLOCKED] Max limit reached for " + type);
            return;
        }

        for (int i = 0; i < count; i++) {
            if (list.size() >= maxLimit) break;

            String replicaId = type + "-AUTO-" + UUID.randomUUID().toString().substring(0, 4);
            list.add(replicaId);

            System.out.println("[SCALE-UP] Created " + replicaId + " | total(" + type + ")=" + list.size());
        }
    }

    private static void scaleDownLocal(ResourceType type, int count) {
        CopyOnWriteArrayList<String> list = cluster.get(type);

        for (int i = 0; i < count; i++) {
            if (list.size() <= 1) return;

            int idx = findAutoReplicaIndex(list);
            String removed = (idx >= 0) ? list.remove(idx) : list.remove(list.size() - 1);

            System.out.println("[SCALE-DOWN] Removed " + removed + " | total(" + type + ")=" + list.size());
        }
    }

    private static int findAutoReplicaIndex(List<String> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).contains("-AUTO-")) return i;
        }
        return -1;
    }

    private static void removeNodeLocal(String nodeId) {
        ResourceType t = nodeType.get(nodeId);

        lastHeartbeat.remove(nodeId);
        lastValue.remove(nodeId);
        nodeType.remove(nodeId);
        nodeStatus.remove(nodeId);

        if (t != null) {
            cluster.get(t).remove(nodeId);
        }

        System.out.println("[NODE_REMOVED] " + nodeId + " type=" + t);
    }

    private static void monitorHeartbeats() {
        while (true) {
            stateLock.writeLock().lock();
            try {
                long now = System.currentTimeMillis();

                for (String nodeId : new ArrayList<>(lastHeartbeat.keySet())) {
                    long last = lastHeartbeat.getOrDefault(nodeId, 0L);
                    long diff = now - last;

                    if (diff > HEARTBEAT_TIMEOUT_MS * 2) nodeStatus.put(nodeId, NodeStatus.DEAD);
                    else if (diff > HEARTBEAT_TIMEOUT_MS) nodeStatus.put(nodeId, NodeStatus.SUSPECT);
                    else nodeStatus.put(nodeId, NodeStatus.ALIVE);

                    if (nodeStatus.get(nodeId) == NodeStatus.DEAD && isLeader) {
                        ReplicatedEvent ev = new ReplicatedEvent(nextSeq++, "NODE_DEAD", nodeId);
                        applyEventLocally(ev);
                        replicateEvent(ev);
                    }
                }
            } finally {
                stateLock.writeLock().unlock();
            }

            try {
                Thread.sleep(2000);
            } catch (Exception ignored) {
            }
        }
    }

    private static void replicateEvent(ReplicatedEvent ev) {
        for (ClusterPeer p : PEERS) {
            sendToPeer(p, new Message("REPL_EVENT", ev));
        }
    }

    private static void onReplicatedEvent(ReplicatedEvent ev) {
        if (isLeader) return;

        stateLock.writeLock().lock();
        try {
            if (ev.seq == lastAppliedSeq + 1) {
                applyEventLocally(ev);
                lastAppliedSeq = ev.seq;

                while (pendingEvents.containsKey(lastAppliedSeq + 1)) {
                    ReplicatedEvent nxt = pendingEvents.remove(lastAppliedSeq + 1);
                    applyEventLocally(nxt);
                    lastAppliedSeq = nxt.seq;
                }
            } else if (ev.seq > lastAppliedSeq + 1) {
                pendingEvents.put(ev.seq, ev);
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private static void applyEventLocally(ReplicatedEvent ev) {
        switch (ev.eventType) {
            case "SCALE_UP" -> {
                ScaleCommand cmd = (ScaleCommand) ev.payload;
                scaleUpLocal(cmd.type, cmd.count);
            }
            case "SCALE_DOWN" -> {
                ScaleCommand cmd = (ScaleCommand) ev.payload;
                scaleDownLocal(cmd.type, cmd.count);
            }
            case "NODE_DEAD" -> {
                String nodeId = (String) ev.payload;
                removeNodeLocal(nodeId);
            }
        }
    }

    private static void runClusterListener() {
        try (ServerSocket ss = new ServerSocket(CLUSTER_PORT)) {
            System.out.println("[CLUSTER] Listening on " + CLUSTER_PORT);

            while (true) {
                Socket s = ss.accept();
                clusterConnPool.submit(() -> handleClusterConn(s));
            }
        } catch (Exception e) {
            System.out.println("[CLUSTER] ERROR: " + e.getMessage());
        }
    }

    private static void handleClusterConn(Socket s) {
        try (
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(s.getInputStream())
        ) {
            out.flush();

            while (true) {
                Message msg = (Message) in.readObject();

                switch (msg.getType()) {
                    case "LEADER_HEARTBEAT" -> {
                        int hbLeaderId = (int) msg.getPayload();
                        leaderId = hbLeaderId;
                        isLeader = (leaderId == SERVER_ID);
                        lastLeaderHeartbeat = System.currentTimeMillis();
                        electionInProgress = false;
                    }
                    case "ELECTION" -> {
                        int fromId = (int) msg.getPayload();
                        out.writeObject(new Message("OK", SERVER_ID));
                        out.flush();

                        if (fromId < SERVER_ID && !electionInProgress) {
                            new Thread(() -> startElection("RECV_ELECTION")).start();
                        }
                    }
                    case "COORDINATOR" -> {
                        int newLeader = (int) msg.getPayload();
                        setLeader(newLeader);
                        electionInProgress = false;
                    }
                    case "REPL_EVENT" -> messageQueue.put(msg);
                    default -> {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void monitorLeader() {
        while (true) {
            try {
                if (isLeader) {
                    broadcastLeaderHeartbeat();
                } else {
                    long diff = System.currentTimeMillis() - lastLeaderHeartbeat;
                    if (!electionInProgress && (leaderId == -1 || diff > LEADER_TIMEOUT_MS)) {
                        startElection("LEADER_TIMEOUT");
                    }
                }
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }
        }
    }

    private static synchronized void startElection(String reason) {
        if (isLeader) return;
        if (electionInProgress) return;
        electionInProgress = true;

        System.out.println("[ELECTION] start reason=" + reason + " me=" + SERVER_ID);

        boolean higherExists = false;
        boolean gotOk = false;

        for (ClusterPeer p : PEERS) {
            if (p.id > SERVER_ID) {
                higherExists = true;
                Boolean ok = sendElectionAndWaitOk(p);
                if (Boolean.TRUE.equals(ok)) gotOk = true;
            }
        }

        if (!higherExists || !gotOk) {
            becomeLeader();
        } else {
            System.out.println("[ELECTION] waiting coordinator...");
        }
    }

    private static Boolean sendElectionAndWaitOk(ClusterPeer p) {
        try (Socket s = new Socket(p.host, p.clusterPort);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {

            out.writeObject(new Message("ELECTION", SERVER_ID));
            out.flush();

            s.setSoTimeout(1200);
            Message resp = (Message) in.readObject();
            return "OK".equals(resp.getType());

        } catch (Exception e) {
            return false;
        }
    }

    private static void becomeLeader() {
        setLeader(SERVER_ID);
        electionInProgress = false;

        System.out.println("[COORDINATOR] I am leader: " + SERVER_ID);

        for (ClusterPeer p : PEERS) {
            sendToPeer(p, new Message("COORDINATOR", SERVER_ID));
        }
    }

    private static void setLeader(int id) {
        leaderId = id;
        isLeader = (leaderId == SERVER_ID);
        lastLeaderHeartbeat = System.currentTimeMillis();
        electionInProgress = false;

        System.out.println("[LEADER] leaderId=" + leaderId + " me=" + SERVER_ID + " isLeader=" + isLeader);
    }

    private static void broadcastLeaderHeartbeat() {
        if (!isLeader) return;

        for (ClusterPeer p : PEERS) {
            sendToPeer(p, new Message("LEADER_HEARTBEAT", SERVER_ID));
        }
    }

    private static void sendToPeer(ClusterPeer p, Message msg) {
        try (Socket s = new Socket(p.host, p.clusterPort);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {

            out.writeObject(msg);
            out.flush();

        } catch (Exception ignored) {
        }
    }

    public static String getNodesJson() {
        stateLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;

            for (String nodeId : nodeType.keySet()) {
                if (!first) sb.append(",");
                first = false;

                sb.append("{")
                        .append("\"nodeId\":\"").append(nodeId).append("\",")
                        .append("\"type\":\"").append(nodeType.get(nodeId)).append("\",")
                        .append("\"status\":\"").append(nodeStatus.getOrDefault(nodeId, NodeStatus.SUSPECT)).append("\",")
                        .append("\"lastValue\":").append(lastValue.getOrDefault(nodeId, -1))
                        .append("}");
            }

            sb.append("]");
            return sb.toString();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public static String getClusterStatusJson() {
        stateLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"serverId\":").append(SERVER_ID).append(",");
            sb.append("\"leaderId\":").append(leaderId).append(",");
            sb.append("\"isLeader\":").append(isLeader).append(",");
            sb.append("\"resources\":{");

            boolean first = true;
            for (ResourceType t : cluster.keySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(t.name()).append("\":").append(cluster.get(t).size());
            }

            sb.append("}}");
            return sb.toString();
        } finally {
            stateLock.readLock().unlock();
        }
    }
}
