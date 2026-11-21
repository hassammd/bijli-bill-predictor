package com.hatch.bijlibill;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v3.7.34 — AI Assistant (v2, VOICE-ONLY, no text at all). The whole
 * conversation is voice: NO transcript bubbles, NO suggestion chips,
 * NO status text, NO voice toggle — the user opens the screen, the
 * Assistant greets by voice and listens at once. Replies are spoken
 * in short sentence chunks (fast start, no stutter) with the best
 * available ur-PK voice at 0.95x rate, and the recognizer uses short
 * silence timeouts so answers start promptly. Errors (mic denied /
 * not understood) are SPOKEN, never shown as text. The mic itself
 * shows the state by colour: teal idle, amber listening, green
 * speaking — and it pulses while hearing.
 *
 * v3.7.33 — AI Assistant (v2, VOICE-ONLY). A voice-call-style screen
 * that answers the user's questions using ONLY the locally saved bill
 * snapshots — no internet, no API key, no server, no cost.
 *
 * There is NO text input any more: the user taps the big mic (or the
 * floating bubble on Home) and talks, the Assistant answers by voice
 * — a direct voice conversation, like a phone call. v3.7.34 removed
 * the last visible text too (transcript bubbles, suggestion chips,
 * status line, voice toggle) — the screen is voice-only.
 *
 * Voice in via Android SpeechRecognizer (ur-PK first; if it hears
 * nothing and the device reports pa-PK support, it retries in
 * pa-PK) + voice out via TextToSpeech (ur-PK, falls back to the
 * device default when Urdu TTS data is missing). Answers are
 * decided by {@link AssistantBrain} in simple Roman Urdu/Punjabi.
 *
 * v3.7.32: floating-bubble entry (Home) opens the Assistant with
 * EXTRA_AUTO_LISTEN — it greets by voice and starts listening at
 * once, no extra tap. When speech is not understood, the Assistant
 * says so INSIDE the chat and listens again (once); a second miss
 * asks the user to tap the mic and speak again. No Toast / system
 * notification is ever shown.
 *
 * Out of scope (later, gradually): photo/OCR, daily summary
 * notifications, challenges, comparisons, storytelling, anything
 * needing the internet — and NEVER any CAPTCHA auto-solve.
 */
public class AssistantActivity extends BaseActivity {

    /** Open with auto-listen: greet by voice, then start listening. */
    public static final String EXTRA_AUTO_LISTEN = "auto_listen";

    private static final int REQ_MIC = 101;
    /** One free retry after a missed hearing; then ask to tap mic. */
    private static final int MAX_LISTEN_RETRIES = 1;

    /** Big round mic (voice-call style). Its colour is the only
     *  status indicator — v3.7.34 has NO text anywhere: teal idle,
     *  amber listening, green speaking; it pulses while hearing. */
    private ImageButton bigMic;
    private GradientDrawable micBg;
    private static final int MIC_IDLE = 0;
    private static final int MIC_LISTENING = 1;
    private static final int MIC_SPEAKING = 2;
    private int micState = MIC_IDLE;
    private boolean listening = false;
    /** True after the user deliberately stops listening — the
     *  auto-listen-after-reply loop must not restart it then. */
    private boolean userStopped = false;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;

    // v3.7.34: every open greets by voice and listens at once
    // (bubble or Quick Tools — same voice-call flow).
    private boolean pendingGreeting = false;
    private boolean listenAfterError = false;
    private int listenFailures = 0;

    // v3.7.34: live-call feel — watch the partial transcript; when
    // it stops changing for ~700ms the user has finished speaking
    // and listening ends at once, instead of waiting for the
    // recognizer's own slow silence timeout or any text step.
    private String lastPartial = "";
    private long lastPartialAt = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable partialQuietCheck = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            if (System.currentTimeMillis() - lastPartialAt < 700) return;
            try {
                if (recognizer != null) recognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
    };

    // v3.7.33: recognizer language. ur-PK first; pa-PK retry only
    // when the device says it supports it (not every phone does).
    private String listenLang = "ur-PK";
    private boolean paSupported = false;
    private boolean triedPa = false;

    private List<BillSnapshots.Snapshot> snaps =
            new ArrayList<BillSnapshots.Snapshot>();
    private String meterLabel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        if (w != null) {
            w.setStatusBarColor(Ds.ndTealAccent());
            w.getDecorView().setSystemUiVisibility(0);
        }

