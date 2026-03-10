package dbclient;

import dbclient.websocket.JettyServer;
import java.util.Map;

/**
 * Main entry point - uses unified Jetty server for HTTP + WebSocket.
 */
public class Main {
    
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        
        System.out.println("Starting DJ Link Server on port " + port + "...");
        
        // Start unified Jetty server (HTTP + WebSocket on same port) first,
        // so UI remains reachable even if DJ Link startup is slow/flaky.
        JettyServer.start(port);

        // Manual scan mode: do not auto-start DeviceManager.
        // WebUI / API toggle endpoint will start/stop scanning on demand.

        // Semantic event bridge (TriggerEngine -> WS/Sync Output Manager)
        registerEventListener();

        // Start STATE broadcast after delay
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception e) {}
            JettyServer.startStateBroadcast();
        }).start();
        
        System.out.println("Server running. Press Ctrl+C to stop.");
        
        // Keep server running
        JettyServer.join();
    }
    
    private static void startDeviceManager() {
        try {
            Class<?> dmClass = Class.forName("djlink.DeviceManager");
            // Get instance first, then call start()
            Object dm = dmClass.getMethod("getInstance").invoke(null);
            dmClass.getMethod("start").invoke(dm);
            System.out.println("DeviceManager started");
        } catch (Exception e) {
            System.out.println("DeviceManager start: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void registerEventListener() {
        try {
            Class<?> teClass = Class.forName("djlink.trigger.TriggerEngine");
            Class<?> listenerClass = Class.forName("djlink.trigger.TriggerEngine$EventListener");
            java.lang.reflect.Method setListener = teClass.getMethod("setEventListener", listenerClass);
            
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[] { listenerClass },
                (proxy, method, margs) -> {
                    if ("onEvent".equals(method.getName()) && margs != null && margs.length > 0) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> event = (Map<String, Object>) margs[0];
                        String type = (String) event.get("type");
                        Integer player = (Integer) event.get("player");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) event.get("data");
                        JettyServer.pushEvent(type, player, data);
                    }
                    return null;
                }
            );
            setListener.invoke(null, listener);
            System.out.println("Event listener registered");
        } catch (Exception e) {
            System.out.println("Event listener: " + e.getMessage());
        }
    }
}
