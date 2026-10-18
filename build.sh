#!/bin/bash
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
#     48dp touch targets, teal+amber chips.
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
  java/com/hatch/bijlibill/BillReminder.java \
  java/com/hatch/bijlibill/BillSummary.java \
  java/com/hatch/bijlibill/BillPdf.java \
  java/com/hatch/bijlibill/Disco.java \
  java/com/hatch/bijlibill/SavedMeters.java \
  java/com/hatch/bijlibill/LangPolicy.java \
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
cp build/final.apk "$OUTDIR/bijli-bill-calculator-v3.6.0.apk"
cp build/signed.aab "$OUTDIR/bijli-bill-publish-3.6.0.aab"
ls -la "$OUTDIR/bijli-bill-calculator-v3.6.0.apk" "$OUTDIR/bijli-bill-publish-3.6.0.aab"
echo BUILD_OK
