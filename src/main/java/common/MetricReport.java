package common;

import java.io.Serializable;

public class MetricReport implements Serializable {
    private static final long serialVersionUID = 1L;

    private String nodeId;
    private ResourceType type;
    private int value;

    public MetricReport(String nodeId, ResourceType type, int value) {
        this.nodeId = nodeId;
        this.type = type;
        this.value = value;
    }

    public String getNodeId() { return nodeId; }
    public ResourceType getType() { return type; }
    public int getValue() { return value; }
}
