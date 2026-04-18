package common;

import java.io.Serializable;

public class ClusterPeer implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final String host;
    public final int clusterPort;

    public ClusterPeer(int id, String host, int clusterPort) {
        this.id = id;
        this.host = host;
        this.clusterPort = clusterPort;
    }

    @Override
    public String toString() {
        return "Peer{id=" + id + ", host=" + host + ", port=" + clusterPort + "}";
    }
}