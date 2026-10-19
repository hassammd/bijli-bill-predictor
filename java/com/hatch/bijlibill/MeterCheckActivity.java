package com.hatch.bijlibill;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hatch.bijlibill.LescoBillFetcher.BillInfo;
import com.hatch.bijlibill.LescoBillFetcher.BillPage;
import com.hatch.bijlibill.MeterCheckCalc.Result;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * v2.1.0 UNIT CHECK (یونٹ چیک), v2.2.0 hardened.
 *
 * The user's problem: nobody knows their mid-month consumption, and
 * everyone fears crossing into a higher slab — especially the 200-unit
 * Protected -> Non-Protected cliff. Flow:
 *
 *   1. CAPTURE — up to 60s video of the meter (ACTION_VIDEO_CAPTURE),
 *      or pick a previously recorded video from the gallery
 *      (ACTION_GET_CONTENT "video/*") — no internet needed at the
 *      meter. Both flow into the SAME processVideo() pipeline.
 *   2. AUTO FRAME PICK (v2.3.0) — the app picks the sharpest frame
 *      ITSELF via FramePrep.sharpness + KwhAnchorPicker.bestFrameIndex.
 *      No manual frame selection: a layman can't be expected to do it.
 *      The confirm screen opens directly with a "Reading parhi ja rahi
 *      hai…" progress state while OCR runs.
 *   3. OCR     — ML Kit thin client reads text ELEMENTS (with boxes)
 *      off the frames in the background; KwhAnchorPicker finds the
 *      digit run nearest a "kWh" token (digits usually LEFT of kWh,
 *      same line) — the app detects the billing reading itself.
 *      Falls back to most-frequent run; any OCR failure degrades
 *      silently to manual entry.
 *   4. CONFIRM — MANDATORY: the suggestion is shown BIG but never
 *      auto-accepted; the user confirms or edits the reading.
 *      v2.4.0: the manual field accepts DECIMALS ("352.32", also
 *      "352,32") — all calc math is double, trimmed for display.
 *   5. RESULT  — units so far, ANDAZA bill (bill's real tax ratios),
 *                "Andaza bill kaise bana?" breakdown in plain words,
 *                slab alerts, month-end projection.
 *
 * The parsed bill (previous reading + category + tax fields) is
 * re-parsed from the raw-HTML cache — the cache schema is untouched.
 * Every figure on the result card is labeled ANDAZA / تخمینہ.
 */
public class MeterCheckActivity extends BaseActivity {

    public static final String EXTRA_REF = "ref";
    public static final String EXTRA_MODE = "mode"; // "ref" or "cid"
    public static final String EXTRA_DISCO = "disco"; // v2.5.0
    public static final String EXTRA_FROM_BILL = "from_bill";

    private static final int REQ_VIDEO = 41;
    private static final int REQ_CAMERA = 42;
    private static final int REQ_GALLERY = 43;
    /** v2.2.0: meter cycles are slow — allow up to a minute of video. */
    private static final int MAX_VIDEO_SECONDS = 60;
    /** v2.2.0: adaptive — about one frame per second, capped here. */
    private static final int MAX_FRAMES = 12;

    private String ref;
    private String mode;
    private Disco disco = Disco.LESCO; // v2.5.0
    private boolean fromBill;
    private BillInfo bill;
    /**
     * v2.2.1: the calculation base = bill's PRESENT reading
     * (bill.currReading), falling back to the previous reading only
     * when present is missing/zero. Units are counted FROM here
     * (bill ke baad se) so already-billed units aren't double-counted.
     */
    private String billBaseReading;
    /**
     * v3.1.1: manual entry is reachable both with a bill (from
     * showCapture) and without one (from showNoBill) — this tracks
     * where the back link should return to.
     */
    private boolean manualFromNoBill;
    private int noBillMsgRes;

    private LinearLayout content;
    private LinearLayout bannerBox;

    private Uri videoUri;
    private final List<Bitmap> frames = new ArrayList<Bitmap>();
    private int selectedFrame;
    private KwhAnchorPicker.Picked ocrPicked;
    private boolean ocrDone;
    private boolean ocrSupported;

    // Confirm-step live views (updated when background OCR finishes).
    private TextView suggestView;
    private TextView kwhNoteView;
    private View ocrTipsCard;
    private EditText readingField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ref = getIntent().getStringExtra(EXTRA_REF);
        if (ref == null) ref = "";
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (!"cid".equals(mode)) mode = "ref";
        // v2.5.0: which company's cached bill to re-parse.
        disco = Disco.fromCode(getIntent().getStringExtra(EXTRA_DISCO));
        fromBill = getIntent().getBooleanExtra(EXTRA_FROM_BILL, false);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.pageBg());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(Ds.appBar(this,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { finish(); }
                },
                getString(R.string.meter_check_title),
                getString(R.string.meter_check_sub), false));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content = Ui.col(this);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        bannerBox = Ui.col(this);
        bannerBox.setPadding(Ui.dp(this, 16), Ui.dp(this, 8),
                Ui.dp(this, 16), Ui.dp(this, 16));
        root.addView(bannerBox);

        // v3.6.4: bottom nav on every tab (tester feedback #3).
        root.addView(Ds.bottomNav(this, Ds.TAB_UNIT));
        setContentView(root);
        ocrSupported = MeterOcr.isAvailable();
        loadBillData();
    }

    @Override
    protected void onDestroy() {
        clearFrames();
        super.onDestroy();
    }

    private void clearFrames() {
        for (Bitmap b : frames) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        frames.clear();
    }

    // ------------------------------------------------------------------
    // Bill data (re-parsed from the raw-HTML cache — schema untouched)
    // ------------------------------------------------------------------

    private void loadBillData() {
        // v2.5.0 cache key: disco:mode:ref.
        BillCache.CachedPage cached =
                BillCache.getHtml(this, disco.code + ":" + mode + ":" + ref);
        if (cached == null) {
            showNoBill(R.string.meter_no_bill);
            return;
        }
        try {
            BillPage p = LescoBillFetcher.pageFromHtml(
                    cached.html, ref, cached.fetchPath);
            // v2.2.1: base = bill's PRESENT reading (latest billed
            // reading); previous only as fallback.
            String base = p.parseOk && p.info != null
                    ? MeterCheckCalc.resolveBase(p.info.currReading,
                            p.info.prevReading)
                    : null;
            if (base != null && !base.trim().isEmpty()
                    && MeterCheckCalc.parseReading(base) >= 0) {
                bill = p.info;
                billBaseReading = base;
                showCapture();
            } else {
                showNoBill(R.string.meter_need_prev);
            }
        } catch (Exception e) {
            showNoBill(R.string.meter_no_bill);
        }
    }

    /** No usable cached bill — honest prompt + manual-entry path. */
    private void showNoBill(int msgRes) {
        noBillMsgRes = msgRes;
        content.removeAllViews();
        LinearLayout card = Ds.card(this);
        TextView t = new TextView(this);
        t.setText(getString(msgRes));
        t.setTextSize(15);
        t.setTextColor(Ds.red());
        Ds.body(this, t, 500);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        card.addView(Ui.spacer(this, 12));
        // v3.1.1: no bill, but the user can still type BOTH readings
        // manually — the same estimate pipeline, no video needed.
        Button manual = Ds.outlineBtn(this,
                getString(R.string.meter_manual_btn),
                R.drawable.ic_edit,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        manualFromNoBill = true;
                        showManualEntry();
                    }
                });
        card.addView(manual);
        card.addView(Ui.spacer(this, 12));
        card.addView(Ds.backLink(this,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { finish(); }
                }));
        content.addView(Ui.padded(this, card));
    }

    /** 3-step indicator for the Unit Check flow (design: .steps). */
    private View stepsView(int[] states, int[] labelRes) {
        String[] labels = new String[labelRes.length];
        for (int i = 0; i < labelRes.length; i++) {
            labels[i] = getString(labelRes[i]);
        }
        LinearLayout steps = Ds.steps(this, labels, states);
        // v3.6.35: make completed steps tappable to go back.
        // Children: [col0, line, col1, line, col2...] — cols at even idx.
        for (int i = 0; i < labels.length; i++) {
            final int stepIdx = i;
            int childIdx = i * 2;
            if (childIdx < steps.getChildCount()
                    && states[i] == 1) {
                View col = steps.getChildAt(childIdx);
                col.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onStepTapped(stepIdx);
                    }
                });
            }
        }
        LinearLayout wrap = Ui.col(this);
        wrap.setPadding(Ui.dp(this, 16), Ui.dp(this, 18),
                Ui.dp(this, 16), Ui.dp(this, 4));
        wrap.addView(steps);
        return wrap;
    }

    /**
     * v3.6.35: navigate when a completed step is tapped.
     * Step 0 = bill/capture, Step 1 = record/manual entry.
     */
    private void onStepTapped(int stepIdx) {
        if (stepIdx == 0) {
            showCapture();
        } else if (stepIdx == 1) {
            showManualEntry();
        }
    }

    // ------------------------------------------------------------------
    // Step 1: capture (design: Unit Check screen)
    // ------------------------------------------------------------------

    private void showCapture() {
        content.removeAllViews();
        content.addView(stepsView(new int[]{1, 2, 0}, new int[]{
                R.string.step_bill_loaded, R.string.step_record,
                R.string.step_estimate}));

        // Main instruction card: illustration + title + text + pill.
        LinearLayout card = Ds.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int cp = Ui.dp(this, 22);
        card.setPadding(cp, Ui.dp(this, 26), cp, Ui.dp(this, 26));

        android.widget.FrameLayout illus =
                Ds.illus(this, R.drawable.ic_video, Ds.deepGreen());
        LinearLayout.LayoutParams ilp =
                (LinearLayout.LayoutParams) illus.getLayoutParams();
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        illus.setLayoutParams(ilp);
        card.addView(illus);
        card.addView(Ui.spacer(this, 14));

        TextView title = new TextView(this);
        title.setText(getString(R.string.meter_step1_title));
        title.setTextSize(17);
        title.setTextColor(Ds.ink());
        Ds.heading(this, title);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        card.addView(Ui.spacer(this, 8));

        // Instruction paragraph with the "60 second" figure bold + ink
        // (design), both locales.
        TextView t = new TextView(this);
        String raw = getString(R.string.meter_step1_text);
        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder(raw);
        String fig = LocaleHelper.isUrdu(this)
                ? "60 سیکنڈ" : "60 second";
        int fi = raw.indexOf(fig);
        if (fi >= 0) {
            sb.setSpan(new android.text.style.StyleSpan(
                    android.graphics.Typeface.BOLD), fi,
                    fi + fig.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(
                    Ds.ink()), fi, fi + fig.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        t.setText(sb);
        t.setTextSize(13.5f);
        t.setTextColor(Ds.muted());
        t.setLineSpacing(0, 1.7f);
        Ds.body(this, t, 400);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        card.addView(Ui.spacer(this, 12));

        // v2.2.1: the bill's PRESENT reading — the base that units
        // are counted FROM (bill ke baad se). Monospace (design).
        String full = getString(R.string.meter_bill_prev,
                billBaseReading);
        String prefix = full;
        int vi = full.indexOf(billBaseReading);
        if (vi > 0) prefix = full.substring(0, vi).trim();
        TextView pill = Ds.greenPillMono(this, prefix, billBaseReading);
        LinearLayout pillWrap = new LinearLayout(this);
        pillWrap.setOrientation(LinearLayout.HORIZONTAL);
        pillWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        pillWrap.addView(pill);
        card.addView(pillWrap);
        content.addView(Ui.padded(this, card));

        // Actions (design: buttons live outside the card).
        LinearLayout actions = Ui.col(this);
        // v3.6.61: 10dp top to match button-to-button spacing (was 2dp).
        actions.setPadding(Ui.dp(this, 20), Ui.dp(this, 10),
                Ui.dp(this, 20), 0);
        Button capture = Ds.greenBtn(this,
                getString(R.string.meter_capture_btn),
                R.drawable.ic_video,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onCaptureClicked();
                    }
                });
        actions.addView(capture);
        actions.addView(Ui.spacer(this, 10));
        // v2.2.0: pick a previously recorded video (no internet needed
        // at the meter — record there, upload at home).
        Button gallery = Ds.outlineBtn(this,
                getString(R.string.meter_gallery_btn),
                R.drawable.ic_folder,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onGalleryClicked();
                    }
                });
        actions.addView(gallery);
        actions.addView(Ui.spacer(this, 10));
        // v3.1.1: manual reading entry — no video needed. Flows into
        // the SAME estimate pipeline as the OCR route.
        Button manual = Ds.outlineBtn(this,
                getString(R.string.meter_manual_btn),
                R.drawable.ic_edit,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        manualFromNoBill = false;
                        showManualEntry();
                    }
                });
        actions.addView(manual);
        content.addView(actions);

        // v3.6.40: gallery footnote removed (user: unnecessary line).
        AdsManager.loadBannerInto(this, bannerBox);
    }

    // ------------------------------------------------------------------
    // v3.1.1: manual reading entry — the no-video route. Same record
    // step family as showCapture; the typed reading flows into the
    // SAME estimate pipeline (mirror calc when a bill is loaded,
    // legacy otherwise). Previous-reading field appears ONLY when no
    // bill base exists (bill loaded => base = bill's present reading,
    // exactly like the OCR flow).
    // ------------------------------------------------------------------

    private void showManualEntry() {
        content.removeAllViews();
        // Step labels adapt: with a bill the flow is Bill -> Record ->
        // Estimate; without one it is just Record -> Estimate (there
        // is no "bill loaded" step to claim).
        boolean hasBill = billBaseReading != null
                && !billBaseReading.trim().isEmpty();
        if (hasBill) {
            content.addView(stepsView(new int[]{1, 2, 0}, new int[]{
                    R.string.step_bill_loaded, R.string.step_record,
                    R.string.step_estimate}));
        } else {
            content.addView(stepsView(new int[]{1, 0}, new int[]{
                    R.string.step_record, R.string.step_estimate}));
        }

        LinearLayout card = Ds.card(this);
        int cp = Ui.dp(this, 22);
        card.setPadding(cp, Ui.dp(this, 26), cp, Ui.dp(this, 26));

        TextView title = new TextView(this);
        title.setText(getString(R.string.meter_manual_title));
        title.setTextSize(17);
        title.setTextColor(Ds.ink());
        Ds.heading(this, title);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        card.addView(Ui.spacer(this, 8));

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.meter_manual_sub));
        sub.setTextSize(13);
        sub.setTextColor(Ds.muted());
        sub.setLineSpacing(0, 1.65f);
        Ds.body(this, sub, 400);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);
        card.addView(Ui.spacer(this, 16));

        TextView lab = new TextView(this);
        lab.setText(getString(R.string.meter_reading_label));
        lab.setTextSize(12);
        lab.setTextColor(Ds.muted());
        Ds.heading(this, lab);
        card.addView(lab);
        card.addView(Ui.spacer(this, 6));

        final android.widget.EditText currField =
                Ui.numberField(this, R.string.meter_reading_label);
        currField.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        currField.setTextSize(22);
        Ds.mono(currField);
        card.addView(currField);
        card.addView(Ui.spacer(this, 4));

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.meter_reading_hint));
        hint.setTextSize(12);
        hint.setTextColor(Ds.muted());
        Ds.body(this, hint, 400);
        card.addView(hint);

        // Previous reading: only when no bill base exists.
        final android.widget.EditText prevField;
        if (!hasBill) {
            card.addView(Ui.spacer(this, 14));
            TextView plab = new TextView(this);
            plab.setText(getString(R.string.meter_manual_prev_label));
            plab.setTextSize(12);
            plab.setTextColor(Ds.muted());
            Ds.heading(this, plab);
            card.addView(plab);
            card.addView(Ui.spacer(this, 6));
            prevField = Ui.numberField(this,
                    R.string.meter_manual_prev_label);
            prevField.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            prevField.setTextSize(22);
            Ds.mono(prevField);
            card.addView(prevField);
            card.addView(Ui.spacer(this, 4));
            TextView phint = new TextView(this);
            phint.setText(getString(R.string.meter_manual_prev_hint));
            phint.setTextSize(12);
            phint.setTextColor(Ds.muted());
            Ds.body(this, phint, 400);
            card.addView(phint);
        } else {
            prevField = null;
            card.addView(Ui.spacer(this, 2));
            String full = getString(R.string.meter_bill_prev,
                    billBaseReading);
            String prefix = full;
            int vi = full.indexOf(billBaseReading);
            if (vi > 0) prefix = full.substring(0, vi).trim();
            TextView pill = Ds.greenPillMono(this, prefix,
                    billBaseReading);
            LinearLayout pillWrap = new LinearLayout(this);
            pillWrap.setOrientation(LinearLayout.HORIZONTAL);
            pillWrap.setGravity(Gravity.CENTER_HORIZONTAL);
            pillWrap.addView(pill);
            card.addView(pillWrap);
        }
        content.addView(Ui.padded(this, card));

        LinearLayout actions = Ui.col(this);
        actions.setPadding(Ui.dp(this, 20), Ui.dp(this, 2),
                Ui.dp(this, 20), 0);
        Button calc = Ds.greenBtn(this,
                getString(R.string.meter_calc_btn), 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onManualCalculate(currField, prevField);
                    }
                });
        actions.addView(calc);
        content.addView(actions);

        LinearLayout backWrap = Ui.col(this);
        backWrap.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20),
                Ui.dp(this, 8));
        backWrap.addView(Ui.spacer(this, 10));
        backWrap.addView(Ds.backLink(this,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // Return to where manual entry was launched
                        // from: the no-bill prompt or the capture step.
                        if (manualFromNoBill) {
                            showNoBill(noBillMsgRes);
                        } else {
                            showCapture();
                        }
                    }
                }));
        content.addView(backWrap);
        AdsManager.loadBannerInto(this, bannerBox);
    }

    /** Manual route into the SAME pipeline as onCalculate(). */
    private void onManualCalculate(android.widget.EditText currField,
            android.widget.EditText prevField) {
        String typed = currField.getText().toString().trim();
        String base = (billBaseReading != null
                && !billBaseReading.trim().isEmpty())
                ? billBaseReading
                : (prevField == null ? ""
                        : prevField.getText().toString().trim());
        int day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
        MeterCheckCalc.Result r = MeterCheckCalc.compute(
                base, typed,
                bill == null ? null : bill.category,
                bill == null ? null : bill.taxes,
                bill == null ? null : bill.gst,
                bill == null ? null : bill.fpa,
                bill == null ? null : bill.electricityCharges,
                day);
        if (r.error != null) {
            Toast.makeText(this, r.error, Toast.LENGTH_LONG).show();
            return;
        }
        showResult(r, typed);
    }

    private void onCaptureClicked() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        launchCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAMERA) {
            // The system camera app performs the capture either way;
            // the permission only covers direct camera use.
            if (grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.meter_camera_denied,
                        Toast.LENGTH_LONG).show();
            }
            launchCapture();
        }
    }

    private void launchCapture() {
        try {
            Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            // v2.2.0: meter display cycles are slow — allow up to 60s.
            i.putExtra(MediaStore.EXTRA_DURATION_LIMIT,
                    MAX_VIDEO_SECONDS);
            i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            startActivityForResult(i, REQ_VIDEO);
        } catch (Exception e) {
            Toast.makeText(this, R.string.meter_no_camera_app,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** v2.2.0: pick a video recorded earlier (gallery / file manager). */
    private void onGalleryClicked() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("video/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i,
                    getString(R.string.meter_gallery_btn)), REQ_GALLERY);
        } catch (Exception e) {
            Toast.makeText(this, R.string.meter_no_camera_app,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Camera capture AND gallery pick flow into the SAME pipeline.
        if (requestCode == REQ_VIDEO || requestCode == REQ_GALLERY) {
            if (resultCode != RESULT_OK || data == null
                    || data.getData() == null) {
                Toast.makeText(this, R.string.meter_video_cancelled,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            videoUri = data.getData();
            processVideo(videoUri);
        }
    }

    // ------------------------------------------------------------------
    // Step 2: frame picker (+ background OCR)
    // ------------------------------------------------------------------

    /**
     * v2.2.0: the ONE pipeline for meter videos — camera capture and
     * gallery picks both land here (see onActivityResult).
     */
    private void processVideo(final Uri uri) {
        content.removeAllViews();
        content.addView(stepsView(new int[]{1, 2, 0}, new int[]{
                R.string.step_bill_loaded, R.string.step_record,
                R.string.step_estimate}));
        LinearLayout card = Ds.card(this);
        TextView t = new TextView(this);
        t.setText(getString(R.string.meter_extracting));
        t.setTextSize(14);
        t.setTextColor(Ds.muted());
        Ds.body(this, t, 500);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 32), 0, Ui.dp(this, 32));
        card.addView(t);
        content.addView(Ui.padded(this, card));

        new Thread(new Runnable() {
            @Override public void run() {
                // v2.2.0 guard: reject over-long / over-large videos
                // BEFORE any frame extraction (gallery clips can be
                // hours long).
                final KwhAnchorPicker.VideoCheck verdict =
                        checkVideoLimits(uri);
                final List<Bitmap> got =
                        verdict == KwhAnchorPicker.VideoCheck.OK
                                ? grabFrames(uri)
                                : new ArrayList<Bitmap>();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (verdict != KwhAnchorPicker.VideoCheck.OK) {
                            Toast.makeText(MeterCheckActivity.this,
                                    verdict == KwhAnchorPicker.VideoCheck.TOO_LONG
                                            ? R.string.meter_video_too_long
                                            : R.string.meter_video_too_big,
                                    Toast.LENGTH_LONG).show();
                            showCapture();
                            return;
                        }
                        clearFrames();
                        frames.addAll(got);
                        if (frames.isEmpty()) {
                            Toast.makeText(MeterCheckActivity.this,
                                    R.string.meter_video_cancelled,
                                    Toast.LENGTH_LONG).show();
                            showCapture();
                            return;
                        }
                        // v2.3.0: the app picks the sharpest frame ITSELF —
                        // no manual frame selection in the flow.
                        selectedFrame = autoPickFrame();
                        showConfirm();
                        startOcr();
                    }
                });
            }
        }).start();
    }

    /**
     * v2.2.0: reads duration + file size for the guard in
     * {@link KwhAnchorPicker#checkVideo}. Unknowns (-1) mean "can't
     * verify" and are allowed through. Never throws.
     */
    private KwhAnchorPicker.VideoCheck checkVideoLimits(Uri uri) {
        long durMs = -1;
        long sizeBytes = -1;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            try {
                String d = r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durMs = Long.parseLong(d);
            } catch (Exception ignored) { }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{
                            android.provider.OpenableColumns.SIZE},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(
                        android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    sizeBytes = c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) { }
            }
        }
        return KwhAnchorPicker.checkVideo(durMs, sizeBytes);
    }

    /**
     * v2.2.0: adaptive frames — about one per second of video, capped
     * at MAX_FRAMES, evenly spread across the real duration.
     */
    private List<Bitmap> grabFrames(Uri uri) {
        List<Bitmap> out = new ArrayList<Bitmap>();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            long durMs = MAX_VIDEO_SECONDS * 1000L;
            try {
                String d = r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durMs = Math.max(1000L, Long.parseLong(d));
            } catch (Exception ignored) { }
            List<Long> times = KwhAnchorPicker.frameTimestampsUs(
                    durMs, MAX_FRAMES);
            for (long timeUs : times) {
                try {
                    Bitmap b = r.getFrameAtTime(timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b != null) {
                        int w = 320;
                        int h = Math.max(1,
                                (int) (b.getHeight() * (w / (float) b.getWidth())));
                        out.add(Bitmap.createScaledBitmap(b, w, h, true));
                        if (b != out.get(out.size() - 1)) b.recycle();
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * v2.3.0: auto frame pick — scores every extracted frame with
     * FramePrep.sharpness (Laplacian variance) and returns the sharpest
     * index via KwhAnchorPicker.bestFrameIndex. The user never picks.
     */
    private int autoPickFrame() {
        double[] scores = new double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            Bitmap bm = frames.get(i);
            double s = 0;
            try {
                int w = bm.getWidth();
                int h = bm.getHeight();
                int[] px = new int[w * h];
                bm.getPixels(px, 0, w, 0, 0, w, h);
                s = FramePrep.sharpness(px, w, h);
            } catch (Throwable ignored) {
            }
            scores[i] = s;
        }
        int best = KwhAnchorPicker.bestFrameIndex(scores);
        return best >= 0 ? best : 0;
    }

    /**
     * v2.2.1: background STRONG OCR — sharpness-ranks the frames and
     * runs the bounded multi-variant job plan (original / enhanced /
     * inverted / center-crop); every pass feeds the kWh-anchored
     * picker so a kWh reading found in ANY pass wins. Falls back to
     * most-frequent run when no kWh token is seen anywhere.
     */
    private void startOcr() {
        ocrDone = false;
        ocrPicked = null;
        if (!ocrSupported || frames.isEmpty()) {
            ocrDone = true;
            return;
        }
        final List<Bitmap> work = new ArrayList<Bitmap>(frames);
        // v2.2.1: on low-RAM devices only the cheap ORIGINAL passes run.
        boolean lowRam = false;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(
                            ACTIVITY_SERVICE);
            lowRam = am != null && am.isLowRamDevice();
        } catch (Throwable ignored) { }
        final boolean lowRamF = lowRam;
        new Thread(new Runnable() {
            @Override public void run() {
                List<List<KwhAnchorPicker.OcrElement>> perPass =
                        MeterOcr.recognizeStrong(work, lowRamF);
                final KwhAnchorPicker.Picked picked =
                        KwhAnchorPicker.pick(perPass);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        ocrDone = true;
                        ocrPicked = picked;
                        onOcrFinished();
                    }
                });
            }
        }).start();
    }

    /** Pushes the OCR suggestion into the confirm screen if visible. */
    private void onOcrFinished() {
        if (suggestView == null || readingField == null) return;
        if (ocrPicked != null && ocrPicked.rawValue != null
                && !ocrPicked.rawValue.isEmpty()) {
            suggestView.setText(getString(R.string.meter_suggested)
                    + " " + ocrPicked.rawValue);
            if (readingField.getText().toString().trim().isEmpty()) {
                readingField.setText(ocrPicked.billingValue);
            }
            // v2.2.0: tell the user the app found the kWh reading itself.
            if (ocrPicked.kwhAnchored && kwhNoteView != null) {
                kwhNoteView.setVisibility(View.VISIBLE);
            }
            if (ocrTipsCard != null) {
                ocrTipsCard.setVisibility(View.GONE);
            }
        } else {
            suggestView.setText(getString(R.string.meter_ocr_none));
            // v2.2.1: specific guidance when OCR found nothing — the
            // human eye can read what OCR missed (BIG manual entry).
            if (ocrTipsCard != null) {
                ocrTipsCard.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * v2.2.1: tap-to-zoom — shows the frame large in a dialog so the
     * user can read the digits with their own eyes when OCR failed.
     */
    private void showZoomDialog(Bitmap bm) {
        if (bm == null || bm.isRecycled()) return;
        try {
            android.app.AlertDialog.Builder b =
                    new android.app.AlertDialog.Builder(this);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Ui.dp(this, 8);
            iv.setPadding(pad, pad, pad, pad);
            b.setView(iv);
            b.setPositiveButton(android.R.string.ok, null);
            b.show();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------
    // Step 3: confirm (MANDATORY — OCR is never auto-accepted).
    // Design: Unit Check "Analyze" step.
    // ------------------------------------------------------------------

    private void showConfirm() {
        content.removeAllViews();
        suggestView = null;
        kwhNoteView = null;
        ocrTipsCard = null;
        readingField = null;

        content.addView(stepsView(new int[]{1, 2, 0}, new int[]{
                R.string.step_record, R.string.step_analyze,
                R.string.step_estimate}));

        LinearLayout card = Ds.card(this);
        TextView title = new TextView(this);
        title.setText(getString(R.string.meter_confirm_title));
        title.setTextSize(17);
        title.setTextColor(Ds.ink());
        Ds.heading(this, title);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        card.addView(Ui.spacer(this, 10));

        final ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setImageBitmap(frames.get(selectedFrame));
        // Rounded frame (design: 22dp card radius).
        preview.setBackgroundResource(R.drawable.ds_card);
        preview.setClipToOutline(true);
        int pp = Ui.dp(this, 4);
        preview.setPadding(pp, pp, pp, pp);
        card.addView(preview);
        card.addView(Ui.spacer(this, 8));
        // v2.2.1: tap-to-zoom — read the digits with your own eyes.
        preview.setClickable(true);
        preview.setFocusable(true);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showZoomDialog(frames.get(selectedFrame));
            }
        });
        TextView zoomHint = new TextView(this);
        zoomHint.setText(getString(R.string.meter_zoom_hint));
        zoomHint.setTextSize(11);
        zoomHint.setTextColor(Ds.muted());
        Ds.body(this, zoomHint, 400);
        zoomHint.setGravity(Gravity.CENTER);
        card.addView(zoomHint);
        card.addView(Ui.spacer(this, 8));

        suggestView = new TextView(this);
        suggestView.setText("");
        suggestView.setTextSize(20);
        suggestView.setTextColor(Ds.g800());
        Ds.heading(this, suggestView);
        suggestView.setGravity(Gravity.CENTER);
        card.addView(suggestView);
        // v2.2.0: shown only when the kWh reading was auto-detected.
        kwhNoteView = new TextView(this);
        kwhNoteView.setText(getString(R.string.meter_kwh_found));
        kwhNoteView.setTextSize(13);
        kwhNoteView.setTextColor(Ds.tipGreen());
        Ds.body(this, kwhNoteView, 500);
        kwhNoteView.setGravity(Gravity.CENTER);
        kwhNoteView.setVisibility(View.GONE);
        card.addView(kwhNoteView);
        content.addView(Ui.padded(this, card));

        // v2.2.1: shown only when OCR found NO candidate — specific
        // guidance in a blue info callout (design: .alert-card.blue).
        ocrTipsCard = Ds.alertCard(this, false,
                R.drawable.ic_info_circle, Ds.blueText(),
                getString(R.string.meter_ocr_fail_tips));
        ocrTipsCard.setVisibility(View.GONE);
        LinearLayout tipsWrap = Ui.col(this);
        tipsWrap.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        tipsWrap.addView(ocrTipsCard);
        content.addView(tipsWrap);

        // Manual entry card — the hero when OCR can't read the LCD.
        LinearLayout entry = Ds.card(this);
        TextView lab = new TextView(this);
        lab.setText(getString(R.string.meter_reading_label));
        lab.setTextSize(12);
        lab.setTextColor(Ds.muted());
        Ds.heading(this, lab);
        entry.addView(lab);
        entry.addView(Ui.spacer(this, 6));
        readingField = Ui.numberField(this, R.string.meter_reading_label);
        // v2.4.0: DECIMAL readings allowed — "352.32" jaisi bhi
        // chalegi ("," / "٫" separators normalize ho jatay hain).
        readingField.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        // v2.2.1: BIG entry field — manual entry is the hero when OCR
        // can't read the LCD.
        readingField.setTextSize(22);
        Ds.mono(readingField);
        entry.addView(readingField);
        entry.addView(Ui.spacer(this, 4));
        TextView hint = new TextView(this);
        hint.setText(getString(R.string.meter_reading_hint));
        hint.setTextSize(12);
        hint.setTextColor(Ds.muted());
        Ds.body(this, hint, 400);
        entry.addView(hint);
        content.addView(Ui.padded(this, entry));

        LinearLayout actions = Ui.col(this);
        actions.setPadding(Ui.dp(this, 16), Ui.dp(this, 2),
                Ui.dp(this, 16), 0);
        Button calc = Ds.greenBtn(this,
                getString(R.string.meter_calc_btn), 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onCalculate();
                    }
                });
        actions.addView(calc);
        content.addView(actions);

        LinearLayout backWrap = Ui.col(this);
        backWrap.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16),
                Ui.dp(this, 8));
        backWrap.addView(Ui.spacer(this, 10));
        backWrap.addView(Ds.backLink(this,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showCapture();
                    }
                }));
        content.addView(backWrap);

        // Reflect whatever the background OCR has found so far.
        onOcrFinished();
        if (!ocrDone && ocrSupported) {
            suggestView.setText(getString(R.string.meter_ocr_reading));
        }
    }

    private void onCalculate() {
        String typed = readingField.getText().toString().trim();
        int day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
        // v2.2.1: base = bill's PRESENT reading (bill ke baad se).
        Result r = MeterCheckCalc.compute(
                billBaseReading, typed, bill.category,
                bill.taxes, bill.gst, bill.fpa,
                bill.electricityCharges, day);
        if (r.error != null) {
            Toast.makeText(this, r.error, Toast.LENGTH_LONG).show();
            return;
        }
        showResult(r, typed);
    }

    // ------------------------------------------------------------------
    // Step 4: result card (everything labeled ANDAZA).
    // Design: Estimate screen.
    // ------------------------------------------------------------------

    private void showResult(final Result r, final String currReading) {
        // v3.6.35: ad-gate before showing unit-check result.
        AdsManager.showInterstitialBefore(this, new Runnable() {
            @Override public void run() {
                doShowResult(r, currReading);
            }
        });
    }

    private void doShowResult(Result r, String currReading) {
        content.removeAllViews();
        content.addView(stepsView(new int[]{1, 1, 2}, new int[]{
                R.string.step_record, R.string.step_analyze,
                R.string.step_estimate}));

        // v3.1.0: mirror last bill's charges for a near-accurate
        // estimate (BillMirrorCalc). Falls back to the legacy ratio
        // path when the bill has no usable charge base.
        BillMirrorCalc.MirrorResult mirror = BillMirrorCalc.mirror(bill,
                r.unitsUsed, r.energyEstimate, BillMirrorCalc.tariffCat(
                        bill == null ? null : bill.category));
        boolean useMirror = mirror != null && mirror.usable;
        double totalEstimate =
                useMirror ? mirror.estimatedTotal : r.estimatedTotal;

        // v3.3.0: accuracy tracker — remember this displayed
        // estimate so the NEXT fetched bill can be compared against
        // it ("Andaza vs Asal Bill"). Latest estimate only; a newer
        // one supersedes.
        try {
            AccuracyTracker.savePending(this,
                    disco.code + ":" + mode + ":" + ref,
                    r.unitsUsed, totalEstimate);
        } catch (Exception ignored) {
            // Tracking must never break the result screen.
        }

        // Hero: UNITS first — the user-checked figure, big and
        // unmissable (user request, v3.1.0). The Rs estimate stays
        // prominent below it.
        LinearLayout hero = Ds.card(this);
        hero.setBackgroundResource(R.drawable.ds_card_est);
        if (Build.VERSION.SDK_INT >= 21) {
            hero.setElevation(Ui.dp(this, 3));
        }
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        int hp = Ui.dp(this, 22);
        hero.setPadding(hp, Ui.dp(this, 30), hp, Ui.dp(this, 30));

        TextView eyebrow = new TextView(this);
        eyebrow.setText(getString(R.string.est_units_eyebrow));
        eyebrow.setTextSize(11);
        eyebrow.setTextColor(Ds.faint());
        Ds.heading(this, eyebrow);
        if (Build.VERSION.SDK_INT >= 21
                && !Fonts.isUrdu(eyebrow.getText())) {
            eyebrow.setLetterSpacing(0.18f);
            eyebrow.setAllCaps(true);
        }
        eyebrow.setGravity(Gravity.CENTER);
        hero.addView(eyebrow);
        hero.addView(Ui.spacer(this, 10));

        TextView unitsBig = new TextView(this);
        unitsBig.setText(splitUnits(MeterCheckCalc.fmtUnits(r.unitsUsed)));
        unitsBig.setTextSize(48);
        unitsBig.setTextColor(Ds.g800());
        Ds.amount(this, unitsBig);
        if (Build.VERSION.SDK_INT >= 21
                && !Fonts.isUrdu(unitsBig.getText())) {
            unitsBig.setLetterSpacing(-0.03f);
        }
        unitsBig.setGravity(Gravity.CENTER);
        hero.addView(unitsBig);
        hero.addView(Ui.spacer(this, 12));

        TextView billLbl = new TextView(this);
        billLbl.setText(getString(R.string.meter_andaza_bill));
        billLbl.setTextSize(11);
        billLbl.setTextColor(Ds.faint());
        Ds.heading(this, billLbl);
        if (Build.VERSION.SDK_INT >= 21
                && !Fonts.isUrdu(billLbl.getText())) {
            billLbl.setLetterSpacing(0.16f);
        }
        billLbl.setGravity(Gravity.CENTER);
        hero.addView(billLbl);
        hero.addView(Ui.spacer(this, 4));

        TextView billAmt = new TextView(this);
        billAmt.setText(Tariff.money(totalEstimate));
        billAmt.setTextSize(26);
        billAmt.setTextColor(Ds.g800());
        Ds.heading(this, billAmt);
        billAmt.setGravity(Gravity.CENTER);
        hero.addView(billAmt);
        hero.addView(Ui.spacer(this, 6));

        TextView heroSub = new TextView(this);
        heroSub.setText(useMirror ? getString(R.string.est_hero_mirror)
                : getString(r.taxRatioFallback
                        ? R.string.meter_tax_fallback
                        : R.string.meter_tax_real));
        heroSub.setTextSize(13);
        heroSub.setTextColor(Ds.muted());
        Ds.body(this, heroSub, 400);
        heroSub.setGravity(Gravity.CENTER);
        hero.addView(heroSub);
        content.addView(Ui.padded(this, hero));

        // Warnings stay slim and appear only when they matter.
        if (r.protectedCliff) {
            content.addView(paddedAlert(Ds.warnStrip(this,
                    R.drawable.ic_prohibit,
                    warnHtml(getString(
                            R.string.meter_protected_warn)))));
        }
        if (r.slabWarning && r.nextSlab > 0) {
            content.addView(paddedAlert(Ds.warnStrip(this,
                    R.drawable.ic_warning,
                    getString(R.string.meter_slab_warn,
                            MeterCheckCalc.fmtUnits(r.remaining),
                            r.nextSlab))));
        }


        // The slab progress lives in its own card now.
        content.addView(Ui.padded(this, slabCard(r)));

        // Breakdown hides behind one tap - the declutter win.
        content.addView(Ui.padded(this,
                tafseelCard(r, currReading, mirror)));

        // v3.2.0: cliff-crossed banner — a Protected estimate above
        // 200 units loses the subsidy AND the protected slabs.
        if (useMirror && mirror.crossedCliff) {
            LinearLayout banner = new LinearLayout(this);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setBackgroundResource(R.drawable.ds_warn_strip);
            int bp2 = Ui.dp(this, 12);
            banner.setPadding(bp2, bp2, bp2, bp2);
            TextView bt = new TextView(this);
            bt.setText(getString(R.string.crossed_cliff_banner));
            bt.setTextSize(13.5f);
            bt.setTextColor(Ds.red());
            Ds.body(this, bt, 600);
            banner.addView(bt);
            LinearLayout bw = Ui.col(this);
            bw.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                    Ui.dp(this, 16), 0);
            bw.addView(banner);
            content.addView(bw);
        }

        // v3.2.0: Guard + What-if entry buttons (need the bill).
        if (bill != null) {
            final String cr = currReading;
            LinearLayout toolRow = new LinearLayout(this);
            toolRow.setOrientation(LinearLayout.HORIZONTAL);
            Button guardBtn = Ds.outlineBtn(this,
                    getString(R.string.guard_save_reading_btn),
                    R.drawable.ic_warning,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            GuardActivity.open(MeterCheckActivity.this,
                                    disco, mode, ref, cr);
                        }
                    });
            LinearLayout.LayoutParams gp =
                    new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            gp.setMargins(0, 0, Ui.dp(this, 5), 0);
            toolRow.addView(guardBtn, gp);
            Button whatIfBtn = Ds.outlineBtn(this,
                    getString(R.string.whatif_open_btn),
                    R.drawable.ic_edit,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            WhatIfActivity.open(
                                    MeterCheckActivity.this, disco,
                                    mode, ref);
                        }
                    });
            LinearLayout.LayoutParams wp =
                    new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wp.setMargins(Ui.dp(this, 5), 0, 0, 0);
            toolRow.addView(whatIfBtn, wp);
            LinearLayout tw = Ui.col(this);
            tw.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                    Ui.dp(this, 16), 0);
            tw.addView(toolRow);
            content.addView(tw);
        }

        // "Ye ANDAZA kyun hai?" - quiet centered link, not a card.
        final TextView whyA = new TextView(this);
        whyA.setText(getString(R.string.meter_why_andaza_a));
        whyA.setTextSize(12.5f);
        whyA.setTextColor(Ds.muted());
        Ds.body(this, whyA, 400);
        whyA.setVisibility(View.GONE);
        whyA.setGravity(Gravity.CENTER);
        LinearLayout whyWrap = Ui.col(this);
        whyWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        whyWrap.setPadding(Ui.dp(this, 20), Ui.dp(this, 14),
                Ui.dp(this, 20), 0);
        whyWrap.addView(Ds.linkLike(this,
                getString(R.string.meter_why_andaza_q),
                R.drawable.ic_info_circle, Ds.linkBlue(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        whyA.setVisibility(
                                whyA.getVisibility() == View.VISIBLE
                                        ? View.GONE : View.VISIBLE);
                    }
                }));
        whyWrap.addView(whyA);
        content.addView(whyWrap);

        // Honesty footer.
        TextView d = new TextView(this);
        d.setText(getString(R.string.meter_disclaimer));
        d.setTextSize(11.5f);
        d.setTextColor(Ds.muted());
        d.setLineSpacing(0, 1.6f);
        Ds.body(this, d, 400);
        d.setGravity(Gravity.CENTER);
        LinearLayout dWrap = Ui.col(this);
        dWrap.setPadding(Ui.dp(this, 20), Ui.dp(this, 14),
                Ui.dp(this, 20), 0);
        dWrap.addView(d);
        content.addView(dWrap);

        LinearLayout actions = Ui.col(this);
        actions.setPadding(Ui.dp(this, 20), Ui.dp(this, 14),
                Ui.dp(this, 20), Ui.dp(this, 8));
        Button bilDekho = Ds.greenBtn(this,
                getString(R.string.meter_bildekho_btn), 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openBill();
                    }
                });
        actions.addView(bilDekho);
        actions.addView(Ui.spacer(this, 10));
        Button recheck = Ds.outlineBtn(this,
                getString(R.string.meter_recheck_btn), 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showCapture();
                    }
                });
        actions.addView(recheck);
        content.addView(actions);

        AdsManager.loadBannerInto(this, bannerBox);
    }

    private View paddedAlert(View alert) {
        LinearLayout wrap = Ui.col(this);
        wrap.setPadding(Ui.dp(this, 20), Ui.dp(this, 14),
                Ui.dp(this, 20), 0);
        wrap.addView(alert);
        return wrap;
    }

    /** "Rs 261.17" -> "Rs" (20sp) + "261.17" (48sp, hero). */
    private CharSequence splitMoney(String money) {
        int sp = money.indexOf(' ');
        String pre = sp > 0 ? money.substring(0, sp) : "Rs";
        String num = sp > 0 ? money.substring(sp + 1) : money;
        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder();
        int ps = sb.length();
        sb.append(pre);
        sb.append(" ");
        int ns = sb.length();
        sb.append(num);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(20, true),
                ps, ns,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(48, true),
                ns, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** "22.98" (48sp hero) + " units" (20sp, muted) — v3.1.0. */
    private CharSequence splitUnits(String units) {
        String word = getString(R.string.est_units_word);
        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder();
        sb.append(units);
        int ws = sb.length();
        sb.append(" ");
        sb.append(word);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(20, true),
                ws, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Ds.muted()),
                ws, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Bolds the lead (text before the em-dash) of an alert message. */
    /**
     * v3.0.0: bold the warning's lead (before the em-dash) as HTML
     * for the slim warnStrip.
     */
    private String warnHtml(String text) {
        int dash = text.indexOf("\u2014");
        if (dash > 0) {
            return "<b>" + text.substring(0, dash).trim() + "</b> "
                    + text.substring(dash);
        }
        return text;
    }

    private CharSequence boldLead(String text) {
        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder(text);
        int dash = text.indexOf("—");
        if (dash > 0) {
            sb.setSpan(new android.text.style.StyleSpan(
                    android.graphics.Typeface.BOLD), 0, dash,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /**
     * v2.6.1: ONE calm usage card - reading pair, units since the
     * bill, then slab progress + month-end projection. Replaces the
     * old separate reading card and slab card (declutter).
     */
    /**
     * v3.0.0: usage card — reading pair + units + the monthly
     * projection. Slab progress lives in its own card (slabCard).
     */
    private View slabCard(Result r) {
        LinearLayout card = Ds.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        if (r.nextSlab > 0) {
            TextView lab = new TextView(this);
            lab.setText(getString(R.string.slab_card_title,
                    r.nextSlab));
            lab.setTextSize(13);
            lab.setTextColor(Ds.muted());
            Ds.body(this, lab, 500);
            lab.setGravity(Gravity.CENTER);
            card.addView(lab);
            card.addView(Ui.spacer(this, 4));

            TextView big = new TextView(this);
            String remS = MeterCheckCalc.fmtUnits(r.remaining);
            String suf = getString(R.string.slab_card_suffix);
            android.text.SpannableStringBuilder sb =
                    new android.text.SpannableStringBuilder();
            int bs = sb.length();
            sb.append(remS);
            int be = sb.length();
            sb.append(" ");
            int ss = sb.length();
            sb.append(suf);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(
                    22, true), bs, be,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(
                    14, true), ss, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            big.setText(sb);
            big.setGravity(Gravity.CENTER);
            big.setTextColor(r.slabWarning ? Ds.red() : Ds.ink());
            Ds.heading(this, big);
            card.addView(big);
            card.addView(Ui.spacer(this, 8));

            float frac = (float) ((r.nextSlab - r.remaining)
                    / r.nextSlab);
            card.addView(Ds.progBar(this, frac));
            card.addView(Ui.spacer(this, 10));

        } else {
            TextView s = new TextView(this);
            s.setText(getString(R.string.meter_top_slab));
            s.setTextSize(13);
            s.setTextColor(Ds.ink());
            Ds.body(this, s, 500);
            s.setGravity(Gravity.CENTER);
            card.addView(s);
        }
        return card;
    }

    /**
     * v3.1.0: the receipt breakdown is ALWAYS visible — compact
     * dotted-leader rows with plain numeric amounts; only the total
     * keeps "Rs". Row 0 is the readings pair (the old usage card was
     * folded in here — fewer cards, airier screen). Mirror rows come
     * from BillMirrorCalc when the last bill had a usable charge base;
     * otherwise the legacy ratio rows are kept. Method name kept for
     * structural tests.
     */
    private View tafseelCard(Result r, String currReading,
            BillMirrorCalc.MirrorResult mirror) {
        LinearLayout card = Ds.card(this);

        TextView head = new TextView(this);
        head.setText(getString(R.string.andaza_how_title));
        head.setTextSize(11.5f);
        head.setTextColor(Ds.faint());
        if (Build.VERSION.SDK_INT >= 21
                && !Fonts.isUrdu(head.getText())) {
            head.setLetterSpacing(0.1f);
            head.setAllCaps(true);
        }
        Ds.heading(this, head);
        card.addView(head);
        card.addView(Ui.spacer(this, 4));

        addBreakdown(card, r, currReading, mirror);
        return card;
    }

    /**
     * v3.1.0: "Andaza bill kaise bana?" — one dotted-leader receipt
     * row per REAL term of the estimate. With a usable mirror, every
     * charge is a line the last bill actually printed (nothing
     * invented); without one, the legacy ratio / 18%-fallback rows.
     * The TOTAL row is bold.
     */
    private void addBreakdown(LinearLayout card,
            MeterCheckCalc.Result r, String currReading,
            BillMirrorCalc.MirrorResult mirror) {
        boolean useMirror = mirror != null && mirror.usable;

        // Row 0: readings pair -> units (mono, like the old usage card).
        // v3.1.1: use the RESULT's parsed base (r.baseReading), never
        // the billBaseReading field — the no-bill manual path has no
        // bill, so billBaseReading is null there.
        String pair = getString(R.string.meter_readings_pair,
                MeterCheckCalc.fmtUnits(r.baseReading), currReading);
        android.text.SpannableStringBuilder pairSb =
                new android.text.SpannableStringBuilder(pair);
        pairSb.setSpan(new android.text.style.TypefaceSpan("monospace"),
                0, pairSb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        card.addView(Ds.dottedRow(this, pairSb,
                MeterCheckCalc.fmtUnits(r.unitsUsed) + " "
                        + getString(R.string.est_units_word),
                false));
        card.addView(Ds.dashDivider(this));

        if (useMirror) {
            // v3.1.2: show the CALIBRATED energy (bill's real Rs/unit
            // basis) so the rows add up to the total below.
            card.addView(Ds.dottedRow(this,
                    getString(R.string.andaza_lbl_energy),
                    Tariff.moneyPlain(mirror.energyEstimate), false));
            // v3.1.3: when the mirror netted a Protected subsidy into
            // the energy basis, say so under the Energy row.
            if (mirror.subsidyNetted) {
                TextView sn = new TextView(this);
                sn.setText(getString(R.string.andaza_subsidy_net_note));
                sn.setTextSize(11f);
                sn.setTextColor(Ds.faint());
                Ds.body(this, sn, 400);
                sn.setGravity(Gravity.END);
                sn.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
                card.addView(sn);
            }
            card.addView(Ds.dashDivider(this));
            for (BillMirrorCalc.MirrorRow mrow : mirror.rows) {
                String label = mrow.label;
                if (mrow.kind == BillMirrorCalc.LineKind.PER_UNIT) {
                    label += " \u00b7 " + getString(
                            R.string.andaza_rate_per_unit,
                            Tariff.money(mrow.aux));
                } else if (mrow.kind == BillMirrorCalc.LineKind.PERCENTAGE
                        || mrow.kind == BillMirrorCalc.LineKind.SUBSIDY) {
                    label += " \u00b7 "
                            + MeterCheckCalc.fmtPct(mrow.aux) + "%";
                }
                card.addView(Ds.dottedRow(this, label,
                        Tariff.moneyPlain(mrow.estAmount), false));
                card.addView(Ds.dashDivider(this));
            }
            card.addView(Ds.dottedRow(this,
                    getString(R.string.andaza_lbl_total),
                    Tariff.money(mirror.estimatedTotal), true));
            if (mirror.excludedCount > 0) {
                TextView n = new TextView(this);
                n.setText(getString(R.string.est_excluded_note));
                n.setTextSize(11.5f);
                n.setTextColor(Ds.faint());
                n.setLineSpacing(0, 1.5f);
                Ds.body(this, n, 400);
                n.setGravity(Gravity.CENTER);
                n.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
                card.addView(n);
            }
            return;
        }

        // Legacy path (unchanged): ratio / 18%-fallback rows.
        List<MeterCheckCalc.BreakRow> rows =
                MeterCheckCalc.breakdown(r);
        if (rows.isEmpty()) return;
        for (int i = 0; i < rows.size(); i++) {
            MeterCheckCalc.BreakRow br = rows.get(i);
            String label;
            boolean total = false;
            switch (br.kind) {
                case ENERGY:
                    label = getString(R.string.andaza_lbl_energy,
                            MeterCheckCalc.fmtUnits(br.aux));
                    break;
                case TAXES:
                    label = getString(R.string.andaza_lbl_taxes,
                            MeterCheckCalc.fmtPct(br.aux) + "%");
                    break;
                case GST:
                    label = getString(R.string.andaza_lbl_gst,
                            MeterCheckCalc.fmtPct(br.aux) + "%");
                    break;
                case FPA:
                    label = getString(R.string.andaza_lbl_fpa,
                            MeterCheckCalc.fmtPct(br.aux) + "%");
                    break;
                case TAX_FALLBACK:
                    label = getString(
                            R.string.andaza_lbl_tax_fallback);
                    break;
                default: // TOTAL
                    label = getString(R.string.andaza_lbl_total);
                    total = true;
                    break;
            }
            card.addView(Ds.dottedRow(this, label,
                    total ? Tariff.money(br.amount)
                            : Tariff.moneyPlain(br.amount), total));
            if (i < rows.size() - 1) {
                card.addView(Ds.dashDivider(this));
            }
        }
    }

    /** Official bill stays one tap away. */
    private void openBill() {
        if (fromBill) {
            finish();
            return;
        }
        Intent i = new Intent(this, BillActivity.class);
        i.putExtra(BillActivity.EXTRA_MODE, mode);
        i.putExtra(BillActivity.EXTRA_REF, ref);
        startActivity(i);
        finish();
    }
}
