# Bijli Bill Predictor ⚡

**Know your bijli bill before it arrives.**

Bijli Bill Predictor is Pakistan's most complete electricity bill assistant —
built around the 200-unit Protected threshold that decides whether your bill
stays low or jumps.

![version](https://img.shields.io/badge/version-3.6.2-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-36-green)
![minSdk](https://img.shields.io/badge/minSdk-24-orange)
![license](https://img.shields.io/badge/license-MIT-lightgrey)

## ✨ Features

- 🧾 **OFFICIAL BILL CHECK** — enter your reference number to fetch your
  official LESCO bill (units, readings, category, charges, subsidy, GST/taxes,
  fuel adjustment, arrears, due date) and download it as PDF
- 🛡️ **200-UNIT GUARD** — enter your current meter reading any day of the
  month: units used vs 200, month-end projection, daily average, daily budget,
  SAFE / WARNING / DANGER status; per-meter history
- 📷 **METER OCR** — point the camera at your meter; reading auto-detected
  from photo, video or gallery; multiple meters tracked
- ⚖️ **WHAT-IF COMPARE** — compare 190 vs 200 vs 210 vs 250 units side by
  side; see exactly what crossing 200 units costs
- 🎯 **ANDAZA vs ASAL TRACKER** — estimate vs real bill, with accuracy
  percentage (last 6 comparisons)
- 🔔 **SMART REMINDERS** — bill due-date reminder, 200-unit approach warning,
  daily guard-check streaks, new-bill nudge, Sunday weekly report
- 🌐 **ENGLISH + اردو** — complete bilingual app (Noto Nastaliq Urdu)
- 📡 **REMOTE RATE UPDATES** — tariff rates update from a hosted JSON; no app
  update needed
- 🔒 **100% on-device** — your meter numbers, bill data and photos never
  leave your phone

## 🛠️ Tech stack

- Native Android — **Java + XML layouts** (no frameworks)
- Manual build pipeline (`build.sh`): `javac` → `d8` → `aapt2` → `apksigner`
  (no Gradle required)
- Headless unit tests (`TestBillFetch.java`, run by `build.sh`)
- `minSdk 24` · `targetSdk 36` · Java 17

## 🚀 Build

```bash
cd <project-root>
./build.sh
```

Outputs land in `build/`. Before any release AAB, run
`./verify-release.sh <aab>` — size, version, targetSdk, certificate and
change-markers are all checked before delivery.

## 🔑 Signing

The release upload keystore and its passwords live in `release/` on the
build machine and are **excluded from git** (see `.gitignore`). Keep two
offline backups — losing the upload key means losing update rights to the
Play Store listing.

## 📊 Data sources & disclaimer

- Tariff schedules: [nepra.org.pk](https://www.nepra.org.pk)
- Official bills: [bill.pitc.com.pk](https://bill.pitc.com.pk)

This is **not a government app**. All figures shown are estimates (اندازہ);
the final bill is always your DISCO's.

## 📦 Releases

Installable builds are published via GitHub Releases. Play Store status:
v3.6.2 (versionCode 34) submitted for review.

## ⚖️ License

MIT — see [LICENSE](LICENSE).
