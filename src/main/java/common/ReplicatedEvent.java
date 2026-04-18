package common;

import java.io.Serializable;

public class ReplicatedEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    public final long seq;
    public final String eventType; // "SCALE_UP", "SCALE_DOWN", "NODE_DEAD"
    public final Object payload;
    public final long ts;

    public ReplicatedEvent(long seq, String eventType, Object payload) {
        this.seq = seq;
        this.eventType = eventType;
        this.payload = payload;
        this.ts = System.currentTimeMillis();
    }
}