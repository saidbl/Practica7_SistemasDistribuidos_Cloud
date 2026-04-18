package common;

import java.io.Serializable;

public class ScaleCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    public final ResourceType type;
    public final int count;

    public ScaleCommand(ResourceType type, int count) {
        this.type = type;
        this.count = count;
    }
}