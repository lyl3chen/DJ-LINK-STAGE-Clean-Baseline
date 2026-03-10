package dbclient.websocket;

import com.google.gson.Gson;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket event pusher for DJ Link real-time events.
 * Runs on same port as HTTP server.
 * Endpoint: /ws
 * 
 * Features:
 * - Multiple client support
 * - Event broadcasting
 * - STATE push every 100ms
 * - Connection logging
 */
public class EventPusher {
    
    private static final Gson gson = new Gson();
    private static Server wsServer;
    private static final Set<Session> sessions = new CopyOnWriteArraySet<>();
    private static ScheduledExecutorService stateScheduler;
    private static Object playersStateCache;
    private static int wsPort = 8080;
    
    /**
     * Initialize WebSocket server on specified port.
     */
    public static void init() {
        // Will be started with startServer()
    }
    
    /**
     * Start WebSocket server.
     */
    public static void startServer(int port) {
        if (wsServer != null) return;
        
        wsPort = port;
        try {
            wsServer = new Server(port);
            
            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath("/");
            wsServer.setHandler(context);
            
            context.addServlet(new ServletHolder(new WsServlet()), "/ws");
            
            wsServer.start();
            System.out.println("[WS] WebSocket server started on port " + port);
        } catch (Exception e) {
            System.out.println("[WS] Failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Start STATE broadcast scheduler (every 100ms).
     */
    public static void startStateBroadcast() {
        if (stateScheduler != null) return;
        
        stateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-state-push");
            t.setDaemon(true);
            return t;
        });
        
        // Push STATE every 100ms
        stateScheduler.scheduleAtFixedRate(() -> {
            if (playersStateCache != null && !sessions.isEmpty()) {
                pushStateInternal(playersStateCache);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        
        System.out.println("[WS] STATE broadcast started (100ms interval)");
    }
    
    /**
     * Stop STATE broadcast.
     */
    public static void stopStateBroadcast() {
        if (stateScheduler != null) {
            stateScheduler.shutdown();
            stateScheduler = null;
        }
    }
    
    /**
     * Update the cached players state for STATE broadcasts.
     */
    public static void updateState(Object playersState) {
        playersStateCache = playersState;
    }
    
    /**
     * Push a DJ Link event to all connected clients.
     */
    public static void pushEvent(String eventType, Integer player, Map<String, Object> data) {
        Map<String, Object> event = new ConcurrentHashMap<>();
        event.put("type", "EVENT");
        event.put("event", eventType);
        event.put("player", player);
        event.put("time", System.currentTimeMillis() / 1000);
        event.put("data", data != null ? data : new ConcurrentHashMap<>());
        
        String json = gson.toJson(event);
        broadcast(json);
        
        System.out.println("[WS] >>> EVENT " + eventType + " D" + player);
    }
    
    /**
     * Push STATE to all clients.
     */
    public static void pushState() {
        if (playersStateCache != null) {
            pushStateInternal(playersStateCache);
        }
    }
    
    private static void pushStateInternal(Object state) {
        try {
            Map<String, Object> stateMsg = new ConcurrentHashMap<>();
            stateMsg.put("type", "STATE");
            stateMsg.put("time", System.currentTimeMillis() / 1000);
            stateMsg.put("players", state);
            
            String json = gson.toJson(stateMsg);
            broadcast(json);
        } catch (Exception e) {
            // Silent fail for state push
        }
    }
    
    /**
     * Broadcast to all sessions.
     */
    private static void broadcast(String message) {
        for (Session session : sessions) {
            try {
                if (session.isOpen()) {
                    session.getRemote().sendString(message);
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Add session with logging.
     */
    public static void addSession(Session session) {
        sessions.add(session);
        System.out.println("[WS] >>> CLIENT_CONNECTED " + session.getRemoteAddress() + " (total: " + sessions.size() + ")");
        
        // Send initial STATE
        if (playersStateCache != null) {
            pushStateInternal(playersStateCache);
        }
    }
    
    /**
     * Remove session with logging.
     */
    public static void removeSession(Session session) {
        sessions.remove(session);
        System.out.println("[WS] >>> CLIENT_DISCONNECTED (total: " + sessions.size() + ")");
    }
    
    public static int getClientCount() {
        return sessions.size();
    }
    
    /**
     * WebSocket servlet.
     */
    public static class WsServlet extends WebSocketServlet {
        private static final long serialVersionUID = 1L;
        
        @Override
        public void configure(WebSocketServletFactory factory) {
            factory.getPolicy().setIdleTimeout(300000);
            factory.setCreator((req, resp) -> new WsEndpoint());
        }
    }
    
    /**
     * WebSocket endpoint.
     */
    public static class WsEndpoint {
        
        public void onOpen(Session session) {
            EventPusher.addSession(session);
        }
        
        public void onClose(int statusCode, String reason) {
            // Session already removed via addSession/removeSession pair
        }
        
        public void onMessage(String message) {
            System.out.println("[WS] <<< " + message);
        }
    }
}
