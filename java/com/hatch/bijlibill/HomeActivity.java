package com.hatch.bijlibill;

import android.content.Context;
import android.content.Intent;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * v2.6.0 design-system Home (logic unchanged from v2.5.0):
 *  - Disco selector chips (LESCO..QESCO; K-Electric = coming soon).
 *  - Segmented [Reference No] [Customer ID] (customer-ID wiring is
 *    best-effort — NOT live-verified, see README).
 *  - PASTE button pastes the clipboard into the active field.
 *  - "Mere Meter": auto-saved after every successful bill fetch
 *    (tap = re-fetch, long-press = delete). Legacy manual saves
 *    migrate once.
 *  - "UNIT CHECK" entry (green button) only when a cached bill exists.
 *  - Bottom nav: Home | Bills | Unit Check | Settings(About).
 * No calculator — removed per user.
 */
public class HomeActivity extends BaseActivity {

    private static final String PREFS = "bijli_bill_refs";
    private static final String KEY_TAB = "tab_mode";   // "ref" or "cid"
    private static final String KEY_DISCO = "disco_code"; // Disco.code
    /** v2.1.0: last bill fetched — Unit Check entry needs a cached bill. */
    private static final String KEY_LAST_MODE = "metercheck_last_mode";
    private static final String KEY_LAST_REF = "metercheck_last_ref";
    private static final String KEY_LAST_DISCO = "metercheck_last_disco";
    private static final String ITEM_SEP = " "; // U+001F unit separator
    private static final String KEY_REFS = "saved_refs"; // legacy, migrated

    private static final String KEY_MIGRATED = "migrated_legacy_v1";

    private static final String MODE_REF = "ref";
    private static final String MODE_CID = "cid";

    private String tabMode = MODE_REF;
    private Disco disco = Disco.LESCO;

    private TextView segRefBtn;
    private TextView segCidBtn;
    private LinearLayout discoRow;
    private TextView fieldLabel;
    private TextView refField;
    private TextView cidField;
    private TextView errorView;
    private LinearLayout savedList;
    /** v3.4.0: streak card (visible only while a streak exists). */
    private View streakCard;
    private TextView streakCardText;
    /** v3.6.0: gentle banner when OS notifications are off. */
    private View notifBanner;
    private static final String KEY_NOTIF_PROMPT = "notif_off_prompt_ms";
    /** Max one gentle prompt per 3 days — never nag. */
    private static final long NOTIF_PROMPT_COOLDOWN_MS =
            3L * 86400000L;
    /** v3.2.0: Guard + What-if entry row (same visibility as unit check). */
    private ScrollView scrollView;
    /** v2.1.0: visible only when a cached bill exists. */
    private String meterCheckMode;
    private String meterCheckRef;
    private Disco meterCheckDisco;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        tabMode = sp.getString(KEY_TAB, MODE_REF);
        if (!MODE_CID.equals(tabMode)) tabMode = MODE_REF;
        disco = Disco.fromCode(sp.getString(KEY_DISCO, "lesco"));
        AdsManager.init(this);
        migrateLegacyOnce(sp);
        // v3.4.0: weekly usage report — always-on repeating alarm;
        // the receiver no-ops without guard/bill data, so this is
        // safe on every app start.
        WeeklyReportAlarm.schedule(this);
        buildUi();
        refreshTabs();
        refreshChips();
        // v3.6.9: "outside Pakistan?" popup (like competitor apps) — PITC
        // blocks foreign IPs, so bill fetch fails abroad. Ask once.
        maybeAskOutsidePakistan(sp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSavedList();
        refreshMeterCheckButton();
        refreshStreakCard();
        refreshNotifBanner();
    }

    /** One-time migration of the old manual saves into SavedMeters. */
    private void migrateLegacyOnce(SharedPreferences sp) {
        if (sp.getBoolean(KEY_MIGRATED, false)) return;
        Set<String> set =
                sp.getStringSet(KEY_REFS, new LinkedHashSet<String>());
        List<String[]> items = new ArrayList<String[]>();
        for (String s : set) {
            String[] parts = s.split(ITEM_SEP, -1);
            if (parts.length == 3) items.add(parts);
            else if (parts.length == 2) {
                items.add(new String[]{parts[0], MODE_REF, parts[1]});
            }
        }
        SavedMeters.migrateLegacy(MeterUi.store(this), items);
        sp.edit().putBoolean(KEY_MIGRATED, true).apply();
    }

