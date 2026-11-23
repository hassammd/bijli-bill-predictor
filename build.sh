#!/bin/bash
# Bijli Bill v3.7.54 (186) — FIXES-ONLY build (user order, 2026-11-24:
# "koi bhi issue nahi ana chahiye"):
#   - CRASH FIX (live v161 crash, 1 user): LescoBillFetcher
#     .splitReference validates by THROWING IllegalArgumentException,
#     and both callers let it escape — fetchBillPageWith (declares
#     only IOException) and browserFallbackUrl (catches nothing).
#     Now fetchBillPageWith converts it to FetchException
#     (INVALID_REF), fetchByCustomerIdWith does the same for a bad
#     customer ID, and browserFallbackUrl NEVER throws (malformed
#     ref -> portal landing page). BillActivity shows the friendly
#     "reference number looks wrong" message for INVALID_REF on
#     both fetch paths. No fetch entry point can crash on a bad
#     number anymore.
#   - PORTRAIT FIX (Play blocked the 185 submission):
#     <uses-feature android.hardware.screen.portrait
#     android:required="false" /> in the manifest — the portrait
#     screenOrientation on all 11 activities stays, phones stay
#     portrait, Play no longer treats it as a required feature.
#   - NON-PROTECTED FIX (display-only, totals unchanged): the
#     category classifier now strips separators before matching,
#     so "Non-Protected" AND odd variants ("Non - Protected",
#     "Non—Protected") map to NON_PROTECTED in MeterCheckCalc
#     and BillMirrorCalc.
#   - NO ads change: TEST AdMob IDs stay (real IDs = later build).
# Bijli Bill v3.7.53 (185) — user order, 2026-11-23:
#   - ROUNDED SPLASH ICON: splash_icon.png corners rounded (~23.5%
#     radius, transparent) to match the launcher squircle; launcher
#     mipmap icons stay SQUARE (the launcher masks them itself).
# Bijli Bill v3.7.52 (184) — user order, 2026-11-23:
#   - BIGGER LAUNCHER ICON: artwork enlarged ~1.15x from the 2048
#     original (downscaled, no blur); all mipmap densities + the
#     432 adaptive foreground + splash icon replaced.
#   - SPLASH REDESIGN: illustration band removed; centered new icon
#     120dp + title + tagline, fade/scale entrance animation,
#     slim progress indicator near the bottom; ~1500ms.
#   - LAUNCH POPUP REMOVED: "Are you outside Pakistan?" dialog no
#     longer appears on app open (HomeActivity call + methods gone).
# Bijli Bill v3.7.51 (183) — user order, 2026-11-23, ICON-ONLY build:
#   - PENDING BILLS rows show the CONSUMER NAME (user: "jo bill
#     pending hain unki thodi si detail bhi likho, BY NAME"):
#     name first on each row, from the saved meter's holder name
#     (same source the Bills list shows); no real name stored ->
#     no name line (ref line stays); name never invented.
#   - PENDING BILLS cards SAME WIDTH as the Bills list (user:
#     "jitni un cards ki width hai, utni hi in cards ki width
#     rakho... same list aani chahiye"): shared listCardMargins
#     in MeterUi (0 side, 0 top, 12 bottom) used by MeterUi.card
#     AND PendingBillsActivity — both lists share the same 20dp
#     list padding, so both cards are screen - 40dp wide.
#   - v3.7.49 INTACT: category chip, MDI meter-table parse,
#     dues (arrears) chip untouched.
# Bijli Bill v3.7.49 (181) — user order, 2026-11-22, ONE build:
#   - CATEGORY CHIP on the bill detail (user: "Arrears KO remove na
#     krna or category show krwao"): a COMMERCIAL / DOMESTIC pill
#     sits BESIDE the dues chip; the NO ARREARS / PICHLA BAQAYA chip
#     is untouched. Source: the bill's "TARIFF CAT" field (parser
#     now accepts "tariff cat" as well as "tariff category").
#     Unknown category -> no chip (never invented).
#   - COMMERCIAL (MDI) BILL PARSE: the MDI bill prints readings as
#     a METER INFO table (METER | TYPE | PREVIOUS | PRESENT | MF |
#     UNITS), not domestic label-rows — so meter reading + units
#     were missing (live case: Abdul Qadeer Sheikh, ref
#     44115130438868, OCT 26, Rs 121,207). New parseMeterInfoTable
#     reads the KWH row: prev "47403 / 12213", curr "48288 /
#     12732", units 1404 (885 + 519). Domestic parsing untouched.
#   - DUE-DATE FINDING (verified, no code change): the MDI bill's
#     due date (16 NOV 26) DOES parse — the due chip hides only
#     because the date is past (chip guard: days >= 0).
# Bijli Bill v3.7.48 (180) — user order, 2026-11-22, ONE build:
#   - "ESTIMATE VS ACTUAL BILL" CARD REMOVED from the bill detail
#     screen (user: "ye box Q show ho raha hai, isko remove kro,
#     ye ni chye"). The v3.3.0 card compared the meter's pending
#     Unit Check estimate against the fresh bill (e.g. estimate
#     5 units / Rs 257.11 vs actual 130 units / Rs 6,953 ->
#     Accuracy 3.7%, +125 units) — it showed whenever a pending
#     estimate existed for that meter. Now the wiring is gone:
#     addAccuracyCard + accuracyCardView + accLine + fmt helpers
#     removed from BillActivity; the card can never render, in
#     any case. AccuracyTracker itself is untouched (Unit Check
#     still saves pending estimates; they simply are never
#     consumed into a card anymore) — no risky cleanup.
# Bijli Bill v3.7.47 (179) — user order, 2026-11-22, ONE build:
#   - UNIT CHECK LAST-BILL FIX: "Bill reading: 820" was stuck —
#     the last-bill pointer (metercheck_last_*) was written ONLY
#     by the Home form submit, so bills opened from the Bills tab
#     left Unit Check on the first bill ever fetched. Now
#     BillActivity.showBillPage updates the pointer on EVERY
#     successful bill display (cache hit, fresh fetch, CAPTCHA
#     fetch), and the fallback picks the MOST RECENTLY saved
#     meter with a live cache (pickFallbackMeter, by savedAt).
#     MeterCheckActivity re-parses the cached HTML, so the rates
#     (category, taxes, GST, FPA) follow the same last bill.
#   - JURMANA RULE (user-confirmed, formula UNCHANGED): the Unit
#     Check estimate NEVER includes arrears / LP surcharge —
#     regression test proves it (unpaid bill with arrears 4,637
#     + LPS 159 estimates identically to the same bill with
#     arrears/LPS zeroed).
# Bijli Bill v3.7.46 (178) — user order, 2026-11-22, ONE build:
#   - STAMP SMALLER: 72dp -> 56dp wide (user: still too big).
#   - STATUS TEXT SMALLER: "Paid on 7 Nov 26" / "Unpaid" 12sp
#     -> 10sp, tick icon 11dp -> 9dp.
#   - RIGHT EDGE FLUSH: status column (stamp + status) now sits
#     flush against the card's right edge — FlowLayout gained a
#     per-child alignEnd flag (FlowMath.endAlignedLeft): no dead
#     gap on the right, whether the column shares the line with
#     the amount or wrapped onto its own line. Wrap preserved:
#     Rs 1,25,000 -> 2 lines; Rs 2,229 / Rs 19,615 -> 1 line.
# Bijli Bill v3.7.45 (177) — user orders, voice call 2026-11-22, ONE build:
#   - REAL STAMP IMAGES: user-sent green BILL PAID / red BILL UNPAID
#     PNGs (res/drawable-nodpi/stamp_paid_img.png /
#     stamp_unpaid_img.png) replace the v3.7.42 text stamp in
#     paidStamp(); artwork already tilted, no extra rotation.
#   - BADGE DATE SHORT: "Paid on 17 Nov 26" — "Paid on" complete +
#     green tick, only the date is 2-digit year.
#   - BIG-AMOUNT GUARD: amount row is now a FlowLayout — badge +
#     stamp wrap to a second line instead of clipping at thousands.
#   - SETTINGS TOP ROWS: Rate Us, Share App, Privacy Policy — all
#     above "How it works" (lower Settings card removed).
# Bijli Bill v3.7.43 (175) — Total Payable improvements: per-bill
# due date + days-left, tap card -> pending-bills list, Overdue red
# mark + after-due amount, all-paid green tick (user order, voice
# call 2026-11-21, "sahi karo aisa hi karo").
#   - QUICK TOOLS on the company form page: Unit Check + Bill
#     History below VIEW BILL (reference input ke neeche), above
#     the ad banner. Home keeps only greeting + Total Payable +
#     company grid. (v3.7.41 had them on Home; the up-front
#     variant was ordered then cancelled the same call.)
#   - STAMPS on bill detail: round tilted stamp next to the badge
#     — red PAID when paid, amber UNPAID when not paid (same as
#     the stamp printed on the bill document). Badge stays.
#   - SPLASH BLUE: splash illustration recolored teal -> blue
#     (#1B62B0 family); launcher icon NOT changed (separate pick).
#   - SETTINGS: Share App + Privacy Policy moved to the very top
#     of the Settings page, before "How it works"; Rate Us stays
#     in the Settings card below.
# Bijli Bill v3.7.41 (173) — live-bug fixes (user order, voice call
# 2026-11-21, screenshots):
#   - PAID STATUS BUG (critical): v3.7.40's badge read the bill's
#     own paid fields only through label-row/table harvesters, but
#     the live bill's paid box lives in the slip section — so the
#     NOV 26 bill (AMOUNT PAID 2,229 + PAYMENT DATE 17-Nov-26 +
#     red PAID stamp) still showed Unpaid, and Total Payable
#     counted the paid Rs 2,229 as pending. Fix: structure-agnostic
#     parsePaidBox text-stream scan (label+value same line or next
#     line, PAID stamp as corroboration); paidFieldsChecked is set
#     ONLY when a paid marker is positively located — "not found"
#     now means UNKNOWN (no badge, NEVER Unpaid), never a false
#     Unpaid. History fallback kept for old saved bills.
#   - ONE BLUE: Total Payable card is now solid #1B62B0 (was a
#     #21507E->#1B62B0 gradient) — same as the language toggle
#     pill and the header.
#   - BOX SHADOW: elevation + blue shadow removed from ALL cards
#     (home grid, Total Payable, form, bill detail, viewer,
#     meter rows, Unit Check hero); buttons keep theirs. More
#     spacing between cards (grid gap 8->12dp, card margins up).
#   - QUICK TOOLS BACK on Home below the company grid: Unit Check
#     + Bill History (v3.7.40 had removed them). Rate Us stays in
#     Settings.
# Bijli Bill v3.7.40 (172) — Paid badge from the BILL's own fields +
# company-cards home grid (user orders, 2026-11-21):
#   - Paid/Unpaid badge now reads the bill's own AMOUNT PAID +
#     PAYMENT DATE fields (LescoBillFetcher labels). CORRECTS v3.7.38:
#     the history table was the wrong source — the bill document
#     itself prints the payment date (user's NOV 26 LESCO bill:
#     AMOUNT PAID 2,229, PAYMENT DATE 17-Nov-26). History resolve
#     kept only as fallback for old saved bills; date never invented.
#   - Home redesign: companies carousel + Quick Tools + home input
#     card gone. After splash: greeting + blue gradient Total
#     Payable card (saved bills, PAID excluded) + "Select Your
#     Company" 11-card grid (v3.7.39 logos, tap -> that company's
#     input form). Bottom nav unchanged.
#   - Colour pass (user: white + light slatey, blue primary; yellow
#     only for small highlights): cards #EFF3F2, ref/cust-id segment
#     + lang pill blue, due chip light blue; dead yellow chip/segment
#     drawables deleted.
#   - Kept: bill fetch, CAPTCHA/session-out fixes, reminders,
#     tariff code, DISCO logos, Settings. Launcher icon NOT changed.
# Bijli Bill v3.7.39 (171) — official DISCO logos in picker + TESCO/HAZECO
# + Rate Us moved to Settings (user order, 2026-11-21):
#   - Blue theme: the old green palette is gone everywhere (Ds/Ui
#     central colors, colors.xml, styles, drawables, icon tints) —
#     sampled competitor blue: primary #1B62B0, mid #165598, deep
#     #21507E, bg #F4F8F7. Launcher icon NOT changed (separate pick
#     still pending).
#   - Paid/Unpaid badge on the bill detail screen, right after the
#     amount (PaidStatus on the PITC history table; "Paid"/"Unpaid"
#     text only — PITC prints no payment date, never invented;
#     UNKNOWN -> no badge).
#   - Rate Us card leaves Home Quick Tools; Settings (About) gains
#     Rate Us / Share App / Privacy Policy rows; About content is
#     now scrollable (new card no longer fits one screen).
#   - Kept: bill fetch, CAPTCHA/session-out fixes, reminders,
#     tariff code. No AI voice feature, no company-cards home flow.
# Bijli Bill v3.7.37 (169) — voice feature REMOVED entirely
# (user order, voice call 2026-11-21: "AI wala kaam saara remove,
# bubble wala hata do, wo nahi chahiye").
#   - Floating bubble (HomeActivity) gone; voice screen, on-device
#     brain, Live client, API-key file and bill snapshots deleted;
#     RECORD_AUDIO permission gone; all voice-feature strings
#     (EN + UR) and drawables gone. No API key remains in the app.
#   - Kept: Rate Us card (restored in v3.7.36), Unit Check, Bill
#     History, bill fetch, CAPTCHA/session-out fixes, reminders,
#     tariff code. Nothing else changes.
# Bijli Bill v3.7.36 (168) — Rate Us card restored (user correction)
# v3.7.36 (user, 2026-11-21): v3.7.35 removed the whole Quick Tools
# second row by mistake. Rate Us card is back as a full-width second
# row (v3.7.34 style: star icon, Play Store listing via market://
# with web fallback).
# Bijli Bill v3.7.30 (162) — in-app PITC image CAPTCHA (Tareeqa 1):
#   - LescoBillFetcher stops at the form GET when the page carries a
#     CAPTCHA, throws CaptchaRequiredException with the live session
#     (same cookie jar + tokens + downloaded picture); BillActivity
#     shows the picture + code box in-app; fetchWithCaptcha() POSTs
#     the same form PLUS the code field (field name + image URL
#     parsed from the form HTML, never hardcoded).
#   - A server "Invalid CAPTCHA code" rejection now throws
#     CaptchaRejectedException and shows "CAPTCHA ghalat hai" — it
#     NEVER becomes the misleading "reference number looks wrong".
#   - PITC is geo-blocked from this VM: the live fetch is NOT tested
#     here; headless tests (TestBillFetch) cover the whole flow with
#     a scripted transport. User tests on their own phone.
# Bijli Bill v3.6.0 — ZERO-INPUT GUARD + SUNDAY REMINDER build (manual APK + AAB, no Gradle).
# v3.6.0 changes:
#   - Guard works with ZERO input: auto-projection from the last
#     fetched bill's pace (labeled as andaza); real readings win.
#   - Sunday reminder strictly always-on (~10:00, no toggle anywhere);
#     weekly reading nudge on every fire; meter fallback to first
#     saved meter; BigTextStyle.
#   - Streak is now WEEKLY (4w/12w milestones); gentle 3-day-cooldown
#     banner when OS notifications are off (opens OS settings).
#   - versionCode 32, versionName 3.6.0. Release-signed (upload key).
#   - AdMob STILL on Google TEST IDs — swap when user sends real IDs.
# v3.5.0 changes:
#   - targetSdkVersion 34 -> 36, compileSdk android-36 (Play requires
#     API 36+ for new apps since 2026-08-31).
#   - Edge-to-edge insets handling in BaseActivity (API 35+).
#   - Signed with the RELEASE upload keystore (release/); passwords are
#     read from release/keystore-passwords.txt (0600) at build time.
#   - versionCode 31, versionName 3.5.0 (superseded by 3.6.0).
#   - AdMob STILL on Google TEST IDs — swap before/after user sends the
#     3 real IDs (small follow-up build).
#
# v2.5.0 changes:
#   - MULTI-DISCO: LESCO/IESCO/GEPCO/FESCO/MEPCO/PESCO/HESCO/SEPCO/QESCO
#     fetch through the shared PITC portal template (K-Electric =
#     coming soon). Company chips on home, cache keys disco-scoped.
#   - MERE METER: every successful bill fetch auto-saves
#     (disco, id, holder name); tap = re-fetch, long-press = delete.
#     Legacy manual saves migrate once. Manual name SAVE removed.
#   - METER CHECK renamed to UNIT CHECK everywhere (یونٹ چیک).
#   - Default language ENGLISH (saved choice still honored).
#   - Splash re-centered (true Gravity.CENTER, no weights).
#   - UI polish: 20/16/14/12sp type scale, 12dp card corners,
#     48dp touch targets, blue+amber chips.
#   - Customer-ID search wired per disco (best-effort, NOT
#     live-verified — PITC is geo-blocked outside Pakistan).
# v2.4.0 changes:
# v2.3.0 changes:
#   - Meter Check: AUTO frame pick — the app itself picks the sharpest
#     frame (FramePrep.sharpness + KwhAnchorPicker.bestFrameIndex);
#     the confirm screen opens DIRECTLY with "Reading parhi ja rahi
#     hai…" while OCR runs. No manual frame selection anywhere.
#   - "Ye ANDAZA kyun hai?" — tappable plain-words explainer under the
#     meter-check estimate.
#   - REPORT BHEJO removed from every visible Bill screen.
#   - PRINT replaced by direct PDF DOWNLOAD (PrintedPdfDocument,
#     Downloads/BijliBill, then opens the PDF).
#   - Rich bill-details card (Sarif / Reading / Raqam / Charges) above
#     the full bill WebView.
#   - Due-date reminder: local notification 2 days before the due date
#     at 09:00 (AlarmManager + ReminderReceiver).
#   - Hand-drawn 12-month bill-history chart with UNITS/RS toggle.
# Unchanged: official PITC fetch chain, HTTPS-first, 24h cache,
# BROWSER MEIN KHOLO, meter base = bill's present reading, gallery
# upload, 60s/150MB guards, kWh-anchor OCR, EN/Urdu, TEST AdMob,
# debug signature.
# OCR still uses the ML Kit THIN client
# (play-services-mlkit-text-recognition via Google Play Services).
# AARs are resolved once by build/mlkit/resolve_mlkit.py and extracted once
# by build/mlkit/extract_mlkit.py into build/mlkit/aar-{libs,res,manifests}/
# (PERSISTED — not wiped below, same pattern as neon-memory's ads AARs).
# Library R classes are generated per-build by build/gen_r.py.
set -e
export JAVA_HOME="$HOME/workspace/jdk/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/android-sdk"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
BT="$SDK/build-tools/34.0.0"
BUNDLETOOL="$HOME/workspace/bundletool.jar"
PROJ="$HOME/workspace/apk-build/lesco-bill"
OUTDIR="$HOME/workspace/your_files/bijli-bill-app"
cd "$PROJ"
# NOTE: build/mlkit/aar-{repo,libs,res,manifests} are intentionally kept.
rm -rf build/classes build/dex build/gen build/gen-lib build/R.txt \
       build/aar-res-zip build/mod build/app.aab build/signed.aab \
       build/proto.apk build/base.apk build/aligned.apk build/final.apk \
       build/gen-proto build/test-classes build/univ build/univ.apks \
       build/res.zip build/R-proto.txt build/rjava.list