        // Snapshots: newest bill across all saved meters.
        BillSnapshots.Latest latest = BillSnapshots.latestAny(this);
        if (latest != null && latest.snap != null) {
            snaps = BillSnapshots.list(this, latest.key);
            meterLabel = labelFor(latest.snap);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ds.bg());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(Ds.appBar(this, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }, getString(R.string.assistant_title),
                getString(R.string.assistant_sub), false));

        // v3.7.34: voice-call screen — NO text anywhere. One big mic,
        // centred, is the whole screen; its colour is the status
        // (teal idle, amber listening, green speaking). Tapping the
        // mic starts or stops listening. The Assistant greets by
        // voice and listens at once on every open.
        LinearLayout voicePanel = new LinearLayout(this);
        voicePanel.setOrientation(LinearLayout.VERTICAL);
        voicePanel.setGravity(Gravity.CENTER);
        voicePanel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bigMic = new ImageButton(this);
        bigMic.setImageResource(R.drawable.ic_mic);
        bigMic.setColorFilter(Color.WHITE);
        bigMic.setContentDescription(
                getString(R.string.assistant_bubble_desc));
        micBg = new GradientDrawable();
        micBg.setShape(GradientDrawable.OVAL);
        micBg.setColor(Ui.teal());
        bigMic.setBackground(micBg);
        bigMic.setScaleType(
                android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int micPad = Ui.dp(this, 22);
        bigMic.setPadding(micPad, micPad, micPad, micPad);
        int micSize = Ui.dp(this, 88);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(
                micSize, micSize);
        micLp.gravity = Gravity.CENTER;
        bigMic.setLayoutParams(micLp);
        bigMic.setElevation(Ui.dp(this, 4));
        bigMic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBigMicClicked(); }
        });
        voicePanel.addView(bigMic);
        root.addView(voicePanel);

        // Banner (same as other pages).
        LinearLayout bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        int mp = Ui.dp(this, 16);
        bannerBox.setPadding(mp, Ui.dp(this, 8), mp, Ui.dp(this, 8));
        root.addView(bannerBox);
        AdsManager.loadBannerInto(this, bannerBox);

        setContentView(root);

        queryPaSupport();
        initTts();
        // Greet by voice, then listen — on every open.
        pendingGreeting = true;
        tryStartConversation();
    }

    // ------------------------------------------------------------------
    // Q&A
    // ------------------------------------------------------------------

    private void sendQuestion(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        // v3.7.34: voice-only — the answer goes straight to speech,
        // no transcript bubble is ever shown.
        String ans = AssistantBrain.answer(q, snaps, meterLabel);
        speak(ans);
    }

    private String labelFor(BillSnapshots.Snapshot s) {
        String name = s.holderName == null || s.holderName.trim().isEmpty()
                ? SavedMeters.maskId(s.ref) : s.holderName.trim();
        return name + " (" + Disco.fromCode(s.disco).displayName + ", "
                + SavedMeters.maskId(s.ref) + ")";
    }

    /** v3.7.34: the mic colour is the only status indicator —
     *  teal = idle, amber = listening, green = speaking. */
    private void setMicState(int state) {
        micState = state;
        if (bigMic == null || micBg == null) return;
        int color;
        if (state == MIC_LISTENING) {
            color = Color.parseColor("#E8A33D"); // amber
        } else if (state == MIC_SPEAKING) {
            color = Color.parseColor("#2E9E6B"); // green
        } else {
            color = Ui.teal();
        }
        micBg.setColor(color);
    }

    // ------------------------------------------------------------------
    // Voice out (TextToSpeech)
    // ------------------------------------------------------------------

    private void initTts() {
        try {
            tts = new TextToSpeech(this,
                    new TextToSpeech.OnInitListener() {
                        @Override public void onInit(int status) {
                            if (status != TextToSpeech.SUCCESS
                                    || tts == null) {
                                // No voice out — still listen so the
                                // user can talk; answers just can't
                                // be spoken on this phone.
                                if (pendingGreeting) {
                                    pendingGreeting = false;
                                    startListening();
                                }
                                return;
                            }
                            int r = tts.setLanguage(
                                    new Locale("ur", "PK"));
                            if (r == TextToSpeech.LANG_MISSING_DATA
                                    || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                // Roman Urdu reads acceptably with the
                                // device default voice instead.
                                tts.setLanguage(Locale.getDefault());
                            }
                            // v3.7.34: pick the best Urdu voice on
                            // this phone (a default English voice
                            // reading Roman Urdu sounds robotic) and
                            // set a calm, near-natural rate.
                            selectBestUrduVoice();
                            try {
                                tts.setSpeechRate(0.95f);
                                tts.setPitch(1.0f);
                            } catch (Exception ignored) {
                            }
                            ttsReady = true;
                            tryStartConversation();
                        }
                    });
            // v3.7.32: when the spoken greeting ends (or fails), start
            // listening — the user never taps the mic after the bubble.
            // v3.7.33: when a spoken REPLY ends, listen again at once —
            // a direct voice conversation, like a phone call.
            // v3.7.34: replies are spoken in sentence chunks; only the
            // LAST chunk carries "assistant_reply", so listening
            // restarts after the whole reply, not mid-sentence.
            tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onDone(String id) {
                            if ("assistant_greeting".equals(id)) {
                                listenAfterGreeting();
                            } else if ("assistant_reply".equals(id)) {
                                listenAfterReply();
                            } else if ("assistant_error".equals(id)) {
                                listenAfterErrorSpoken();
                            }
                        }
                        @Override public void onError(String id) {
                            if ("assistant_greeting".equals(id)) {
                                listenAfterGreeting();
                            } else if ("assistant_reply".equals(id)) {
                                listenAfterReply();
                            } else if ("assistant_error".equals(id)) {
                                listenAfterErrorSpoken();
                            }
                        }
                    });
        } catch (Exception ignored) {
            tts = null;
            if (pendingGreeting) {
                pendingGreeting = false;
                startListening();
            }
        }
    }

    /**
     * v3.7.34: prefer a real Urdu voice — ur-PK first, then any
     * Urdu voice, then a voice whose name mentions Urdu. Without
     * this, phones with no Urdu TTS data fall back to an English
     * voice reading Roman Urdu, which sounds robotic.
     */
    private void selectBestUrduVoice() {
        try {
            if (tts == null) return;
            Set<Voice> voices = tts.getVoices();
            if (voices == null || voices.isEmpty()) return;
            Voice best = null;
            Voice anyUrdu = null;
            for (Voice v : voices) {
                if (v == null || v.getLocale() == null) continue;
                Locale l = v.getLocale();
                if ("ur".equals(l.getLanguage())) {
                    if (anyUrdu == null) anyUrdu = v;
                    if ("PK".equals(l.getCountry())) {
                        best = v;
                        break;
                    }
                } else if (best == null && v.getName() != null
                        && v.getName().toLowerCase(Locale.US)
                                .contains("urdu")) {
                    best = v;
                }
            }
            if (best == null) best = anyUrdu;
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) {
        }
    }

    private void listenAfterGreeting() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!isFinishing() && !listening) startListening();
            }
        });
    }

    /** v3.7.33: reply spoken — listen again for the next question,
     *  unless the user deliberately stopped listening. */
    private void listenAfterReply() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setMicState(MIC_IDLE);
                if (!isFinishing() && !listening && !userStopped) {
                    startListening();
                }
            }
        });
    }

    /** v3.7.34: a spoken retry prompt finished — listen again. */
    private void listenAfterErrorSpoken() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!listenAfterError) {
                    setMicState(MIC_IDLE);
                    return;
                }
                listenAfterError = false;
                if (!isFinishing() && !listening && !userStopped) {
                    startListening();
                }
            }
        });
    }

    /** v3.7.34: greet by voice, then listen — on every open. */
    private void tryStartConversation() {
        if (!pendingGreeting || isFinishing()) return;
        if (ttsReady && tts != null) {
            pendingGreeting = false;
            speakGreeting();
        }
        // Else TTS is still initialising — onInit calls
        // tryStartConversation().
    }

    private void speakGreeting() {
        if (!ttsReady || tts == null) {
            startListening();
            return;
        }
        try {
            speak(getString(R.string.assistant_voice_greeting),
                    "assistant_greeting");
        } catch (Exception ignored) {
            startListening();
        }
    }

    /** Spoken reply, chunked into short sentences (v3.7.34). */
    private void speak(String text) {
        speak(text, "assistant_reply");
    }

    /**
     * v3.7.34: speak in sentence chunks — first chunk QUEUE_FLUSH,
     * the rest QUEUE_ADD, only the last chunk carrying the given
     * utterance id. A long answer as one speak() call stutters on
     * many TTS engines; short chunks start fast and sound smooth.
     * Speech is sanitised for the ear: newlines and bullet dashes
     * become sentence breaks, em-dashes become commas.
     */
    private void speak(String text, String finalUtteranceId) {
        if (!ttsReady || tts == null) {
            // No voice out right now — keep the conversation going
            // by listening again instead of stalling.
            if (!isFinishing() && !listening && !userStopped) {
                startListening();
            }
            return;
        }
        setMicState(MIC_SPEAKING);
        try {
            java.util.List<String> parts = splitForSpeech(text);
            if (parts.isEmpty()) {
                setMicState(MIC_IDLE);
                if (!isFinishing() && !listening && !userStopped) {
                    startListening();
                }
                return;
            }
            for (int i = 0; i < parts.size(); i++) {
                boolean last = (i == parts.size() - 1);
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH
                        : TextToSpeech.QUEUE_ADD;
                String id = last ? finalUtteranceId
                        : finalUtteranceId + "_part_" + i;
                tts.speak(parts.get(i), mode, null, id);
            }
        } catch (Exception ignored) {
            setMicState(MIC_IDLE);
        }
    }

    /** v3.7.34: speak an error out loud (never as text), without
     *  restarting listening — the caller decides what happens next. */
    private void speakError(String text) {
        if (!ttsReady || tts == null) return;
        try {
            setMicState(MIC_SPEAKING);
            java.util.List<String> parts = splitForSpeech(text);
            for (int i = 0; i < parts.size(); i++) {
                boolean last = (i == parts.size() - 1);
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH
                        : TextToSpeech.QUEUE_ADD;
                tts.speak(parts.get(i), mode, null,
                        last ? "assistant_error"
                                : "assistant_error_" + i);
            }
        } catch (Exception ignored) {
            setMicState(MIC_IDLE);
        }
    }

    /** Split an answer into short spoken sentences. */
    private java.util.List<String> splitForSpeech(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null) return out;
        String s = text.replace("\n", ". ")
                .replace("—", ", ")
                .replace("–", ", ")
                .replaceAll("\\s+-\\s+", ". ")
                .replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return out;
        String[] bits = s.split("(?<=[.!?])\\s+");
        for (String b : bits) {
            String t = b.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Voice in (SpeechRecognizer)
    // ------------------------------------------------------------------

    /**
     * v3.7.33: ask the recognizer which languages this phone
     * supports, so the pa-PK fallback is only tried when it can
     * actually work (not every phone has Punjabi recognition).
     */
    private void queryPaSupport() {
        try {
            Intent details = new Intent(
                    RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
            sendOrderedBroadcast(details, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context,
                        Intent intent) {
                    try {
                        Bundle extras = getResultExtras(true);
                        if (extras == null) return;
                        java.util.ArrayList<String> langs =
                                extras.getStringArrayList(
                                        RecognizerIntent
                                                .EXTRA_SUPPORTED_LANGUAGES);
                        if (langs != null) {
                            paSupported = langs.contains("pa-PK")
                                    || langs.contains("pa");
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, null, RESULT_OK, null, null);
        } catch (Exception ignored) {
            paSupported = false;
        }
    }

    private void onBigMicClicked() {
        if (listening) {
            // User deliberately stopped — do not auto-listen again
            // until they tap the mic.
            userStopped = true;
            stopListening();
            return;
        }
        userStopped = false;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // v3.7.34: voice-only — errors are SPOKEN, never shown.
            speakError(getString(R.string.assistant_mic_unavailable));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        listenFailures = 0;
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions,
                grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                listenFailures = 0;
                userStopped = false;
                startListening();
            } else {
                // v3.7.34: voice-only — errors are SPOKEN, never shown.
                speakError(getString(R.string.assistant_mic_denied));
            }
        }
    }

    private void startListening() {
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(
                        new AssistantRecognitionListener());
            }
            Intent intent = new Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            // v3.7.34: live-call feel — short silence timeouts so the
            // answer starts the moment speech ends, and partial
            // results on so the quiet-detection in onPartialResults
            // can end listening early instead of waiting for the
            // recognizer's own slow silence timeout.
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    700);
            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    500);
            lastPartial = "";
            lastPartialAt = 0;
            mainHandler.removeCallbacks(partialQuietCheck);
            listening = true;
            setMicState(MIC_LISTENING);
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            setMicState(MIC_IDLE);
            // v3.7.34: voice-only — errors are SPOKEN, never shown.
            speakError(getString(R.string.assistant_mic_failed));
        }
    }

    private void stopListening() {
        listening = false;
        setMicState(MIC_IDLE);
        resetMicScale();
        lastPartial = "";
        mainHandler.removeCallbacks(partialQuietCheck);
        try {
            if (recognizer != null) recognizer.stopListening();
        } catch (Exception ignored) {
        }
    }

    private void resetMicScale() {
        try {
            if (bigMic != null) {
                bigMic.setScaleX(1f);
                bigMic.setScaleY(1f);
            }
        } catch (Exception ignored) {
        }
    }

    private final class AssistantRecognitionListener
            implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {
            // v3.7.33: the big mic pulses with the voice — the
            // user sees the Assistant is hearing them.
            try {
                if (bigMic != null && listening) {
                    float s = 1f + Math.min(Math.max(rmsdB, 0f), 10f)
                            / 40f;
                    bigMic.setScaleX(s);
                    bigMic.setScaleY(s);
                }
            } catch (Exception ignored) {
            }
        }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        /**
         * v3.7.34: live-call feel — watch the partial transcript.
         * When it stops changing for ~700ms the user has finished
         * speaking, so end listening at once and let onResults fire
         * immediately instead of waiting for the recognizer's own
         * slow silence timeout. No text is ever shown.
         */
        @Override public void onPartialResults(Bundle partialResults) {
            try {
                ArrayList<String> partial =
                        partialResults.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()
                        && partial.get(0) != null) {
                    String p = partial.get(0).trim();
                    if (!p.isEmpty()) {
                        lastPartial = p;
                        lastPartialAt = System.currentTimeMillis();
                        mainHandler.removeCallbacks(partialQuietCheck);
                        mainHandler.postDelayed(partialQuietCheck, 700);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @Override public void onEvent(int eventType, Bundle params) {}

        @Override public void onError(int error) {
            listening = false;
            resetMicScale();
            setMicState(MIC_IDLE);
            mainHandler.removeCallbacks(partialQuietCheck);
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                // v3.7.33: nothing heard in ur-PK — try Punjabi
                // once, but only if this phone supports pa-PK.
                if ("ur-PK".equals(listenLang) && paSupported
                        && !triedPa) {
                    triedPa = true;
                    listenLang = "pa-PK";
                    startListening();
                    return;
                }
                // v3.7.34: not understood — say so by VOICE and
                // listen again once; never a Toast / notification.
                onNotUnderstood();
            } else if (error
                    == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                // v3.7.34: voice-only — errors are SPOKEN, never shown.
                speakError(getString(R.string.assistant_mic_denied));
            } else {
                // v3.7.34: voice-only — errors are SPOKEN, never shown.
                speakError(getString(R.string.assistant_mic_failed));
            }
        }

        @Override public void onResults(Bundle results) {
            listening = false;
            resetMicScale();
            setMicState(MIC_IDLE);
            mainHandler.removeCallbacks(partialQuietCheck);
            ArrayList<String> heard = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (heard != null && !heard.isEmpty()
                    && heard.get(0) != null
                    && !heard.get(0).trim().isEmpty()) {
                listenFailures = 0;
                // Next listen starts in ur-PK again; pa-PK is only
                // a per-hearing fallback.
                listenLang = "ur-PK";
                triedPa = false;
                sendQuestion(heard.get(0));
            } else {
                onNotUnderstood();
            }
        }

        /** v3.7.34: missed hearing — say so by VOICE and retry
         *  once, then ask the user to tap the mic and speak again.
         *  There is no typing fallback any more (voice-only
         *  Assistant). The retry prompt is spoken first and
         *  listening restarts only after it finishes, so the
         *  recognizer never hears the phone's own voice. */
        private void onNotUnderstood() {
            listenFailures++;
            if (listenFailures <= MAX_LISTEN_RETRIES) {
                listenLang = "ur-PK";
                triedPa = false;
                if (ttsReady && tts != null) {
                    listenAfterError = true;
                    speakError(getString(R.string.assistant_hear_retry));
                } else {
                    startListening();
                }
            } else {
                listenFailures = 0;
                listenLang = "ur-PK";
                triedPa = false;
                speakError(getString(R.string.assistant_hear_type));
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {
        }
        try {
            if (recognizer != null) recognizer.destroy();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