    private void persistTab() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TAB, tabMode)
                .apply();
    }

    private void persistDisco() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DISCO, disco.code)
                .apply();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    /**
     * v3.6.9: "Are you outside Pakistan?" one-time popup. PITC geo-blocks
     * foreign IPs so bill fetch fails abroad — warn once, remember choice.
     * Custom designed dialog (not the plain system AlertDialog).
     */
    private void maybeAskOutsidePakistan(final SharedPreferences sp) {
        if (sp.contains("outside_pk")) return;

        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
        }
        dlg.setCancelable(false);

        int d = (int) (getResources().getDisplayMetrics().density + 0.5f);
        int dp24 = 24 * d, dp16 = 16 * d, dp12 = 12 * d, dp8 = 8 * d;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card_24);
        card.setPadding(dp24, dp24, dp24, dp24);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        // Icon
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_info_circle);
        ic.setColorFilter(Ds.g800(),
                android.graphics.PorterDuff.Mode.SRC_IN);
        int ics = 48 * d;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ics, ics);
        ilp.bottomMargin = dp16;
        ic.setLayoutParams(ilp);
        card.addView(ic);

        // Title
        TextView title = new TextView(this);
        title.setText(R.string.outside_pk_title);
        title.setTextSize(18);
        title.setTextColor(Ds.ink());
        title.setGravity(Gravity.CENTER);
        Ds.heading(this, title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp8;
        title.setLayoutParams(tlp);
        card.addView(title);

        // Message
        TextView msg = new TextView(this);
        msg.setText(R.string.outside_pk_msg);
        msg.setTextSize(14);
        msg.setTextColor(Ds.muted());
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(dp8, 1.3f);
        Ds.body(this, msg, 400);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.bottomMargin = dp24;
        msg.setLayoutParams(mlp);
        card.addView(msg);

        // "No, I'm in Pakistan" — green primary button
        LinearLayout btnNo = new LinearLayout(this);
        btnNo.setOrientation(LinearLayout.HORIZONTAL);
        btnNo.setGravity(Gravity.CENTER);
        btnNo.setBackgroundResource(R.drawable.nd_btn_solid);
        btnNo.setMinimumHeight(52 * d);
        btnNo.setClickable(true);
        btnNo.setFocusable(true);
        TextView tNo = new TextView(this);
        tNo.setText(R.string.outside_pk_no);
        tNo.setTextSize(15);
        tNo.setTextColor(Color.WHITE);
        Ds.heading(this, tNo);
        btnNo.addView(tNo);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp12;
        btnNo.setLayoutParams(blp);
        btnNo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putBoolean("outside_pk", false).apply();
                dlg.dismiss();
            }
        });
        card.addView(btnNo);

        // "Yes, I'm outside" — outlined secondary button
        LinearLayout btnYes = new LinearLayout(this);
        btnYes.setOrientation(LinearLayout.HORIZONTAL);
        btnYes.setGravity(Gravity.CENTER);
        btnYes.setBackgroundResource(R.drawable.ds_btn_outline);
        btnYes.setMinimumHeight(52 * d);
        btnYes.setClickable(true);
        btnYes.setFocusable(true);
        TextView tYes = new TextView(this);
        tYes.setText(R.string.outside_pk_yes);
        tYes.setTextSize(15);
        tYes.setTextColor(Ds.g800());
        Ds.heading(this, tYes);
        btnYes.addView(tYes);
        btnYes.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        btnYes.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putBoolean("outside_pk", true).apply();
                dlg.dismiss();
            }
        });
        card.addView(btnYes);

        dlg.setContentView(card);
        // Dialog width: 85% of screen
        if (dlg.getWindow() != null) {
            int w = (int) (getResources().getDisplayMetrics().widthPixels
                    * 0.88);
            dlg.getWindow().setLayout(w,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private void buildUi() {
        Window w = getWindow();
        if (w != null) {
            w.setStatusBarColor(Ds.bg());
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ds.bg());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scrollView.setFillViewport(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(topBar());
        content.addView(greeting());
        content.addView(secLabel(R.string.disco_label, 4));
        content.addView(discoChips());
        content.addView(inputCard());

        // v3.6.77: BILL DEKHO button moved INSIDE inputCard (user request).
        int hm = Ui.dp(this, 20);

        // v3.6.71: Quick Tools — Unit Check + Bill History cards.
        content.addView(quickToolsSection());


        // v3.4.0: streak card — flame + consecutive check-in days;
        // tapping opens the Guard. Hidden while no streak exists.
        streakCard = streakCardView();
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scp.setMargins(hm, Ui.dp(this, 8), hm, 0);
        content.addView(streakCard, scp);

        // v3.6.0: gentle banner when the user disabled
        // notifications at OS level (the Sunday reminder needs
        // them). Shown at most once per 3 days; there is no
        // in-app toggle anywhere — this only opens OS settings.
        notifBanner = notifBannerView();
        LinearLayout.LayoutParams nbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nbp.setMargins(hm, Ui.dp(this, 8), hm, 0);
        notifBanner.setVisibility(View.GONE);
        content.addView(notifBanner, nbp);

        // v3.6.82: errorView now inside inputCard (user request).
        // (removed from here)

        // v3.6.63: MERE METER section removed (user: separate Bills page exists).
        content.addView(Ui.spacer(this, 24));
        root.addView(scrollView);
        // v3.6.16: test banner ad (user request — see ad on all pages).
        LinearLayout bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bannerBox);
        AdsManager.loadBannerInto(this, bannerBox);
        // v3.6.5: shared bottom nav (no top dash — active = color only).
        root.addView(Ds.bottomNav(this, Ds.TAB_HOME));

        setContentView(root);
    }

    // ---- top bar: logo + name + language toggle ----

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // v3.6.60: dark green header (matches BillActivity).
        bar.setBackgroundColor(Ds.ndTealAccent());
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 12),
                Ui.dp(this, 18), Ui.dp(this, 10));

        FrameLayout logo = new FrameLayout(this);
        int ls = Ui.dp(this, 38);
        logo.setLayoutParams(new LinearLayout.LayoutParams(ls, ls));
        logo.setBackgroundResource(R.drawable.ds_logo_bg);
        ImageView bolt = new ImageView(this);
        bolt.setImageResource(R.drawable.ic_bolt_amber);
        int bs = Ui.dp(this, 20);
        bolt.setLayoutParams(new FrameLayout.LayoutParams(
                bs, bs, Gravity.CENTER));
        logo.addView(bolt);
        bar.addView(logo);

        TextView name = new TextView(this);
        name.setText(getString(R.string.home_title));
        name.setTextSize(17);
        name.setTextColor(android.graphics.Color.WHITE);
        Ds.heading(this, name);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = Ui.dp(this, 12);
        name.setLayoutParams(nlp);
        bar.addView(name);

        View toggle = Ds.langToggle(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 12);
        toggle.setLayoutParams(tlp);
        // push to the right
        LinearLayout spacer = new LinearLayout(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                0, 1, 1f));
        bar.addView(spacer);
        bar.addView(toggle);
        return bar;
    }

    // ---- greeting ----

    private View greeting() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        // v3.6.83: more bottom space (user request).
        g.setPadding(Ui.dp(this, 20), Ui.dp(this, 6),
                Ui.dp(this, 20), Ui.dp(this, 8));

        TextView h = new TextView(this);
        h.setText(getString(R.string.home_greeting));
        h.setTextSize(25);
        h.setTextColor(Ds.ink());
        Ds.heading(this, h);
        g.addView(h);

        return g;
    }

    /** Small caps section label, e.g. "BIJLI COMPANY". */
    private View secLabel(int resId, int topMarginDp) {
        TextView l = new TextView(this);
        l.setText(getString(resId));
        l.setAllCaps(true);
        l.setTextSize(11.5f);
        l.setLetterSpacing(0.1f);
        l.setTextColor(Ds.faint());
        Ds.heading(this, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 20), Ui.dp(this, topMarginDp),
                Ui.dp(this, 20), Ui.dp(this, 10));
        l.setLayoutParams(lp);
        return l;
    }

    // ---- disco chips (horizontal scroll) ----

    // v3.6.87: single-row carousel (user: no double row, 3-4 visible + scroll).

    private View discoChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        // v3.6.87: no clip — chips peek to show scroll (user: carousel).
        hsv.setClipToPadding(false);
        int hp = Ui.dp(this, 20);
        // v3.6.83: more bottom space (user request).
        hsv.setPadding(hp, Ui.dp(this, 2), hp, Ui.dp(this, 12));

        discoRow = new LinearLayout(this);
        discoRow.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(discoRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return hsv;
    }

    private void refreshChips() {
        discoRow.removeAllViews();
        for (int i = 0; i < Disco.values().length; i++) {
            final Disco d = Disco.values()[i];
            final boolean isSel = d.code.equals(disco.code);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(
                    // v3.6.80: selected = yellow (user request, like toggle).
                    isSel ? R.drawable.ds_chip_yellow : R.drawable.ds_chip);
            int v = Ui.dp(this, 9), h = Ui.dp(this, 16);
            chip.setPadding(h, v, h, v);
            if (isSel && android.os.Build.VERSION.SDK_INT >= 21) {
                chip.setElevation(Ui.dp(this, 3));
            }

            TextView t = new TextView(this);
            t.setText(d.displayName);
            // v3.6.87: K-Electric smaller font (user: was showing double line).
            t.setTextSize(d.comingSoon ? 11 : 13);
            t.setSingleLine(true);
            // v3.6.80: dark text on yellow when selected (user request).
            t.setTextColor(isSel ? Ds.amberDark() : Ds.muted());
            Ds.heading(this, t);
            chip.addView(t);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            chip.setLayoutParams(lp);
            chip.setClickable(true);
            chip.setFocusable(true);
            if (d.comingSoon) {
                chip.setAlpha(0.55f);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        android.widget.Toast.makeText(HomeActivity.this,
                                R.string.kelectric_soon,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        disco = d;
                        persistDisco();
                        refreshChips();
                    }
                });
            }
            discoRow.addView(chip);
        }
    }

    // ---- input card: seg + label + field + paste + helper ----

    private View inputCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 20),
                Ui.dp(this, 20), Ui.dp(this, 22));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            card.setElevation(Ui.dp(this, 4));
        }
        Ds.tealShadow(card);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int hm = Ui.dp(this, 20);
        clp.setMargins(hm, Ui.dp(this, 8), hm, 0);
        card.setLayoutParams(clp);

        // segmented toggle
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundResource(R.drawable.ds_seg_bg);
        seg.setPadding(Ui.dp(this, 4), Ui.dp(this, 4),
                Ui.dp(this, 4), Ui.dp(this, 4));
        segRefBtn = segBtn(R.string.tab_ref);
        segRefBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tabMode = MODE_REF;
                persistTab();
                refreshTabs();
            }
        });
        segCidBtn = segBtn(R.string.tab_custid);
        segCidBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tabMode = MODE_CID;
                persistTab();
                refreshTabs();
            }
        });
        seg.addView(segRefBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(segCidBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams seglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        seglp.bottomMargin = Ui.dp(this, 16);
        card.addView(seg, seglp);

        // field label
        fieldLabel = new TextView(this);
        fieldLabel.setTextSize(13);
        fieldLabel.setTextColor(Ds.g800());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.bottomMargin = Ui.dp(this, 10);
        card.addView(fieldLabel, flp);

        // input wrap: link icon + field + PASTE
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setBackgroundResource(R.drawable.ds_input_bg);
        wrap.setPadding(Ui.dp(this, 14), Ui.dp(this, 6),
                Ui.dp(this, 8), Ui.dp(this, 6));

        ImageView link = new ImageView(this);
        link.setImageResource(R.drawable.ic_link);
        int lsz = Ui.dp(this, 18);
        link.setLayoutParams(new LinearLayout.LayoutParams(lsz, lsz));
        wrap.addView(link);

        refField = inputField(R.string.ref_hint,
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cidField = inputField(R.string.custid_hint,
                InputType.TYPE_CLASS_NUMBER);
        wrap.addView(refField);
        wrap.addView(cidField);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.dp(this, 12);
        card.addView(wrap, wlp);

        // v3.6.77: VIEW BILL button INSIDE the card (user request).
        // Proper spacing between input field and button.
        View dekho = billDekhoBtn();
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 14);
        card.addView(dekho, dlp);

        // v3.6.82: error INSIDE the card (user request).
        errorView = new TextView(this);
        errorView.setTextSize(13);
        errorView.setTextColor(Ds.red());
        Ds.body(this, errorView, 600);
        errorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(this, 8);
        card.addView(errorView, elp);

        return card;
    }

    private TextView segBtn(int resId) {
        TextView b = new TextView(this);
        b.setText(getString(resId));
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView inputField(int hintRes, int inputType) {
        TextView f = new android.widget.EditText(this);
        f.setHint(getString(hintRes));
        f.setHintTextColor(Color.parseColor("#9DB3A8"));
        f.setTextSize(15);
        f.setTypeface(Fonts.jakarta(this, 600));
        f.setTextColor(Ds.ink());
        f.setBackgroundColor(Color.TRANSPARENT);
        f.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
        ((android.widget.EditText) f).setInputType(inputType);
        f.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 10);
        lp.rightMargin = Ui.dp(this, 4);
        f.setLayoutParams(lp);
        return f;
    }

    private void refreshTabs() {
        boolean isRef = MODE_REF.equals(tabMode);
        tintSeg(segRefBtn, isRef);
        tintSeg(segCidBtn, !isRef);
        refField.setVisibility(isRef ? View.VISIBLE : View.GONE);
        cidField.setVisibility(isRef ? View.GONE : View.VISIBLE);
        fieldLabel.setText(getString(isRef
                ? R.string.ref_label : R.string.tab_custid));
        Ds.heading(this, fieldLabel);
    }

    private void tintSeg(TextView b, boolean active) {
        b.setBackgroundResource(
                // v3.6.80: active = yellow (user request, like toggle).
                active ? R.drawable.ds_seg_yellow : 0);
        if (!active) b.setBackgroundColor(Color.TRANSPARENT);
        else if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.setElevation(Ui.dp(this, 2));
        }
        // v3.6.80: dark text on yellow when active (user request).
        b.setTextColor(active ? Ds.amberDark() : Ds.muted());
        Ds.heading(this, b);
    }

    // ---- BILL DEKHO (amber) ----

    private View billDekhoBtn() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        // v3.6.62: gradient (was amber, user request).
        b.setBackgroundResource(R.drawable.nd_btn_solid);
        b.setMinimumHeight(Ui.dp(this, 54));
        b.setPadding(Ui.dp(this, 16), 0,
                Ui.dp(this, 16), 0);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.setElevation(Ui.dp(this, 8));
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            b.setOutlineSpotShadowColor(
                    Color.parseColor("#61F6A609"));
        }

        TextView t = new TextView(this);
        t.setText(getString(R.string.fetch_btn));
        t.setTextSize(14.5f);
        t.setLetterSpacing(0.05f);
        // v3.6.63: white on gradient (was amberDark for amber bg).
        t.setTextColor(android.graphics.Color.WHITE);
        Ds.heading(this, t);
        b.addView(t);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_right);
        // v3.6.63: white arrow on gradient.
        arrow.setColorFilter(android.graphics.Color.WHITE,
                android.graphics.PorterDuff.Mode.SRC_IN);
        int as = Ui.dp(this, 18);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                as, as);
        alp.leftMargin = Ui.dp(this, 10);
        arrow.setLayoutParams(alp);
        b.addView(arrow);

        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onFetchClicked(); }
        });
        return b;
    }

    // ---- QUICK TOOLS (v3.6.71) ----

    private View quickToolsSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        // v3.6.79: don't clip card shadows.
        sec.setClipChildren(false);
        sec.setClipToPadding(false);
        int hm = Ui.dp(this, 20);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        // v3.6.78: bottom margin 16dp (user: shadow was cut at bottom).
        slp.setMargins(hm, Ui.dp(this, 20), hm, Ui.dp(this, 16));
        sec.setLayoutParams(slp);

        // Heading
        TextView h = new TextView(this);
        h.setText(getString(R.string.quick_tools_title));
        h.setTextSize(19);
        h.setTextColor(Ds.ink());
        Ds.heading(this, h);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(this, 12);
        // RTL: heading aligns right in Urdu
        if (isUrdu()) h.setGravity(Gravity.END);
        h.setLayoutParams(hlp);
        sec.addView(h);

        // Two cards side by side
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // v3.6.79: don't clip shadows (user: shadow cut at bottom).
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // Padding for shadow room on all sides.
        int shp = Ui.dp(this, 6);
        row.setPadding(shp, shp, shp, shp);

        View card1 = quickToolCard(
                R.drawable.icon_gauge,
                "#DDF5EF", // mint circle
                getString(R.string.quick_unit_check),
                getString(R.string.quick_unit_check_sub),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(HomeActivity.this,
                                MeterCheckActivity.class));
                    }
                });
        LinearLayout.LayoutParams c1lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c1lp.rightMargin = Ui.dp(this, 4);
        card1.setLayoutParams(c1lp);
        row.addView(card1);

        View card2 = quickToolCard(
                R.drawable.icon_document,
                "#E8E4F8", // lavender circle
                getString(R.string.quick_bill_history),
                getString(R.string.quick_bill_history_sub),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(HomeActivity.this,
                                BillsActivity.class));
                    }
                });
        LinearLayout.LayoutParams c2lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c2lp.leftMargin = Ui.dp(this, 4);
        card2.setLayoutParams(c2lp);
        row.addView(card2);

        sec.addView(row);
        return sec;
    }

    private View quickToolCard(int iconRes, String circleColor,
            String title, String sub, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card);
        int p = Ui.dp(this, 12);
        card.setPadding(p, p, p, p);
        // v3.6.77: 4dp elevation to match other cards (user: green shadow
        // on all four sides like other cards).
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            card.setElevation(Ui.dp(this, 4));
        }
        Ds.tealShadow(card);

        // Icon circle
        // v3.6.76: smaller icon (user: make room for full headings).
        FrameLayout circle = new FrameLayout(this);
        int cs = Ui.dp(this, 40);
        circle.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(Color.parseColor(circleColor));
        circle.setBackground(bg);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        int is = Ui.dp(this, 22);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                is, is, Gravity.CENTER);
        ic.setLayoutParams(ilp);
        circle.addView(ic);
        card.addView(circle);

        // Text (title + subtitle)
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 10);
        tlp.rightMargin = Ui.dp(this, 6);
        txt.setLayoutParams(tlp);

        TextView t = new TextView(this);
        t.setText(title);
        // v3.6.76: 12sp single-line complete (user: full heading, icons smaller).
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(Ds.ink());
        Ds.heading(this, t);
        txt.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11);
        // v3.6.76: subtitle allows 2 lines (user request).
        s.setMaxLines(2);
        s.setTextColor(Ds.muted());
        Ds.body(this, s, 400);
        txt.addView(s);
        card.addView(txt);

        // Chevron
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chev_right);
        chev.setColorFilter(Ds.muted(),
                android.graphics.PorterDuff.Mode.SRC_IN);
        int chs = Ui.dp(this, 18);
        chev.setLayoutParams(new LinearLayout.LayoutParams(chs, chs));
        // RTL: flip chevron in Urdu
        if (isUrdu()) chev.setRotation(180);
        card.addView(chev);

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(onClick);
        return card;
    }

    private boolean isUrdu() {
        return "ur".equals(LocaleHelper.get(this));
    }

    // ---- UNIT CHECK (v3.0.0 list row) ----

    /**
     * v3.6.0 — slim banner shown when notifications are disabled at
     * OS level. Honest platform limit: the app cannot force them
     * back on; this only suggests re-enabling so the Sunday
     * reminder can arrive. Never claims the reminder "cannot be
     * turned off". Dismissing hides it until the cooldown passes.
     */
    private View notifBannerView() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card);
        int p = Ui.dp(this, 16);
        card.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(getString(R.string.notif_off_title));
        title.setTextSize(14);
        title.setTextColor(Ds.ink());
        Ds.body(this, title, 700);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(title);
        TextView dismiss = new TextView(this);
        dismiss.setText("\u2715");
        dismiss.setTextSize(16);
        dismiss.setTextColor(Ds.muted());
        dismiss.setPadding(Ui.dp(this, 12), Ui.dp(this, 6),
                Ui.dp(this, 4), Ui.dp(this, 6));
        dismiss.setClickable(true);
        dismiss.setFocusable(true);
        dismiss.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (notifBanner != null) {
                    notifBanner.setVisibility(View.GONE);
                }
            }
        });
        top.addView(dismiss);
        card.addView(top);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.notif_off_text));
        sub.setTextSize(12.5f);
        sub.setTextColor(Ds.muted());
        Ds.body(this, sub, 400);
        card.addView(sub);
        card.addView(Ui.spacer(this, 8));

        Button open = Ds.outlineBtn(this,
                getString(R.string.notif_off_open), 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                            i.putExtra(
                                    Settings.EXTRA_APP_PACKAGE,
                                    getPackageName());
                            startActivity(i);
                        } catch (Exception ignored) {
                            // Settings screen unavailable: stay put.
                        }
                    }
                });
        card.addView(open);
        return card;
    }

    /** v3.6.0: show the banner at most once per 3 days. */
    private void refreshNotifBanner() {
        if (notifBanner == null) return;
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.areNotificationsEnabled()) {
                notifBanner.setVisibility(View.GONE);
                return;
            }
            SharedPreferences sp = getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE);
            long last = sp.getLong(KEY_NOTIF_PROMPT, 0);
            long now = System.currentTimeMillis();
            if (now - last < NOTIF_PROMPT_COOLDOWN_MS) {
                notifBanner.setVisibility(View.GONE);
                return;
            }
            sp.edit().putLong(KEY_NOTIF_PROMPT, now).apply();
            notifBanner.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
            notifBanner.setVisibility(View.GONE);
        }
    }

    /** v3.4.0: streak card view (visibility managed by refresh). */
    private View streakCardView() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card);
        int p = Ui.dp(this, 16);
        card.setPadding(p, Ui.dp(this, 13), p, Ui.dp(this, 13));
        card.setVisibility(View.GONE);

        streakCardText = new TextView(this);
        streakCardText.setTextSize(15);
        streakCardText.setTextColor(Ds.ink());
        Ds.body(this, streakCardText, 700);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        streakCardText.setLayoutParams(tlp);
        card.addView(streakCardText);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chev_right);
        int as = Ui.dp(this, 22);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                as, as);
        alp.leftMargin = Ui.dp(this, 10);
        arrow.setLayoutParams(alp);
        card.addView(arrow);

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String key = GuardStore.lastMeter(HomeActivity.this);
                if (key != null) {
                    String[] parts = key.split(":", 3);
                    if (parts.length == 3) {
                        GuardActivity.open(HomeActivity.this,
                                Disco.fromCode(parts[0]), parts[1],
                                parts[2], null);
                        return;
                    }
                }
                GuardActivity.open(HomeActivity.this,
                        meterCheckDisco, meterCheckMode,
                        meterCheckRef, null);
            }
        });
        return card;
    }

    /** v3.4.0: shows/hides the streak card from the last meter
     *  (v3.6.0: weekly streak). */
    private void refreshStreakCard() {
        if (streakCard == null || streakCardText == null) return;
        try {
            String key = GuardStore.lastMeter(this);
            int n = key == null ? 0
                    : GuardStore.streakCount(this, key);
            if (n > 0) {
                streakCardText.setText(
                        getString(R.string.streak_home_card, n));
                streakCard.setVisibility(View.VISIBLE);
            } else {
                streakCard.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {
            streakCard.setVisibility(View.GONE);
        }
    }


    // ---- MERE METER header + cards ----

    private View metersHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        // v3.6.45: RTL for Urdu (separate left/right properly).
        if (android.os.Build.VERSION.SDK_INT >= 17
                && LocaleHelper.isUrdu(this)) {
            h.setLayoutDirection(
                    android.view.View.LAYOUT_DIRECTION_RTL);
        }
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(Ui.dp(this, 20), Ui.dp(this, 18),
                Ui.dp(this, 20), 0);
        h.setLayoutParams(hlp);

        TextView l = new TextView(this);
        l.setText(getString(R.string.meters_title));
        l.setAllCaps(true);
        l.setTextSize(11.5f);
        l.setLetterSpacing(0.1f);
        l.setTextColor(Ds.faint());
        Ds.heading(this, l);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(l);

        TextView seeAll = new TextView(this);
        seeAll.setText(getString(R.string.see_all));
        seeAll.setTextSize(12.5f);
        seeAll.setTextColor(Ds.deepGreen());
        Ds.heading(this, seeAll);
        // v3.6.43: RTL support for Urdu text direction.
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            seeAll.setTextDirection(
                    android.view.View.TEXT_DIRECTION_LOCALE);
        }
        seeAll.setClickable(true);
        seeAll.setFocusable(true);
        seeAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this,
                        BillsActivity.class));
            }
        });
        h.addView(seeAll);
        return h;
    }

    private void refreshSavedList() {
        // v3.6.63: MERE METER removed from Home (user request).
        if (savedList == null) return;
        savedList.removeAllViews();
        final List<SavedMeters.Meter> items =
                SavedMeters.load(MeterUi.store(this));
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.meters_empty));
            empty.setTextSize(13.5f);
            empty.setTextColor(Ds.muted());
            empty.setLineSpacing(Ui.dp(this, 2), 1.55f);
            Ds.body(this, empty, 400);
            empty.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            savedList.addView(empty);
            return;
        }
        for (final SavedMeters.Meter m : items) {
            final Disco md = Disco.fromCode(m.disco);
            savedList.addView(MeterUi.card(this, m,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            openBill(md, m.idType, m.idValue);
                        }
                    },
                    new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            MeterUi.confirmDelete(HomeActivity.this,
                                    MeterUi.store(HomeActivity.this), m,
                                    new Runnable() {
                                        @Override public void run() {
                                            refreshSavedList();
                                        }
                                    });
                            return true;
                        }
                    }));
        }
    }


    // ------------------------------------------------------------------
    // Logic (unchanged)
    // ------------------------------------------------------------------

    /** Current value for the active tab: [mode, value-as-typed]. */
    private String[] currentEntry() {
        if (MODE_CID.equals(tabMode)) {
            return new String[]{MODE_CID,
                    stripSep(cidField.getText().toString())};
        }
        return new String[]{MODE_REF,
                stripSep(refField.getText().toString())};
    }

    /** v2.0.5: users copy references with spaces/dashes — drop them. */
    private static String stripSep(String s) {
        return s == null ? "" : s.trim().replaceAll("[\\s-]+", "");
    }

    private void onFetchClicked() {
        String[] e = currentEntry();
        String mode = e[0], value = e[1];
        // v3.6.82: empty → simple message (user: "Please enter your reference
        // number" only, nothing else).
        if (value == null || value.trim().isEmpty()) {
            showError(getString(R.string.ref_empty));
            return;
        }
        boolean ok = MODE_CID.equals(mode)
                ? LescoBillFetcher.isValidCustomerId(value)
                : LescoBillFetcher.isValidReference(value);
        if (!ok) {
            showError(getString(MODE_CID.equals(mode)
                    ? R.string.custid_invalid : R.string.ref_invalid));
            return;
        }
        hideError();
        // v2.1.0: remember for the Unit Check entry point.
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_MODE, mode)
                .putString(KEY_LAST_REF, value)
                .putString(KEY_LAST_DISCO, disco.code)
                .apply();
        openBill(disco, mode, value);
    }

    private void openBill(Disco d, String mode, String value) {
        Intent i = new Intent(this, BillActivity.class);
        i.putExtra(BillActivity.EXTRA_DISCO, d.code);
        i.putExtra(BillActivity.EXTRA_MODE, mode);
        i.putExtra(BillActivity.EXTRA_REF, value);
        startActivity(i);
    }

    /**
     * v2.1.0: shows UNIT CHECK when a cached (24h) bill exists — first
     * the last-fetched entry, then any saved meter. The cached raw HTML
     * is re-parsed by MeterCheckActivity itself.
     */
    private void refreshMeterCheckButton() {
        meterCheckMode = null;
        meterCheckRef = null;
        meterCheckDisco = null;
        SharedPreferences sp =
                getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String lm = sp.getString(KEY_LAST_MODE, null);
        String lr = sp.getString(KEY_LAST_REF, null);
        Disco ld = Disco.fromCode(sp.getString(KEY_LAST_DISCO, "lesco"));
        if (lm != null && lr != null
                && BillCache.getHtml(this, ld.code + ":" + lm + ":" + lr)
                        != null) {
            meterCheckMode = lm;
            meterCheckRef = lr;
            meterCheckDisco = ld;
        } else {
            for (SavedMeters.Meter m
                    : SavedMeters.load(MeterUi.store(this))) {
                Disco md = Disco.fromCode(m.disco);
                if (BillCache.getHtml(this,
                        md.code + ":" + m.idType + ":" + m.idValue)
                        != null) {
                    meterCheckMode = m.idType;
                    meterCheckRef = m.idValue;
                    meterCheckDisco = md;
                    break;
                }
            }
        }
        // v3.6.5: Unit Check button + Guard/What-if row moved to Bills tab —
        // visibility toggles removed (views no longer on Home).
    }

    /** v3.6.4: shared bottom nav (Ds.bottomNav) callbacks. */
    public void scrollToTop() {
        if (scrollView != null) scrollView.smoothScrollTo(0, 0);
    }

    /** v3.6.4: public wrapper so Ds.bottomNav can trigger meter check. */
    public void openMeterCheck() {
        onMeterCheckClicked();
    }

    private void onMeterCheckClicked() {
        if (meterCheckMode == null || meterCheckRef == null
                || meterCheckDisco == null) {
            android.widget.Toast.makeText(this, R.string.meter_no_bill,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, MeterCheckActivity.class);
        i.putExtra(MeterCheckActivity.EXTRA_DISCO, meterCheckDisco.code);
        i.putExtra(MeterCheckActivity.EXTRA_MODE, meterCheckMode);
        i.putExtra(MeterCheckActivity.EXTRA_REF, meterCheckRef);
        startActivity(i);
    }

    private void showError(String msg) {
        errorView.setText(msg);
        Ds.body(this, errorView, 600);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }
}
