package com.hatch.bijlibill;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v3.7.33 — AI Assistant (v2, VOICE-ONLY). A voice-call-style screen
 * that answers the user's questions using ONLY the locally saved bill
 * snapshots — no internet, no API key, no server, no cost.
 *
 * There is NO text input any more: the user taps the big mic (or the
 * floating bubble on Home) and talks, the Assistant answers by voice
 * — a direct voice conversation, like a phone call. Suggestion chips
 * still exist, but tapping one gives a SPOKEN answer, never a typed
 * one. Roman Urdu AND Roman Punjabi are both understood; the answer
 * comes back in the user's own language.
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

    private LinearLayout messages;
    private ScrollView scrollView;
    /** Big round mic (voice-call style) + status line below it. */
    private ImageButton bigMic;
    private TextView statusText;
    private Button voiceToggle;
    private boolean voiceOn = true;
    private boolean listening = false;
    /** True after the user deliberately stops listening — the
     *  auto-listen-after-reply loop must not restart it then. */
    private boolean userStopped = false;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;

    // v3.7.32: bubble entry — auto greeting + auto listen.
    private boolean autoStart = false;
    private boolean pendingAutoListen = false;
    private int listenFailures = 0;

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

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        int mp = Ui.dp(this, 16);
        messages.setPadding(mp, Ui.dp(this, 12), mp, Ui.dp(this, 8));
        scrollView.addView(messages, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView);

        // Suggestion chips (horizontal scroll, never clipped).
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(mp, Ui.dp(this, 4), mp, Ui.dp(this, 4));
        for (final String s : AssistantBrain.suggestions(!snaps.isEmpty())) {
            TextView chip = Ui.chip(this, s, false, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { sendQuestion(s); }
            });
            chips.addView(chip);
        }
        chipScroll.addView(chips);
        root.addView(chipScroll);

        // v3.7.33: voice-call panel — NO text input anywhere.
        // Big round mic + status line; tapping the mic starts or
        // stops listening. The Assistant answers by voice only.
        LinearLayout voicePanel = new LinearLayout(this);
        voicePanel.setOrientation(LinearLayout.VERTICAL);
        voicePanel.setGravity(Gravity.CENTER_HORIZONTAL);
        voicePanel.setPadding(mp, Ui.dp(this, 6), mp, Ui.dp(this, 4));

        bigMic = new ImageButton(this);
        bigMic.setImageResource(R.drawable.ic_mic);
        bigMic.setColorFilter(Color.WHITE);
        bigMic.setContentDescription(
                getString(R.string.assistant_bubble_desc));
        GradientDrawable micBg = new GradientDrawable();
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
        micLp.gravity = Gravity.CENTER_HORIZONTAL;
        bigMic.setLayoutParams(micLp);
        bigMic.setElevation(Ui.dp(this, 4));
        bigMic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBigMicClicked(); }
        });
        voicePanel.addView(bigMic);

        statusText = new TextView(this);
        statusText.setText(getString(R.string.assistant_status_tap));
        statusText.setTextSize(14);
        statusText.setTextColor(Ds.muted());
        Ds.body(this, statusText, 400);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = Ui.dp(this, 8);
        statusText.setLayoutParams(stLp);
        voicePanel.addView(statusText);

        // Voice on/off row (spoken replies on or off).
        LinearLayout hintRow = new LinearLayout(this);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.setPadding(mp, Ui.dp(this, 2), mp, Ui.dp(this, 2));
        TextView hint = new TextView(this);
        hint.setText(getString(R.string.assistant_hint));
        hint.setTextSize(12);
        hint.setTextColor(Ds.muted());
        Ds.body(this, hint, 400);
        hint.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hintRow.addView(hint);
        voiceToggle = new Button(this);
        voiceToggle.setText(getString(R.string.assistant_voice_on));
        voiceToggle.setTextSize(12);
        voiceToggle.setAllCaps(false);
        voiceToggle.setBackgroundColor(Color.TRANSPARENT);
        voiceToggle.setTextColor(Ui.teal());
        voiceToggle.setMinHeight(Ui.dp(this, 40));
        voiceToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                voiceOn = !voiceOn;
                voiceToggle.setText(getString(voiceOn
                        ? R.string.assistant_voice_on
                        : R.string.assistant_voice_off));
                if (!voiceOn && tts != null) tts.stop();
            }
        });
        hintRow.addView(voiceToggle);
        voicePanel.addView(hintRow);
        root.addView(voicePanel);

        // Banner (same as other pages).
        LinearLayout bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        bannerBox.setPadding(mp, Ui.dp(this, 8), mp, Ui.dp(this, 8));
        root.addView(bannerBox);
        AdsManager.loadBannerInto(this, bannerBox);

        setContentView(root);

        queryPaSupport();
        initTts();
        addBubble(AssistantBrain.greeting(snaps, meterLabel), false);

        // v3.7.32: bubble entry — greet by voice, then listen at once.
        autoStart = getIntent().getBooleanExtra(EXTRA_AUTO_LISTEN, false);
        if (autoStart) {
            pendingAutoListen = true;
            tryAutoStart();
        }
    }

    // ------------------------------------------------------------------
    // Q&A
    // ------------------------------------------------------------------

    private void sendQuestion(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        // The user bubble is the transcript of what was asked (by
        // voice, or a tapped chip) — the answer is always SPOKEN.
        addBubble(q, true);
        String ans = AssistantBrain.answer(q, snaps, meterLabel);
        addBubble(ans, false);
        speak(ans);
    }

    private void addBubble(String text, boolean user) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setLineSpacing(Ui.dp(this, 2), 1.35f);
        int p = Ui.dp(this, 12);
        b.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        if (user) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Ui.teal());
            bg.setCornerRadius(Ui.dp(this, 14));
            b.setBackground(bg);
            b.setTextColor(Color.WHITE);
            lp.gravity = Gravity.END;
            lp.leftMargin = Ui.dp(this, 48);
        } else {
            b.setBackgroundResource(R.drawable.card_bg);
            b.setTextColor(Ui.dark());
            Ds.body(this, b, 400);
            lp.gravity = Gravity.START;
            lp.rightMargin = Ui.dp(this, 48);
        }
        b.setLayoutParams(lp);
        messages.addView(b);
        scrollView.post(new Runnable() {
            @Override public void run() {
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private String labelFor(BillSnapshots.Snapshot s) {
        String name = s.holderName == null || s.holderName.trim().isEmpty()
                ? SavedMeters.maskId(s.ref) : s.holderName.trim();
        return name + " (" + Disco.fromCode(s.disco).displayName + ", "
                + SavedMeters.maskId(s.ref) + ")";
    }

    private void setStatus(int resId) {
        if (statusText != null) statusText.setText(getString(resId));
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
                                // No voice out — still listen if the
                                // bubble asked for it.
                                if (pendingAutoListen) {
                                    pendingAutoListen = false;
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
                            ttsReady = true;
                            tryAutoStart();
                        }
                    });
            // v3.7.32: when the spoken greeting ends (or fails), start
            // listening — the user never taps the mic after the bubble.
            // v3.7.33: when a spoken REPLY ends, listen again at once —
            // a direct voice conversation, like a phone call.
            tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onDone(String id) {
                            if ("assistant_greeting".equals(id)) {
                                listenAfterGreeting();
                            } else if ("assistant_reply".equals(id)) {
                                listenAfterReply();
                            }
                        }
                        @Override public void onError(String id) {
                            if ("assistant_greeting".equals(id)) {
                                listenAfterGreeting();
                            } else if ("assistant_reply".equals(id)) {
                                listenAfterReply();
                            }
                        }
                    });
        } catch (Exception ignored) {
            tts = null;
            if (pendingAutoListen) {
                pendingAutoListen = false;
                startListening();
            }
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
                setStatus(R.string.assistant_status_tap);
                if (!isFinishing() && !listening && !userStopped) {
                    startListening();
                }
            }
        });
    }

    /** v3.7.32: bubble entry — speak the short greeting, then listen. */
    private void tryAutoStart() {
        if (!pendingAutoListen || isFinishing()) return;
        if (!voiceOn) {
            // Voice replies off: skip the greeting, just listen.
            pendingAutoListen = false;
            startListening();
            return;
        }
        if (ttsReady && tts != null) {
            pendingAutoListen = false;
            speakGreeting();
        }
        // Else TTS is still initialising — onInit calls tryAutoStart().
    }

    private void speakGreeting() {
        if (!voiceOn || !ttsReady || tts == null) {
            startListening();
            return;
        }
        try {
            tts.speak(getString(R.string.assistant_voice_greeting),
                    TextToSpeech.QUEUE_FLUSH, null, "assistant_greeting");
        } catch (Exception ignored) {
            startListening();
        }
    }

    private void speak(String text) {
        if (!voiceOn) return;
        if (!ttsReady || tts == null) {
            // No voice out right now — keep the conversation going
            // by listening again instead of stalling.
            if (!isFinishing() && !listening && !userStopped) {
                startListening();
            }
            return;
        }
        setStatus(R.string.assistant_status_speaking);
        try {
            String spoken = text.replace("\n", ". ");
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null,
                    "assistant_reply");
        } catch (Exception ignored) {
            setStatus(R.string.assistant_status_tap);
        }
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
            // v3.7.32: in-chat, never a Toast / notification.
            addBubble(getString(R.string.assistant_mic_unavailable), false);
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
                // v3.7.32: in-chat, never a Toast / notification.
                addBubble(getString(R.string.assistant_mic_denied), false);
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
            listening = true;
            setStatus(R.string.assistant_status_listening);
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            setStatus(R.string.assistant_status_tap);
            // v3.7.32: in-chat, never a Toast / notification.
            addBubble(getString(R.string.assistant_mic_failed), false);
        }
    }

    private void stopListening() {
        listening = false;
        setStatus(R.string.assistant_status_tap);
        resetMicScale();
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
        @Override public void onPartialResults(Bundle partialResults) {}

        @Override public void onEvent(int eventType, Bundle params) {}

        @Override public void onError(int error) {
            listening = false;
            resetMicScale();
            setStatus(R.string.assistant_status_tap);
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
                // v3.7.32: not understood — say so in-chat and listen
                // again once; never a Toast / notification.
                onNotUnderstood();
            } else if (error
                    == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                addBubble(getString(R.string.assistant_mic_denied), false);
            } else {
                addBubble(getString(R.string.assistant_mic_failed), false);
            }
        }

        @Override public void onResults(Bundle results) {
            listening = false;
            resetMicScale();
            setStatus(R.string.assistant_status_tap);
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

        /** v3.7.33: missed hearing — retry once, then ask the user
         *  to tap the mic and speak again. There is no typing
         *  fallback any more (voice-only Assistant). */
        private void onNotUnderstood() {
            listenFailures++;
            if (listenFailures <= MAX_LISTEN_RETRIES) {
                addBubble(getString(R.string.assistant_hear_retry), false);
                listenLang = "ur-PK";
                triedPa = false;
                startListening();
            } else {
                listenFailures = 0;
                listenLang = "ur-PK";
                triedPa = false;
                addBubble(getString(R.string.assistant_hear_type), false);
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
