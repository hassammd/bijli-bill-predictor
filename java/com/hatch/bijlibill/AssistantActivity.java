package com.hatch.bijlibill;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v3.7.31 — AI Assistant (v1, SIMPLE). A chat-style screen that
 * answers the user's questions using ONLY the locally saved bill
 * snapshots — no internet, no API key, no server, no cost.
 *
 * Voice in via Android SpeechRecognizer (ur-PK) + voice out via
 * TextToSpeech (ur-PK, falls back to the device default when Urdu
 * TTS data is missing). Typed questions work the same. Answers are
 * decided by {@link AssistantBrain} in simple Roman Urdu.
 *
 * Out of scope for v1 (later, gradually): photo/OCR, daily summary
 * notifications, challenges, comparisons, storytelling, anything
 * needing the internet — and NEVER any CAPTCHA auto-solve.
 */
public class AssistantActivity extends BaseActivity {

    private static final int REQ_MIC = 101;

    private LinearLayout messages;
    private ScrollView scrollView;
    private EditText input;
    private ImageButton micBtn;
    private Button voiceToggle;
    private boolean voiceOn = true;
    private boolean listening = false;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;

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

        // Voice on/off + hint row.
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
        root.addView(hintRow);

        // Input row: text field + mic + send.
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(mp, Ui.dp(this, 4), mp, Ui.dp(this, 12));
        input = new EditText(this);
        input.setHint(getString(R.string.assistant_input_hint));
        input.setTextSize(15);
        input.setTextColor(Ui.dark());
        input.setHintTextColor(Ui.grey());
        input.setBackgroundResource(R.drawable.textfield_bg);
        int ip = Ui.dp(this, 12);
        input.setPadding(ip, ip, ip, ip);
        input.setMinHeight(Ui.dp(this, 48));
        input.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inputRow.addView(input);

        micBtn = new ImageButton(this);
        micBtn.setImageResource(R.drawable.ic_mic);
        micBtn.setBackgroundColor(Color.TRANSPARENT);
        int ms = Ui.dp(this, 48);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ms, ms);
        mlp.leftMargin = Ui.dp(this, 4);
        micBtn.setLayoutParams(mlp);
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onMicClicked(); }
        });
        inputRow.addView(micBtn);

        Button sendBtn = new Button(this);
        sendBtn.setText(getString(R.string.assistant_send));
        sendBtn.setTextSize(14);
        sendBtn.setAllCaps(false);
        sendBtn.setBackgroundResource(R.drawable.btn_primary);
        sendBtn.setTextColor(Color.WHITE);
        sendBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sendBtn.setMinHeight(Ui.dp(this, 48));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(this, 4);
        sendBtn.setLayoutParams(slp);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendQuestion(input.getText().toString());
            }
        });
        inputRow.addView(sendBtn);
        root.addView(inputRow);

        // Banner (same as other pages).
        LinearLayout bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        bannerBox.setPadding(mp, Ui.dp(this, 8), mp, Ui.dp(this, 8));
        root.addView(bannerBox);
        AdsManager.loadBannerInto(this, bannerBox);

        setContentView(root);

        initTts();
        addBubble(AssistantBrain.greeting(snaps, meterLabel), false);
    }

    // ------------------------------------------------------------------
    // Q&A
    // ------------------------------------------------------------------

    private void sendQuestion(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        input.setText("");
        hideKeyboard();
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

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && input != null) {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
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
                                    || tts == null) return;
                            int r = tts.setLanguage(
                                    new Locale("ur", "PK"));
                            if (r == TextToSpeech.LANG_MISSING_DATA
                                    || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                // Roman Urdu reads acceptably with the
                                // device default voice instead.
                                tts.setLanguage(Locale.getDefault());
                            }
                            ttsReady = true;
                        }
                    });
        } catch (Exception ignored) {
            tts = null;
        }
    }

    private void speak(String text) {
        if (!voiceOn || !ttsReady || tts == null) return;
        try {
            String spoken = text.replace("\n", ". ");
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null,
                    "assistant_reply");
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Voice in (SpeechRecognizer)
    // ------------------------------------------------------------------

    private void onMicClicked() {
        if (listening) {
            stopListening();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, getString(
                    R.string.assistant_mic_unavailable),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
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
                startListening();
            } else {
                Toast.makeText(this, getString(
                        R.string.assistant_mic_denied),
                        Toast.LENGTH_SHORT).show();
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
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            listening = true;
            input.setHint(getString(R.string.assistant_listening));
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            input.setHint(getString(R.string.assistant_input_hint));
            Toast.makeText(this, getString(R.string.assistant_mic_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopListening() {
        listening = false;
        input.setHint(getString(R.string.assistant_input_hint));
        try {
            if (recognizer != null) recognizer.stopListening();
        } catch (Exception ignored) {
        }
    }

    private final class AssistantRecognitionListener
            implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle partialResults) {}

        @Override public void onEvent(int eventType, Bundle params) {}

        @Override public void onError(int error) {
            listening = false;
            input.setHint(getString(R.string.assistant_input_hint));
            if (error != SpeechRecognizer.ERROR_NO_MATCH
                    && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Toast.makeText(AssistantActivity.this, getString(
                        R.string.assistant_mic_failed),
                        Toast.LENGTH_SHORT).show();
            }
        }

        @Override public void onResults(Bundle results) {
            listening = false;
            input.setHint(getString(R.string.assistant_input_hint));
            ArrayList<String> heard = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (heard != null && !heard.isEmpty()
                    && heard.get(0) != null) {
                sendQuestion(heard.get(0));
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
