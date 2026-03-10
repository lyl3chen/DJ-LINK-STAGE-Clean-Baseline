package dbclient.sync;

import java.util.Map;

public interface OutputDriver {
    String name();
    void start(Map<String, Object> config);
    void stop();
    void update(Map<String, Object> state);
    Map<String, Object> status();
}
