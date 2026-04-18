package common;

import java.io.Serializable;

public class HeartbeatRequest implements Serializable{
    private static final long serialVersionUID = 1L;

    private String nodeId;

    public HeartbeatRequest() {
    }

    public HeartbeatRequest(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }
    
    
}