mkdir -p build/classes build/dex build/gen build/gen-lib build/aar-res-zip

echo "== keystore (release upload key; debug fallback removed for publish) =="
PW_FILE="$PROJ/release/keystore-passwords.txt"
if [ ! -f "$PROJ/release/bijli-bill-release.keystore" ] || [ ! -f "$PW_FILE" ]; then
  echo "FATAL: release keystore or $PW_FILE missing."
  exit 1
fi
KS="$PROJ/release/bijli-bill-release.keystore"
KS_ALIAS="$(grep '^alias=' "$PW_FILE" | cut -d= -f2)"
KS_PASS="$(grep '^keystore_password=' "$PW_FILE" | cut -d= -f2-)"
if [ -z "$KS_ALIAS" ] || [ -z "$KS_PASS" ]; then
  echo "FATAL: could not parse alias/password from $PW_FILE"
  exit 1
fi
chmod 600 "$PROJ/release/bijli-bill-release.keystore" "$PW_FILE"

echo "== ML Kit AARs present? =="
if [ ! -d build/mlkit/aar-libs ] || \
   [ -z "$(ls build/mlkit/aar-libs/*.jar 2>/dev/null)" ]; then
  echo "FATAL: build/mlkit/aar-libs missing — run:"
  echo "  python3 build/mlkit/resolve_mlkit.py && python3 build/mlkit/extract_mlkit.py"
  exit 1
