package boxOfActin;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import org.json.JSONException;

import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * WebSocket server for live frame streaming (-3jsLive flag).
 *
 * Protocol (C1-C4):
 *   Server → client: {"topic":"frame",        "payload":{...frame JSON...}}
 *                    {"topic":"inspectResult", "payload":{...}}
 *                    {"topic":"simState",      "payload":{"state":"running"|"paused"|"terminating","step":<N>}}
 *                    {"topic":"paramList",     "payload":[{"name","displayName","type","value","mutable"}, ...]}
 *                    {"topic":"paramAck",      "payload":{"success":bool,"name","oldValue","newValue"} or {"success":false,"name","error":"..."}}
 *   Client → server: {"action":"subscribe","topics":["frame","inspectResult","simState","paramAck","paramList"]}
 *                    {"action":"inspect","id":<N>}
 *                    {"action":"pause"}
 *                    {"action":"resume"}
 *                    {"action":"kill"}
 *                    {"action":"queryParams"}
 *                    {"action":"setParam","name":"<label>","value":"<stringVal>"}
 *
 * Each client has a bounded outgoing queue (size 4). If a slow client falls
 * behind, the oldest queued frame is dropped to make room for the newest.
 * The simulation's dispatch*() calls are always non-blocking.
 *
 * setParam validation happens immediately on the WebSocket thread (bad name / immutable /
 * parse error → immediate paramAck failure). Valid changes are queued in Env.paramQueue
 * and applied at the safe point in BoxOfActin.doLoop(); the success paramAck is dispatched
 * at that point after the value is applied.
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
        // C4: queryParams — build and dispatch the full parameter list
        if (message.contains("\"queryParams\"")) {
            String paramListJson = buildParamListJson();
            dispatchTo(conn, "{\"topic\":\"paramList\",\"payload\":" + paramListJson + "}");
            return;
        }
        // C4: setParam — parse, validate, queue or reject
        if (message.contains("\"setParam\"")) {
            try {
                JSONObject obj = new JSONObject(message);
                String paramName = obj.getString("name");
                String valueStr  = obj.getString("value");
                handleSetParam(paramName, valueStr);
            } catch (JSONException e) {
                System.err.println("LiveFrameServer: malformed setParam: " + e.getMessage());
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
     * Dispatches a benchmark-topic message to all clients. Called from BoxOfActin
     * at the same cadence as dispatchFrame() when benchmark mode is active.
     */
    public static void dispatchBenchmark(String benchmarkJson) {
        if (instance == null || !running || instance.clients.isEmpty()) return;
        String msg = "{\"topic\":\"benchmark\",\"payload\":" + benchmarkJson + "}";
        enqueueAll(msg);
    }

    /**
     * Dispatches an lpBenchmark-topic message to all clients. Called from BoxOfActin
     * at the same cadence as dispatchBenchmark() when benchmark mode is active.
     */
    public static void dispatchLpBenchmark(String lpJson) {
        if (instance == null || !running || instance.clients.isEmpty()) return;
        String msg = "{\"topic\":\"lpBenchmark\",\"payload\":" + lpJson + "}";
        enqueueAll(msg);
    }

    /**
     * Dispatches a glidingAssay-topic message to all clients. Called from BoxOfActin
     * at the same cadence as dispatchFrame() when glidingAssay mode is active.
     */
    public static void dispatchGlidingAssay(String gaJson) {
        if (instance == null || !running || instance.clients.isEmpty()) return;
        String msg = "{\"topic\":\"glidingAssay\",\"payload\":" + gaJson + "}";
        enqueueAll(msg);
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

    // ── C4: parameter adjustment ──────────────────────────────────────────────

    /** Enqueues msg to a single connection (used for queryParams response). */
    private static void dispatchTo(WebSocket conn, String msg) {
        if (instance == null || !running) return;
        ClientState cs = instance.clients.get(conn);
        if (cs == null || !conn.isOpen()) return;
        if (!cs.queue.offer(msg)) {
            cs.queue.poll();
            cs.queue.offer(msg);
        }
    }

    /**
     * Dispatches paramAck (success) to all clients. Called from BoxOfActin.drainParamQueue()
     * at the safe point after the value has been applied.
     */
    public static void dispatchParamAck(String name, double oldValue, double newValue) {
        if (instance == null || !running || instance.clients.isEmpty()) return;
        String msg = String.format(
            "{\"topic\":\"paramAck\",\"payload\":{\"success\":true,\"name\":\"%s\",\"oldValue\":%s,\"newValue\":%s}}",
            name, formatNum(oldValue), formatNum(newValue));
        enqueueAll(msg);
    }

    /**
     * Dispatches a paramAck error (validation failure) to all clients immediately
     * on the WebSocket thread — before the change reaches the safe point.
     */
    private static void dispatchParamAckError(String name, String error) {
        if (instance == null || !running || instance.clients.isEmpty()) return;
        String msg = String.format(
            "{\"topic\":\"paramAck\",\"payload\":{\"success\":false,\"name\":\"%s\",\"error\":\"%s\"}}",
            name, escapeJson(error));
        enqueueAll(msg);
    }

    private static void enqueueAll(String msg) {
        for (Map.Entry<WebSocket, ClientState> entry : instance.clients.entrySet()) {
            WebSocket conn = entry.getKey();
            ClientState cs  = entry.getValue();
            if (!conn.isOpen()) continue;
            if (!cs.queue.offer(msg)) {
                cs.queue.poll();
                cs.queue.offer(msg);
            }
        }
    }

    /**
     * Validates and queues a setParam change, or dispatches an immediate error ack.
     * Called on the WebSocket thread; queue is drained at the simulation safe point.
     */
    private static void handleSetParam(String name, String valueStr) {
        // Look up parameter by label
        Parameter target = null;
        for (int i = 0; i < Parameter.paramCt; i++) {
            Parameter p = Parameter.theParams[i];
            if (p != null && name.equals(p.label)) { target = p; break; }
        }
        if (target == null) {
            dispatchParamAckError(name, "unknown parameter");
            return;
        }
        if (!target.isMutableAtRuntime()) {
            dispatchParamAckError(name, "not mid-run mutable");
            return;
        }
        // Parse value per type
        double parsedValue;
        try {
            if (target.type == Parameter.BOOLEAN) {
                // accept true/false or 1/0
                String v = valueStr.trim().toLowerCase();
                if (v.equals("true") || v.equals("1"))       parsedValue = 1.0;
                else if (v.equals("false") || v.equals("0")) parsedValue = 0.0;
                else throw new NumberFormatException("expected true/false/1/0");
            } else {
                parsedValue = Double.parseDouble(valueStr.trim());
            }
        } catch (NumberFormatException e) {
            dispatchParamAckError(name, "parse error: " + e.getMessage());
            return;
        }
        // Optional: apply positiveValue constraint
        if (target.positiveValue && parsedValue < 0) {
            dispatchParamAckError(name, "value must be non-negative");
            return;
        }
        Env.paramQueue.offer(new Env.PendingParamChange(target, parsedValue));
    }

    /** Builds the paramList JSON array for all registered Parameters. */
    private static String buildParamListJson() {
        StringBuilder sb = new StringBuilder(Parameter.paramCt * 80);
        sb.append("[");
        boolean first = true;
        for (int i = 0; i < Parameter.paramCt; i++) {
            Parameter p = Parameter.theParams[i];
            if (p == null) continue;
            if (!first) sb.append(",");
            String typeStr;
            if      (p.type == Parameter.BOOLEAN) typeStr = "boolean";
            else if (p.type == Parameter.INT)     typeStr = "int";
            else                                   typeStr = "double";
            String valStr;
            if (p.type == Parameter.BOOLEAN)
                valStr = p.getValue() != 0 ? "true" : "false";
            else
                valStr = formatNum(p.getValue());
            sb.append(String.format(
                "{\"name\":\"%s\",\"displayName\":\"%s\",\"type\":\"%s\",\"value\":%s,\"mutable\":%b}",
                escapeJson(p.label), escapeJson(p.parameterName.trim()),
                typeStr, valStr, p.isMutableAtRuntime()));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /** Formats a double without needless trailing zeros; ints print without decimal. */
    private static String formatNum(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15)
            return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
