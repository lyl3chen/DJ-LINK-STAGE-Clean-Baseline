package dbclient.protocol;

/**
 * TitanAdapter 适配器（实现 TitanClient 接口）
 * 
 * 包装现有 TitanAdapter 实现
 */
public class TitanClientAdapter implements TitanClient {

    private final dbclient.sync.drivers.TitanAdapter adapter;
    private boolean connected = false;

    public TitanClientAdapter(dbclient.sync.drivers.TitanAdapter adapter) {
        this.adapter = adapter;
    }

    public TitanClientAdapter() {
        this(new dbclient.sync.drivers.TitanAdapter());
    }

    public dbclient.sync.drivers.TitanAdapter getAdapter() {
        return adapter;
    }

    @Override
    public String getName() {
        return "TitanClientAdapter";
    }

    @Override
    public boolean isConnected() {
        return connected && adapter != null;
    }

    @Override
    public boolean connect() {
        connected = true;
        return true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public String sendCommand(String command) {
        return sendTitanCommand(command);
    }

    @Override
    public String sendCommand(String command, long timeoutMs) {
        return sendTitanCommand(command);
    }

    @Override
    public ConnectionState getConnectionState() {
        return isConnected() ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED;
    }

    @Override
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("connected", connected);
        if (adapter != null) {
            stats.put("baseUrl", adapter.getBaseUrl());
            stats.put("version", adapter.getDetectedVersion());
        }
        return stats;
    }

    @Override
    public String sendTitanCommand(String command) {
        if (adapter == null || !isConnected()) return null;
        try {
            return adapter.httpGet(adapter.getBaseUrl() + command);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean sendTitanCommandSimple(String command) {
        String response = sendTitanCommand(command);
        return response != null;
    }

    @Override
    public void setButton(String page, String button, Object value) {
        // TODO: 实现按钮设置
    }

    @Override
    public void setFader(int channel, int value) {
        // TODO: 实现推子设置
    }

    @Override
    public void sendOSC(String path, Object... args) {
        // TODO: 实现 OSC 发送（Titan via HTTP 暂不支持 OSC）
    }
}
