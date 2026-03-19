package dbclient.protocol;

import dbclient.sync.drivers.Ma2TelnetClient;
import java.util.HashMap;
import java.util.Map;

/**
 * Ma2TelnetClient 适配器（实现 Ma2Client 接口）
 * 
 * 包装现有 Ma2TelnetClient 实现
 */
public class Ma2TelnetClientAdapter implements Ma2Client {

    private final Ma2TelnetClient client;

    public Ma2TelnetClientAdapter(Ma2TelnetClient client) {
        this.client = client;
    }

    public Ma2TelnetClientAdapter() {
        this(new Ma2TelnetClient());
    }

    @Override
    public String getName() {
        return "Ma2TelnetClientAdapter";
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public boolean connect() {
        try {
            client.connect();
            return client.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean connect(String host, int port) {
        try {
            client.configure(host, port, null, null);
            client.connect();
            return client.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean login(String user, String password) {
        try {
            client.configure(null, 0, user, password);
            client.connect();
            return client.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void disconnect() {
        client.disconnect();
    }

    @Override
    public String sendCommand(String command) {
        try {
            return client.sendCommand(command);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String sendCommand(String command, long timeoutMs) {
        return sendCommand(command);
    }

    @Override
    public ConnectionState getConnectionState() {
        return client.isConnected() ? ConnectionState.LOGGED_IN : ConnectionState.DISCONNECTED;
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("connected", client.isConnected());
        return stats;
    }

    @Override
    public String sendMa2Command(String command) {
        try {
            return client.sendCommand(command);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean sendMa2CommandSimple(String command) {
        String response = sendMa2Command(command);
        return response != null && !response.isEmpty();
    }

    @Override
    public boolean executeMacro(String macro) {
        return sendMa2CommandSimple("Macro " + macro);
    }

    @Override
    public boolean triggerExecutor(String executor) {
        return sendMa2CommandSimple("Executor " + executor + " Toggle");
    }

    @Override
    public boolean setChannelParam(int channel, String param, Object value) {
        String cmd = "SetAttr Channel." + channel + "/" + param + " " + value;
        return sendMa2CommandSimple(cmd);
    }
}
