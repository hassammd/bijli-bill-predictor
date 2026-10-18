#!/bin/bash
# verify-release.sh — Post-build verification checklist for Bijli Bill releases.
#
# Standing user boundary (2026-10-11): "Bina is checklist ke tumhe koi file
# nahi milegi." Every AAB delivery must arrive with this checklist:
#   version code/name, file size, target SDK, certificate, new changes included.
#
# Usage:
#   ./verify-release.sh [path-to-signed-aab] [marker-string ...]
#
#   path-to-signed-aab : default build/signed.aab (fresh build output)
#   marker-string ...  : one or more strings that MUST be present in the
#                        compiled artifact (the "new changes included" proof).
#                        Name them explicitly per release, e.g.:
#     ./verify-release.sh your_files/bijli-bill-app/bijli-bill-publish-3.6.2.aab \
#         "nepra.org.pk" "Official Sources & Disclaimer"
#
# Exits 0 only if EVERY check passes; prints the checklist block for the user.
set -u

JAVA_HOME="/home/hatch/workspace/jdk/jdk-17.0.20.1+1"
JAVA="$JAVA_HOME/bin/java"
KEYTOOL="$JAVA_HOME/bin/keytool"
PROJ="/home/hatch/workspace/apk-build/lesco-bill"
BUNDLETOOL="/home/hatch/workspace/bundletool.jar"
PW_FILE="$PROJ/release/keystore-passwords.txt"
KEYSTORE="$PROJ/release/bijli-bill-release.keystore"
SRC_MANIFEST="$PROJ/AndroidManifest.xml"

AAB="${1:-$PROJ/build/signed.aab}"
shift || true
MARKERS=("$@")

PASS=0; FAIL=0
ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
note() { echo "  … $1"; }

echo "════════════════════════════════════════════════════════════"
echo " VERIFICATION CHECKLIST — $(basename "$AAB")"
echo "════════════════════════════════════════════════════════════"

# 1. File exists + size
if [ -f "$AAB" ]; then
  BYTES=$(stat -c%s "$AAB")
  MB=$(awk "BEGIN{printf \"%.2f\", $BYTES/1048576}")
  ok "File size: $BYTES bytes ($MB MB)"
else
  bad "AAB not found: $AAB"; BYTES=0
fi

# 2-4. Manifest values from the compiled AAB (bundletool = ground truth)
if [ -f "$AAB" ]; then
  VC=$("$JAVA" -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" \
       --xpath=/manifest/@android:versionCode 2>/dev/null)
  VN=$("$JAVA" -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" \
       --xpath=/manifest/@android:versionName 2>/dev/null)
  TSDK=$("$JAVA" -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" \
       --xpath=/manifest/uses-sdk/@android:targetSdkVersion 2>/dev/null)
  PKG=$("$JAVA" -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" \
       --xpath=/manifest/@package 2>/dev/null)
  [ -n "$VC" ]   && ok "versionCode (AAB): $VC"        || bad "versionCode unreadable"
  [ -n "$VN" ]   && ok "versionName (AAB): $VN"        || bad "versionName unreadable"
  [ -n "$TSDK" ] && ok "targetSdkVersion (AAB): $TSDK" || bad "targetSdkVersion unreadable"
  [ -n "$PKG" ]  && ok "package (AAB): $PKG"           || bad "package unreadable"
  if [ "$TSDK" = "36" ]; then
    ok "Target SDK 36 — meets Play's API 36+ requirement"
  elif [ -n "$TSDK" ]; then
    bad "Target SDK $TSDK — Play requires 36+ for updates"
  fi
else
  VC=""; VN=""; TSDK=""; PKG=""
fi

# 5. AAB version matches THIS source tree's AndroidManifest.xml
#    (catches the stale-source-dir class of mistake, e.g. the 2026-10-11
#    v3.6.1 838KB build that came from the old bijli-bill/ dir)
if [ -f "$SRC_MANIFEST" ] && [ -n "$VC" ]; then
  SVC=$(grep -o 'android:versionCode="[0-9]*"' "$SRC_MANIFEST" | grep -o '[0-9]*')
  SVN=$(grep -o 'android:versionName="[^"]*"' "$SRC_MANIFEST" | cut -d'"' -f2)
  if [ "$VC" = "$SVC" ] && [ "$VN" = "$SVN" ]; then
    ok "AAB matches source manifest (lesco-bill/): $SVC ($SVN) — correct source dir"
  else
    bad "AAB ($VC/$VN) ≠ source manifest ($SVC/$SVN) — WRONG SOURCE DIR BUILD?"
  fi
else
  note "source-manifest comparison skipped"
fi

# 6. Certificate: AAB must be signed by the release upload key
if [ -f "$AAB" ] && [ -f "$KEYSTORE" ] && [ -f "$PW_FILE" ]; then
  KS_ALIAS="$(grep '^alias=' "$PW_FILE" | cut -d= -f2)"
  KS_PASS="$(grep '^keystore_password=' "$PW_FILE" | cut -d= -f2-)"
  KS_FP=$("$KEYTOOL" -list -v -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
          -storepass "$KS_PASS" 2>/dev/null | grep "SHA256:" | tr -d ' \t')
  AAB_FP=$("$KEYTOOL" -printcert -jarfile "$AAB" 2>/dev/null | grep "SHA256:" | tr -d ' \t')
  if [ -n "$KS_FP" ] && [ "$KS_FP" = "$AAB_FP" ]; then
    ok "Certificate: signed with the release upload key (${KS_FP#SHA256:})"
  else
    bad "Certificate MISMATCH — AAB not signed by the release upload key"
    note "keystore: ${KS_FP:-unreadable}"
    note "aab:      ${AAB_FP:-unreadable}"
  fi
else
  bad "certificate check skipped (keystore/password file/AAB missing)"
fi

# 7. New changes included: marker strings present in the COMPILED artifact
if [ ${#MARKERS[@]} -eq 0 ]; then
  note "no marker strings given — 'new changes included' NOT verified (pass markers explicitly)"
elif [ -f "$AAB" ]; then
  RES_TMP=$(mktemp -d)
  unzip -q -p "$AAB" base/resources.pb > "$RES_TMP/resources.pb" 2>/dev/null
  for m in "${MARKERS[@]}"; do
    if strings "$RES_TMP/resources.pb" 2>/dev/null | grep -q -F "$m"; then
      ok "New change present in compiled AAB: \"$m\""
    else
      bad "Marker NOT found in compiled AAB: \"$m\""
    fi
  done
  rm -rf "$RES_TMP"
fi

# 8. bundletool validate (final sanity gate)
if [ -f "$AAB" ]; then
  if "$JAVA" -jar "$BUNDLETOOL" validate --bundle="$AAB" >/dev/null 2>&1; then
    ok "bundletool validate: passed"
  else
    bad "bundletool validate: FAILED"
  fi
fi

echo "────────────────────────────────────────────────────────────"
if [ "$FAIL" -eq 0 ]; then
  echo " RESULT: VERIFIED_OK ($PASS checks passed)"
  exit 0
else
  echo " RESULT: VERIFICATION_FAILED ($FAIL failed, $PASS passed) — DO NOT DELIVER"
  exit 1
fi
