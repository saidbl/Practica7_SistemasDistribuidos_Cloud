package common;

import java.io.Serializable;

public class Registration implements Serializable {
    private static final long serialVersionUID = 1L;
    private String nodeId;
    private ResourceType type;
    private String role;

    public Registration(String nodeId, ResourceType type, String role) {
        this.nodeId = nodeId;
        this.type = type;
        this.role = role;
    }

    public String getNodeId() { return nodeId; }
    public ResourceType getType() { return type; }
    public String getRole() { return role; }
}
