package common;

import java.io.Serializable;

public enum ResourceType implements Serializable {
    VM,
    CONTAINER,
    DATABASE,
    API_GATEWAY,
    STORAGE_NODE
}
