package com.hatch.bijlibill;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.SSLSocketFactory;

/**
 * v3.7.35 — Gemini Live voice assistant (dependency-free).
 *
 * Replaces the on-device SpeechRecognizer + TextToSpeech brain with
 * Google's Gemini Live API: real voice-to-voice conversation over a
 * single WebSocket (mic audio up, model audio down, server-side
 * turn detection). The Assistant screen stays exactly as it was —
 * voice-only bubble UI, no text anywhere, mic colour is the status.
 *
 * Verified against the official docs 2026-11-21
 * (ai.google.dev/gemini-api/docs/pricing + aistudio.google.com/docs/
 * live-api/get-started-websocket):
 * - Model: {@link #MODEL} = "gemini-3.8-live" — the GA low-latency
 *   voice model. The official pricing page lists it (together with
 *   gemini-3.1-flash-live-preview) as Free Tier "Free of charge"
 *   for input AND output; paid is $0.005/min audio in + $0.018/min
 *   audio out (~$0.023/min combined). Free tier is rate-limited
 *   and for prototyping; Google no longer publishes fixed free
 *   numbers — the live quota shows in AI Studio.
 * - Endpoint: wss://generativelanguage.googleapis.com/ws/
 *   google.ai.generativelanguage.v1beta.GenerativeService.
 *   BidiGenerateContent?key=API_KEY
 * - First message: {"setup": {"model": "models/<model>",
 *   "responseModalities": ["AUDIO"], "generationConfig":
 *   {"speechConfig": {"voiceConfig": {"prebuiltVoiceConfig":
 *   {"voiceName": "<voice>"}}}}, "systemInstruction": {"parts":
 *   [{"text": "..."}]}, "inputAudioTranscription": {},
 *   "outputAudioTranscription": {}}}
 * - Mic audio up: {"realtimeInput": {"audio": {"data": base64,
 *   "mimeType": "audio/pcm;rate=16000"}}} — 16-bit PCM, 16kHz,
 *   mono, little-endian.
 * - Model audio down: serverContent.modelTurn.parts[].inlineData
 *   .data (base64) — 16-bit PCM, 24kHz, mono.
 *
 * No new libraries: the WebSocket is hand-rolled over
 * java.net.Socket + SSLSocketFactory (HTTP-upgrade handshake,
 * masked client frames, ping/pong, close, fragmentation), JSON is
 * built by string concat + a minimal parser below, base64 is pure
 * Java (java.util.Base64 needs API 26; minSdk is 24).
 *
 * The API key lives in ONE place only: {@link ApiKeys#GEMINI_API_KEY}
 * (gitignored file, never committed). With no key configured
 * ("REPLACE_ME") the Assistant falls back to the free on-device
 * brain — nothing regresses. On a network failure the caller is
 * told via {@link Listener#onError(String)} with
 * {@link #ERR_NETWORK}; the Assistant speaks the Roman Urdu error
 * and retries.
 */
public class GeminiLiveClient {

    /** Live model (GA, free tier "Free of charge" — see class doc). */
    public static final String MODEL = "gemini-3.8-live";
    /** Natural voice for the model (prebuilt voice). */
    public static final String VOICE = "Aoede";

    public static final String HOST = "generativelanguage.googleapis.com";
    private static final String WS_PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService"
            + ".BidiGenerateContent";
    private static final String WS_GUID =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Mic: 16kHz mono 16-bit PCM (what the Live API wants). */
    public static final int MIC_RATE = 16000;
    /** Model audio out: 24kHz mono 16-bit PCM. */
    public static final int PLAY_RATE = 24000;

    /** onError kinds. */
    public static final String ERR_NETWORK = "network";
    public static final String ERR_AUTH = "auth";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;

    /** Callbacks — always delivered on the main thread. */
    public interface Listener {
        /** Setup accepted by the server; mic is now streaming. */
        void onConnected();
        /** The model started / stopped speaking. */
        void onSpeaking(boolean speaking);
        /** Session failed: {@link #ERR_NETWORK} or {@link #ERR_AUTH}. */
        void onError(String kind);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private volatile boolean stopped = false;
    private volatile boolean started = false;
    private volatile boolean connected = false;
    private volatile boolean speaking = false;

