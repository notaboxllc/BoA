package boxOfActin;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * WebSocket server for live frame streaming (-3jsLive flag).
 *
 * Protocol (C1-C3):
 *   Server → client: {"topic":"frame",        "payload":{...frame JSON...}}
 *                    {"topic":"inspectResult", "payload":{...}}
 *                    {"topic":"simState",      "payload":{"state":"running"|"paused"|"terminating","step":<N>}}
 *   Client → server: {"action":"subscribe","topics":["frame","inspectResult","simState"]}
 *                    {"action":"inspect","id":<N>}
 *                    {"action":"pause"}
 *                    {"action":"resume"}
 *                    {"action":"kill"}
 *
 * Each client has a bounded outgoing queue (size 4). If a slow client falls
 * behind, the oldest queued frame is dropped to make room for the newest.
 * The simulation's dispatch*() calls are always non-blocking.
 */
public class LiveFrameServer extends WebSocketServer {

    private static final int QUEUE_CAPACITY = 4;

    private static LiveFrameServer instance = null;
    private static volatile boolean running = false;

    private final ConcurrentHashMap<WebSocket, ClientState> clients = new ConcurrentHashMap<>();

    private static class ClientState {
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        volatile Thread senderThread;
    }

    private LiveFrameServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        setConnectionLostTimeout(60);
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    public static void startServer(int port) {
        instance = new LiveFrameServer(port);
        instance.start();
        running = true;
        System.out.println("LiveFrameServer: WebSocket server started on port " + port);
    }

    public static void stopServer() {
        if (instance == null) return;
        running = false;
        try {
            instance.stop(1500);
            System.out.println("LiveFrameServer: stopped");
        } catch (Exception e) {
            System.err.println("LiveFrameServer: error during stop: " + e.getMessage());
        }
    }

    public static boolean isRunning() {
        return running;
    }

    // ── WebSocketServer callbacks ──────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        ClientState state = new ClientState();
        clients.put(conn, state);

        Thread sender = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && conn.isOpen()) {
                    String msg = state.queue.take();
                    conn.send(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // connection closed or send failed — exit sender
            }
        }, "ws-sender-" + conn.getRemoteSocketAddress());
        sender.setDaemon(true);
        state.senderThread = sender;
        sender.start();

        // C3: send current simState immediately so late-joining viewer knows run state
        state.queue.offer(buildSimStateMsg());

        System.out.println("LiveFrameServer: client connected " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientState state = clients.remove(conn);
        if (state != null && state.senderThread != null) {
            state.senderThread.interrupt();
        }
        System.out.println("LiveFrameServer: client disconnected (code=" + code + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.contains("\"subscribe\"")) {
            return;  // subscribed to all topics on connect; subscribe is informational only
        }
        if (message.contains("\"inspect\"")) {
            // Parse {"action":"inspect","id":<N>} — extract the integer id field
            int fieldIdx = message.indexOf("\"id\"");
            if (fieldIdx >= 0) {
                int colonIdx = message.indexOf(':', fieldIdx) + 1;
                int end = colonIdx;
                while (end < message.length() &&
                       (Character.isDigit(message.charAt(end)) || message.charAt(end) == ' '))
                    end++;
                try {
                    Env.inspectQueue.offer(Integer.parseInt(message.substring(colonIdx, end).trim()));
                } catch (NumberFormatException ignored) {}
            }
            return;
        }
        // C3: pause / resume / kill
        if (message.contains("\"pause\"")) {
            if (!Env.paused && !Env.terminating) {
                Env.paused = true;
                dispatchSimState("paused", Env.counter);
            }
            return;
        }
        if (message.contains("\"resume\"")) {
            if (Env.paused && !Env.terminating) {
                Env.paused = false;
                synchronized (Env.safeO) { Env.safeO.notifyAll(); }
                dispatchSimState("running", Env.counter);
            }
            return;
        }
        if (message.contains("\"kill\"")) {
            if (!Env.terminating) {
                Env.terminating = true;
                Env.paused = false;   // wake any pause wait
                synchronized (Env.safeO) { Env.safeO.notifyAll(); }
                dispatchSimState("terminating", Env.counter);
            }
            return;
        }
        System.out.println("LiveFrameServer: unrecognised message: " + message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("LiveFrameServer: error"
                + (conn != null ? " [" + conn.getRemoteSocketAddress() + "]" : "")
                + ": " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("LiveFrameServer: ready");
    }

    // ── dispatch helpers (called from simulation or WebSocket thread) ─────────

    /** Builds the current simState message from Env flags. */
    private static String buildSimStateMsg() {
        String s = Env.terminating ? "terminating" : (Env.paused ? "paused" : "running");
        return "{\"topic\":\"simState\",\"payload\":{\"state\":\"" + s + "\",\"step\":" + Env.counter + "}}";
    }

    /**
     * Enqueues a simState message to all connected clients. Non-blocking,
     * drop-oldest semantics — same as dispatchFrame/dispatchInspectResult.
     */
    public static void dispatchSimState(String state, int step) {
        if (instance == null || !running || instance.clients.isEmpty()) return;
        String msg = "{\"topic\":\"simState\",\"payload\":{\"state\":\"" + state + "\",\"step\":" + step + "}}";
        for (Map.Entry<WebSocket, ClientState> entry : instance.clients.entrySet()) {
            WebSocket conn = entry.getKey();
            ClientState cs = entry.getValue();
            if (!conn.isOpen()) continue;
            if (!cs.queue.offer(msg)) {
                cs.queue.poll();
                cs.queue.offer(msg);
            }
        }
    }

    /**
     * Wraps inspectJson in the inspectResult envelope and enqueues it for every
     * connected client. Same non-blocking, drop-oldest semantics as dispatchFrame.
     */
    public static void dispatchInspectResult(String inspectJson) {
        if (instance == null || !running || instance.clients.isEmpty()) return;
        String msg = "{\"topic\":\"inspectResult\",\"payload\":" + inspectJson + "}";
        for (Map.Entry<WebSocket, ClientState> entry : instance.clients.entrySet()) {
            WebSocket conn = entry.getKey();
            ClientState state = entry.getValue();
            if (!conn.isOpen()) continue;
            if (!state.queue.offer(msg)) {
                state.queue.poll();
                state.queue.offer(msg);
            }
        }
    }

    /**
     * Wraps frameJson in the protocol envelope and enqueues it for every
     * connected client. Non-blocking: if a client's queue is full the oldest
     * frame is dropped to make room. Returns immediately regardless of network.
     */
    public static void dispatchFrame(String frameJson) {
        if (instance == null || !running || instance.clients.isEmpty()) return;

        String msg = "{\"topic\":\"frame\",\"payload\":" + frameJson + "}";

        for (Map.Entry<WebSocket, ClientState> entry : instance.clients.entrySet()) {
            WebSocket conn = entry.getKey();
            ClientState state = entry.getValue();
            if (!conn.isOpen()) continue;

            if (!state.queue.offer(msg)) {
                state.queue.poll();        // drop oldest
                state.queue.offer(msg);    // enqueue newest
            }
        }
    }
}