fi
echo "  $(ls build/mlkit/aar-libs/*.jar | wc -l) AAR jars"

echo "== unit tests =="
javac -encoding UTF-8 -nowarn -d build/test-classes \
  -cp "$ANDROID_JAR" \
  java/com/hatch/bijlibill/ArrearsStatus.java \
  java/com/hatch/bijlibill/DailyReminder.java \
  java/com/hatch/bijlibill/AccuracyTracker.java \
  java/com/hatch/bijlibill/LescoBillFetcher.java \
  java/com/hatch/bijlibill/BillWebRenderer.java \
  java/com/hatch/bijlibill/BillPrinter.java \
  java/com/hatch/bijlibill/Tariff.java \
  java/com/hatch/bijlibill/MeterCheckCalc.java \
  java/com/hatch/bijlibill/BillMirrorCalc.java \
  java/com/hatch/bijlibill/KwhAnchorPicker.java \
  java/com/hatch/bijlibill/FramePrep.java \
  java/com/hatch/bijlibill/GuardCalc.java \
  java/com/hatch/bijlibill/GuardReminder.java \
  java/com/hatch/bijlibill/GuardStore.java \
  java/com/hatch/bijlibill/BillReminder.java \
  java/com/hatch/bijlibill/BillSummary.java \
  java/com/hatch/bijlibill/BillPdf.java \
  java/com/hatch/bijlibill/Disco.java \
  java/com/hatch/bijlibill/SavedMeters.java \
  java/com/hatch/bijlibill/LangPolicy.java \
  java/com/hatch/bijlibill/PaidStatus.java \
  java/com/hatch/bijlibill/FlowLayout.java \
  java/com/hatch/bijlibill/FlowMath.java \
  java/com/hatch/bijlibill/TotalPayable.java \
  java/com/hatch/bijlibill/LastBillStore.java \
  java/com/hatch/bijlibill/StreakCalc.java \
  java/com/hatch/bijlibill/BillDayNudge.java \
  java/com/hatch/bijlibill/WeeklyReport.java \
  java/com/hatch/bijlibill/WeeklyReportAlarm.java \
  TestBillFetch.java