    private Thread connectThread;
    private Thread micThread;
    private Socket socket;
    private OutputStream out;
    private final Object sendLock = new Object();
    private AudioTrack audioTrack;
    private String systemInstruction = "";

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    /** True when a real key is present (not the REPLACE_ME stub). */
    public static boolean isConfigured() {
        return isConfiguredKey(ApiKeys.GEMINI_API_KEY);
    }

    /** Pure helper (unit-tested): a key counts only when it is a
     *  real, non-placeholder value. */
    public static boolean isConfiguredKey(String key) {
        return key != null && !key.trim().isEmpty()
                && !"REPLACE_ME".equals(key.trim());
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    /** Opens the Live session on a background thread. The first
     *  message is the setup (model + voice + system instruction
     *  built from the saved bill snapshots). */
    public synchronized void connect(String systemInstruction) {
        if (started) return;
        started = true;
        stopped = false;
        this.systemInstruction = systemInstruction == null ? ""
                : systemInstruction;
        connectThread = new Thread(new Runnable() {
            @Override public void run() {
                connectLoop();
            }
        }, "gemini-live-connect");
        connectThread.start();
    }

    /** Closes the session: stops mic + playback, closes the socket.
     *  Safe to call twice, from any thread. */
    public synchronized void close() {
        stopped = true;
        started = false;
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        try {
            if (micThread != null) micThread.interrupt();
        } catch (Exception ignored) {
        }
        releaseAudio();
    }

    /** Asks the model to greet the user briefly in Roman Urdu.
     *  Call after {@link Listener#onConnected()}. */
    public void sendGreeting(String greeting) {
        if (!connected || stopped) return;
        String g = greeting == null || greeting.trim().isEmpty()
                ? "Assalamualaikum! Main aapka Bijli Bill Assistant hun."
                : greeting.trim();
        String json = buildGreetingJson(
                "Greet the user briefly in Roman Urdu with this exact "
                + "greeting, then stop and wait for their question: \""
                + g + "\"");
        sendFrame(json);
    }

    // ------------------------------------------------------------------
    // Connection (background thread)
    // ------------------------------------------------------------------

    private void connectLoop() {
        Socket sock = null;
        try {
            SSLSocketFactory factory =
                    (SSLSocketFactory) SSLSocketFactory.getDefault();
            sock = factory.createSocket();
            sock.connect(new InetSocketAddress(HOST, 443),
                    CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(CONNECT_TIMEOUT_MS);
            OutputStream o = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            String wsKey = randomWsKey();
            String req = "GET " + WS_PATH + "?key="
                    + ApiKeys.GEMINI_API_KEY + " HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            o.write(req.getBytes(StandardCharsets.UTF_8));
            o.flush();

            String headers = readHeaders(in);
            if (!headers.startsWith("HTTP/1.1 101")
                    && !headers.startsWith("HTTP/1.0 101")) {
                int code = parseHttpStatus(headers);
                fail(code == 400 || code == 401 || code == 403
                        ? ERR_AUTH : ERR_NETWORK);
                return;
            }
            String accept = headerValue(headers, "Sec-WebSocket-Accept");
            if (accept == null
                    || !accept.equals(computeAccept(wsKey))) {
                fail(ERR_NETWORK);
                return;
            }
            sock.setSoTimeout(0); // blocking reads from here on
            synchronized (this) {
                if (stopped) {
                    try {
                        sock.close();
                    } catch (Exception ignored) {
                    }
                    return;
                }
                socket = sock;
                out = o;
            }

            sendFrame(buildSetupJson(MODEL, VOICE, systemInstruction));

            WsReader reader = new WsReader(in, o);
            while (!stopped) {
                String msg = reader.readMessage();
                if (msg == null) break; // clean close / EOF
                handleServerMessage(msg);
            }
            if (!stopped) {
                // Server closed the session (before or after setup).
                fail(ERR_NETWORK);
            }
        } catch (IOException e) {
            if (!stopped) fail(ERR_NETWORK);
        } catch (Exception e) {
            if (!stopped) fail(ERR_NETWORK);
        } finally {
            try {
                if (sock != null) sock.close();
            } catch (Exception ignored) {
            }
            releaseAudio();
        }
    }

    private void fail(final String kind) {
        if (stopped) return;
        close();
        postError(kind);
    }

    private void postError(final String kind) {
        final Listener l = listener;
        if (l == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    l.onError(kind);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void postConnected() {
        final Listener l = listener;
        if (l == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    l.onConnected();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void setSpeaking(boolean s) {
        if (speaking == s) return;
        speaking = s;
        final boolean v = s;
        final Listener l = listener;
        if (l == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    l.onSpeaking(v);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Server messages (receiver thread)
    // ------------------------------------------------------------------

    private void handleServerMessage(String json) {
        if (serverSetupComplete(json)) {
            connected = true;
            startMic();
            postConnected();
            return;
        }
        for (String data : extractServerAudioParts(json)) {
            try {
                playPcm(base64Decode(data));
            } catch (Exception ignored) {
            }
        }
        if (serverInterrupted(json)) {
            try {
                if (audioTrack != null) audioTrack.flush();
            } catch (Exception ignored) {
            }
            setSpeaking(false);
        }
        if (serverTurnComplete(json)) {
            setSpeaking(false);
        }
    }

    /** True when the server message is the setup-accepted signal. */
    public static boolean serverSetupComplete(String serverJson) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(serverJson));
            return m != null && m.containsKey("setupComplete");
        } catch (Exception e) {
            return false;
        }
    }

    /** Base64 model-audio payloads in a server message (usually one). */
    public static List<String> extractServerAudioParts(String serverJson) {
        List<String> out = new ArrayList<String>();
        try {
            Map<String, Object> m = Json.obj(Json.parse(serverJson));
            if (m == null) return out;
            Map<String, Object> sc = Json.obj(m.get("serverContent"));
            if (sc == null) return out;
            Map<String, Object> turn = Json.obj(sc.get("modelTurn"));
            if (turn == null) return out;
            List<Object> parts = Json.arr(turn.get("parts"));
            if (parts == null) return out;
            for (Object p : parts) {
                Map<String, Object> part = Json.obj(p);
                if (part == null) continue;
                Map<String, Object> inline =
                        Json.obj(part.get("inlineData"));
                if (inline == null) continue;
                String mime = Json.str(inline.get("mimeType"));
                if (mime != null && !mime.startsWith("audio/")) continue;
                String data = Json.str(inline.get("data"));
                if (data != null && !data.isEmpty()) out.add(data);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** True when the server message says the model was interrupted. */
    public static boolean serverInterrupted(String serverJson) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(serverJson));
            if (m == null) return false;
            Map<String, Object> sc = Json.obj(m.get("serverContent"));
            return sc != null && Json.bool(sc.get("interrupted"), false);
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the server message ends the model's turn. */
    public static boolean serverTurnComplete(String serverJson) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(serverJson));
            if (m == null) return false;
            Map<String, Object> sc = Json.obj(m.get("serverContent"));
            return sc != null && Json.bool(sc.get("turnComplete"), false);
        } catch (Exception e) {
            return false;
        }
    }

    private void playPcm(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || stopped) return;
        try {
            ensureAudioTrack();
            if (audioTrack == null) return;
            setSpeaking(true);
            audioTrack.write(pcm, 0, pcm.length);
        } catch (Exception ignored) {
        }
    }

    private void ensureAudioTrack() {
        if (audioTrack != null) return;
        try {
            int minBuf = AudioTrack.getMinBufferSize(PLAY_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = 4096;
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC,
                    PLAY_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
                    AudioTrack.MODE_STREAM);
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                try {
                    t.release();
                } catch (Exception ignored) {
                }
                return;
            }
            t.play();
            audioTrack = t;
        } catch (Exception ignored) {
            audioTrack = null;
        }
    }

    private void releaseAudio() {
        setSpeaking(false);
        try {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
        } catch (Exception ignored) {
        }
        audioTrack = null;
    }

    // ------------------------------------------------------------------
    // Mic (background thread; starts only after setupComplete)
    // ------------------------------------------------------------------

    private synchronized void startMic() {
        if (stopped || micThread != null) return;
        micThread = new Thread(new Runnable() {
            @Override public void run() {
                micLoop();
            }
        }, "gemini-live-mic");
        micThread.start();
    }

    private void micLoop() {
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = 4096;
            rec = createAudioRecord(minBuf);
            if (rec == null
                    || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                return;
            }
            rec.startRecording();
            // 20ms chunks at 16kHz mono 16-bit = 640 bytes.
            byte[] buf = new byte[640];
            while (!stopped && connected) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) sendAudio(buf, n);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * VOICE_COMMUNICATION first (echo cancellation — the mic would
     * otherwise hear the model's own voice from the speaker), MIC
     * as fallback.
     */
    private AudioRecord createAudioRecord(int minBuf) {
        try {
            AudioRecord r = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            try {
                r.release();
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        try {
            return new AudioRecord(MediaRecorder.AudioSource.MIC,
                    MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void sendAudio(byte[] pcm, int len) {
        if (!connected || stopped) return;
        byte[] exact = new byte[len];
        System.arraycopy(pcm, 0, exact, 0, len);
        sendFrame(buildAudioJson(exact));
    }

    private void sendFrame(String json) {
        if (json == null) return;
        sendFrame(json.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(byte[] payload) {
        OutputStream o;
        synchronized (this) {
            o = out;
        }
        if (o == null || stopped) return;
        synchronized (sendLock) {
            try {
                writeTextFrame(o, payload);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Handshake helpers
    // ------------------------------------------------------------------

    private static String randomWsKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return base64Encode(b);
    }

    /** Reads HTTP response headers up to the blank line. */
    public static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            buf.write(c);
            if (buf.size() > 16384) break;
            if (buf.size() >= 4) {
                byte[] b = buf.toByteArray();
                int n = b.length;
                if (b[n - 4] == '\r' && b[n - 3] == '\n'
                        && b[n - 2] == '\r' && b[n - 1] == '\n') break;
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** "HTTP/1.1 101 Switching Protocols" -> 101; -1 when unparsable. */
    public static int parseHttpStatus(String headers) {
        try {
            if (headers == null) return -1;
            String line = headers.split("\r\n|\n", 2)[0];
            String[] parts = line.split(" ");
            if (parts.length >= 2) return Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Case-insensitive header lookup (single value). */
    public static String headerValue(String headers, String name) {
        if (headers == null || name == null) return null;
        String[] lines = headers.split("\r\n|\n");
        String want = name.toLowerCase(java.util.Locale.US) + ":";
        for (String l : lines) {
            if (l.toLowerCase(java.util.Locale.US).startsWith(want)) {
                return l.substring(want.length()).trim();
            }
        }
        return null;
    }

    /** RFC 6455 Sec-WebSocket-Accept: base64(sha1(key + GUID)). */
    public static String computeAccept(String wsKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(
                    (wsKey + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return base64Encode(hash);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Outgoing JSON (pure — unit-tested)
    // ------------------------------------------------------------------

    /** JSON string escaping (quotes, backslash, control chars). */
    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.US,
                                "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** First message of the session (see class doc for the shape). */
    public static String buildSetupJson(String model, String voice,
            String systemInstruction) {
        return "{\"setup\":{\"model\":\"models/" + escapeJson(model)
                + "\",\"responseModalities\":[\"AUDIO\"]"
                + ",\"generationConfig\":{\"speechConfig\":{\"voiceConfig\":"
                + "{\"prebuiltVoiceConfig\":{\"voiceName\":\""
                + escapeJson(voice) + "\"}}}}"
                + ",\"systemInstruction\":{\"parts\":[{\"text\":\""
                + escapeJson(systemInstruction) + "\"}]}"
                + ",\"inputAudioTranscription\":{}"
                + ",\"outputAudioTranscription\":{}}}";
    }

    /** One mic chunk: 16-bit PCM 16kHz mono, base64. */
    public static String buildAudioJson(byte[] pcm) {
        return "{\"realtimeInput\":{\"audio\":{\"data\":\""
                + base64Encode(pcm)
                + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}";
    }

    /** A text turn (used for the greeting on connect). */
    public static String buildGreetingJson(String text) {
        return "{\"clientContent\":{\"turns\":[{\"role\":\"user\","
                + "\"parts\":[{\"text\":\"" + escapeJson(text)
                + "\"}]}],\"turnComplete\":true}}";
    }

    /**
     * System instruction from the saved bill snapshots — the model
     * answers ONLY from this data, in simple Roman Urdu (or Roman
     * Punjabi when the user speaks Punjabi). Short spoken answers:
     * 1-3 sentences, plain numbers, no lists, no markdown.
     */
    public static String buildSystemInstruction(
            List<BillSnapshots.Snapshot> snaps, String meterLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Bijli Bill Assistant, a friendly voice "
                + "helper in the Bijli Bill app (Pakistan electricity "
                + "bills).\n");
        sb.append("- Reply ONLY in simple Roman Urdu. If the user "
                + "speaks Punjabi, reply in simple Roman Punjabi.\n");
        sb.append("- Keep every answer short: 1 to 3 short sentences. "
                + "Plain numbers only, no lists, no markdown, "
                + "no emojis.\n");
        sb.append("- Answer ONLY from the bill data below. Never invent "
                + "units, amounts or dates. If the data does not cover "
                + "a question, say so honestly in Roman Urdu and "
                + "suggest fetching the latest bill in the app.\n");
        sb.append("- You can answer: why the bill is high (compare "
                + "units and FPA with the previous month), the due "
                + "date and days left, the previous month's bill, the "
                + "200-unit guard, FPA, and units.\n");
        sb.append("- Never ask for a reference number and never offer "
                + "to fetch a bill.\n");
        sb.append("\nSaved bills");
        if (meterLabel != null && !meterLabel.trim().isEmpty()) {
            sb.append(" for ").append(meterLabel.trim());
        }
        sb.append(":\n");
        if (snaps == null || snaps.isEmpty()) {
            sb.append("No bill has been fetched yet in this app. Tell "
                    + "the user, in Roman Urdu, that no bill is saved "
                    + "yet and they should fetch their bill first; do "
                    + "not invent any bill data.\n");
            return sb.toString();
        }
        BillSnapshots.Snapshot newest = snaps.get(0);
        for (BillSnapshots.Snapshot s : snaps) {
            if (s == null) continue;
            sb.append("- ");
            sb.append(s.billMonth == null || s.billMonth.isEmpty()
                    ? "latest bill" : s.billMonth.trim());
            sb.append(": ");
            if (s.units >= 0) sb.append(s.units).append(" units, ");
            if (s.amount >= 0) {
                sb.append("Rs ").append(AssistantBrain.fmtRs(s.amount));
                sb.append(", ");
            }
            if (s.dueDate != null && !s.dueDate.trim().isEmpty()) {
                sb.append("due ").append(s.dueDate.trim()).append(", ");
            }
            if (s.category != null && !s.category.trim().isEmpty()) {
                sb.append(s.category.trim()).append(", ");
            }
            if (s.fpa != null && !s.fpa.trim().isEmpty()) {
                sb.append("FPA ").append(s.fpa.trim()).append(", ");
            }
            if (s.prevReading != null && !s.prevReading.isEmpty()
                    && s.currReading != null
                    && !s.currReading.isEmpty()) {
                sb.append("readings ").append(s.prevReading.trim())
                        .append(" to ").append(s.currReading.trim())
                        .append(", ");
            }
            // Trim the trailing ", " of this line.
            int n = sb.length();
            if (n >= 2 && sb.charAt(n - 2) == ','
                    && sb.charAt(n - 1) == ' ') {
                sb.setLength(n - 2);
            }
            sb.append(".\n");
        }
        if (newest != null && newest.units > 200) {
            sb.append("200-unit guard: the newest bill is ")
                    .append(newest.units)
                    .append(" units — ABOVE 200, so the Protected "
                            + "status has ended and the rate is higher. "
                            + "Warn the user clearly when asked.\n");
        } else if (newest != null && newest.units >= 0) {
            sb.append("200-unit guard: the newest bill is ")
                    .append(newest.units)
                    .append(" units — still under 200, so the "
                            + "Protected status is safe for now.\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Base64 (pure Java — java.util.Base64 needs API 26, minSdk 24)
    // ------------------------------------------------------------------

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                    .toCharArray();
    private static final int[] B64_REV = new int[128];
    static {
        for (int i = 0; i < B64_REV.length; i++) B64_REV[i] = -1;
        for (int i = 0; i < B64.length; i++) B64_REV[B64[i]] = i;
        B64_REV['='] = 0;
    }

    public static String base64Encode(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(
                ((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[((b0 & 0x03) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < data.length
                    ? B64[((b1 & 0x0F) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < data.length ? B64[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }

    public static byte[] base64Decode(String s) {
        if (s == null) return new byte[0];
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/'
                    || c == '=') {
                clean.append(c);
            }
        }
        String t = clean.toString();
        if (t.length() % 4 != 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (t.length() / 4) * 3);
        for (int i = 0; i < t.length(); i += 4) {
            int v0 = B64_REV[t.charAt(i)];
            int v1 = B64_REV[t.charAt(i + 1)];
            int v2 = B64_REV[t.charAt(i + 2)];
            int v3 = B64_REV[t.charAt(i + 3)];
            if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) {
                return new byte[0];
            }
            out.write((v0 << 2) | (v1 >> 4));
            if (t.charAt(i + 2) != '=') {
                out.write(((v1 & 0x0F) << 4) | (v2 >> 2));
            }
            if (t.charAt(i + 3) != '=') {
                out.write(((v2 & 0x03) << 6) | v3);
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // WebSocket framing (pure — unit-tested)
    // ------------------------------------------------------------------

    /** One masked client text frame (FIN + opcode 1 + mask bit). */
    public static void writeTextFrame(OutputStream out, byte[] payload)
            throws IOException {
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.write(0x81);
        int len = payload.length;
        if (len < 126) {
            head.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            head.write(0x80 | 126);
            head.write((len >> 8) & 0xFF);
            head.write(len & 0xFF);
        } else {
            head.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                head.write((len >> (8 * i)) & 0xFF);
            }
        }
        head.write(mask);
        out.write(head.toByteArray());
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        out.write(masked);
        out.flush();
    }

    /** Unmasks payload bytes in place (client frames are masked). */
    public static void unmaskInPlace(byte[] payload, byte[] mask) {
        if (payload == null || mask == null || mask.length != 4) return;
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
    }

    /**
     * Minimal WebSocket reader: reassembles fragmented text messages,
     * answers pings with pongs, echoes close frames and returns null
     * on clean close / EOF. Server frames are unmasked; masked
     * frames are handled too.
     */
    public static final class WsReader {
        private final InputStream in;
        private final OutputStream out;

        public WsReader(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        /** Next complete text message, or null on close / EOF. */
        public String readMessage() throws IOException {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            boolean inFragment = false;
            while (true) {
                int b0 = in.read();
                if (b0 < 0) return null;
                int b1 = in.read();
                if (b1 < 0) return null;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | readByte();
                    }
                }
                if (len < 0 || len > MAX_FRAME_BYTES) {
                    throw new IOException("bad frame length: " + len);
                }
                byte[] mask = masked ? readExact(4) : null;
                byte[] payload = readExact((int) len);
                if (mask != null) unmaskInPlace(payload, mask);
                if (op == 0x9) { // ping -> pong
                    writePong(payload);
                    continue;
                }
                if (op == 0x8) { // close -> echo, then end
                    try {
                        writeCloseFrame();
                    } catch (Exception ignored) {
                    }
                    return null;
                }
                if (op == 0xA) continue; // pong — ignore
                if (op == 0x1 || op == 0x2 || op == 0x0) {
                    msg.write(payload);
                    if (!fin) {
                        inFragment = true;
                        continue;
                    }
                    if (inFragment || op == 0x1 || op == 0x2) {
                        return new String(msg.toByteArray(),
                                StandardCharsets.UTF_8);
                    }
                }
                // Unknown opcode — ignore the frame.
            }
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new IOException("EOF in frame header");
            return b;
        }

        private byte[] readExact(int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) throw new IOException("EOF in frame");
                off += r;
            }
            return buf;
        }

        private void writePong(byte[] payload) throws IOException {
            synchronized (out) {
                out.write(0x8A);
                int len = payload == null ? 0 : payload.length;
                if (len < 126) {
                    out.write(len);
                } else if (len <= 0xFFFF) {
                    out.write(126);
                    out.write((len >> 8) & 0xFF);
                    out.write(len & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((len >> (8 * i)) & 0xFF);
                    }
                }
                if (len > 0) out.write(payload);
                out.flush();
            }
        }

        private void writeCloseFrame() throws IOException {
            synchronized (out) {
                out.write(0x88);
                out.write(0x00);
                out.flush();
            }
        }
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (pure — unit-tested). Objects ->
    // LinkedHashMap, arrays -> List, strings -> String, numbers ->
    // Double, true/false/null as usual. Enough for the Live API's
    // server messages; the app never parses arbitrary JSON with it.
    // ------------------------------------------------------------------

    public static final class Json {
        static Object parse(String s) {
            int[] p = new int[]{0};
            skipWs(s, p);
            Object v = value(s, p);
            skipWs(s, p);
            if (p[0] != s.length()) throw new IllegalArgumentException(
                    "trailing data at " + p[0]);
            return v;
        }

        static Map<String, Object> obj(Object o) {
            if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                return m;
            }
            return null;
        }

        static List<Object> arr(Object o) {
            if (o instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) o;
                return l;
            }
            return null;
        }

        static String str(Object o) {
            return o instanceof String ? (String) o : null;
        }

        static boolean bool(Object o, boolean def) {
            return o instanceof Boolean ? (Boolean) o : def;
        }

        private static void skipWs(String s, int[] p) {
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    p[0]++;
                } else {
                    break;
                }
            }
        }

        private static Object value(String s, int[] p) {
            if (p[0] >= s.length()) throw new IllegalArgumentException(
                    "unexpected end");
            char c = s.charAt(p[0]);
            if (c == '{') return object(s, p);
            if (c == '[') return array(s, p);
            if (c == '"') return string(s, p);
            if (c == 't') {
                expect(s, p, "true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect(s, p, "false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect(s, p, "null");
                return null;
            }
            return number(s, p);
        }

        private static void expect(String s, int[] p, String lit) {
            if (!s.startsWith(lit, p[0])) throw new IllegalArgumentException(
                    "bad literal at " + p[0]);
            p[0] += lit.length();
        }

        private static Map<String, Object> object(String s, int[] p) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            p[0]++; // {
            skipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == '}') {
                p[0]++;
                return m;
            }
            while (true) {
                skipWs(s, p);
                String k = string(s, p);
                skipWs(s, p);
                if (p[0] >= s.length() || s.charAt(p[0]) != ':') {
                    throw new IllegalArgumentException(
                            "expected : at " + p[0]);
                }
                p[0]++;
                skipWs(s, p);
                m.put(k, value(s, p));
                skipWs(s, p);
                if (p[0] >= s.length()) throw new IllegalArgumentException(
                        "unterminated object");
                char c = s.charAt(p[0]);
                if (c == ',') {
                    p[0]++;
                } else if (c == '}') {
                    p[0]++;
                    return m;
                } else {
                    throw new IllegalArgumentException(
                            "expected , or } at " + p[0]);
                }
            }
        }

        private static List<Object> array(String s, int[] p) {
            List<Object> l = new ArrayList<Object>();
            p[0]++; // [
            skipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ']') {
                p[0]++;
                return l;
            }
            while (true) {
                skipWs(s, p);
                l.add(value(s, p));
                skipWs(s, p);
                if (p[0] >= s.length()) throw new IllegalArgumentException(
                        "unterminated array");
                char c = s.charAt(p[0]);
                if (c == ',') {
                    p[0]++;
                } else if (c == ']') {
                    p[0]++;
                    return l;
                } else {
                    throw new IllegalArgumentException(
                            "expected , or ] at " + p[0]);
                }
            }
        }

        private static String string(String s, int[] p) {
            if (s.charAt(p[0]) != '"') throw new IllegalArgumentException(
                    "expected string at " + p[0]);
            p[0]++;
            StringBuilder sb = new StringBuilder();
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (p[0] >= s.length()) break;
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (p[0] + 4 > s.length()) {
                                throw new IllegalArgumentException(
                                        "bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(
                                    s.substring(p[0], p[0] + 4), 16));
                            p[0] += 4;
                            break;
                        default:
                            sb.append(e);
                            break;
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private static Double number(String s, int[] p) {
            int start = p[0];
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+'
                        || c == '.' || c == 'e' || c == 'E') {
                    p[0]++;
                } else {
                    break;
                }
            }
            if (p[0] == start) throw new IllegalArgumentException(
                    "bad number at " + start);
            return Double.parseDouble(s.substring(start, p[0]));
        }
    }
}