java -cp build/test-classes -Duser.home=/home/hatch TestBillFetch

echo "== aapt2 compile (app res) =="
"$BT/aapt2" compile --dir res -o build/res.zip

echo "== aapt2 compile (ML Kit AAR res dirs) =="
i=0
for d in build/mlkit/aar-res/*/; do
  [ -d "$d" ] || continue
  n=$(basename "$d")
  "$BT/aapt2" compile --dir "$d" -o "build/aar-res-zip/$n.zip" 2>/dev/null || \
    echo "  (skip, empty: $n)"
  i=$((i+1))
done
echo "  compiled $i AAR res dirs"

echo "== aapt2 link (APK) =="
"$BT/aapt2" link -o build/base.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java build/gen \
  --output-text-symbols build/R.txt \
  -A assets build/res.zip build/aar-res-zip/*.zip

echo "== generate library R.java =="
python3 build/gen_r.py

echo "== javac (app + library R classes + ML Kit) =="
find build/gen-lib -name "R.java" > build/rjava.list
wc -l build/rjava.list
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$ANDROID_JAR:build/mlkit/aar-libs/*" -d build/classes \
  build/gen/com/hatch/bijlibill/R.java \
  @build/rjava.list \
  java/com/hatch/bijlibill/*.java

echo "== d8 (app + AAR classes) =="
"$BT/d8" --min-api 24 --output build/dex \
  $(find build/classes -name "*.class") \
  build/mlkit/aar-libs/*.jar
ls build/dex/

echo "== add dex files (APK, multidex-aware) =="
(cd build/dex && zip -q ../base.apk classes*.dex)

echo "== zipalign =="
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

echo "== apksigner (APK, release key) =="
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --out build/final.apk build/aligned.apk

echo "== AAB: aapt2 link (proto) =="
"$BT/aapt2" link --proto-format -o build/proto.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java build/gen-proto \
  --output-text-symbols build/R-proto.txt \
  -A assets build/res.zip build/aar-res-zip/*.zip

echo "== AAB: repack into bundletool module layout =="
rm -rf build/mod && mkdir -p build/mod/base/manifest build/mod/base/dex
unzip -q -o build/proto.apk -d build/mod/tmp
mv build/mod/tmp/AndroidManifest.xml build/mod/base/manifest/
[ -f build/mod/tmp/resources.pb ] && mv build/mod/tmp/resources.pb build/mod/base/
[ -d build/mod/tmp/res ] && mv build/mod/tmp/res build/mod/base/
[ -d build/mod/tmp/assets ] && mv build/mod/tmp/assets build/mod/base/
cp build/dex/classes*.dex build/mod/base/dex/
(cd build/mod/base && zip -q -r ../base.zip .)

echo "== AAB: bundletool build-bundle =="
java -jar "$BUNDLETOOL" build-bundle --modules=build/mod/base.zip \
  --config="$PROJ/BundleConfig.json" \
  --output=build/app.aab

echo "== AAB: jarsigner (release key) =="
jarsigner -keystore "$KS" -storepass "$KS_PASS" \
  -signedjar build/signed.aab build/app.aab "$KS_ALIAS" 2>&1 | grep -v "^$" | head -3

echo "== AAB: validate =="
java -jar "$BUNDLETOOL" validate --bundle=build/signed.aab

echo "== AAB: verify signature =="
jarsigner -verify build/signed.aab | head -2

echo "== verify APK =="
"$BT/apksigner" verify --print-certs build/final.apk | head -6
"$BT/aapt2" dump badging build/final.apk | grep -E "package:|launchable-activity:|application-label:"

echo "== manifest permissions =="
"$BT/aapt2" dump xmltree build/final.apk --file AndroidManifest.xml 2>/dev/null \
  | grep -o "android.permission.[A-Z_]*" | sort -u

echo "== ML Kit classes in APK =="
unzip -l build/final.apk | grep -c "classes.*dex"
unzip -l build/final.apk | grep -m2 "classes" || true

echo "== AAB manifest =="
for xp in "/manifest/@package" "/manifest/@android:versionCode" "/manifest/@android:versionName"; do
  java -jar "$BUNDLETOOL" dump manifest --bundle=build/signed.aab --xpath="$xp"
done

echo "== AAB: universal APK proof =="
rm -rf build/univ && mkdir -p build/univ
java -jar "$BUNDLETOOL" build-apks --bundle=build/signed.aab \
  --output=build/univ.apks --mode=universal --ks="$KS" \
  --ks-key-alias="$KS_ALIAS" \
  --ks-pass="pass:$KS_PASS" >/dev/null
(cd build/univ && unzip -q -o ../univ.apks universal.apk)
"$BT/aapt2" dump badging build/univ/universal.apk | grep -E "^package:"
"$BT/apksigner" verify build/univ/universal.apk

mkdir -p "$OUTDIR"
cp build/final.apk "$OUTDIR/bijli-bill-v3.7.54.apk"
cp build/signed.aab "$OUTDIR/bijli-bill-publish-3.7.54.aab"
# v3.7.34: fail loudly if either output is missing — a silent
# missing AAB must never reach delivery again.
for f in "$OUTDIR/bijli-bill-v3.7.54.apk" \
        "$OUTDIR/bijli-bill-publish-3.7.54.aab"; do
    if [ ! -s "$f" ]; then
        echo "BUILD FAILED: missing output $f" >&2
        exit 1
    fi
done
ls -la "$OUTDIR/bijli-bill-v3.7.54.apk" "$OUTDIR/bijli-bill-publish-3.7.54.aab"
echo BUILD_OK
