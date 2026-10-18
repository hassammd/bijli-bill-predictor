# Bijli Bill Calculator — Project Guide (for developers/agents)

App: LESCO official bill viewer + meter video check (units/andaza bill/slab alerts)
Package: `com.hatch.bijlibill` — current version: 2.5.0 (versionCode 20)

## Build (no Gradle — manual pipeline)
Requirements: JDK 17, Android SDK (build-tools 34.0.0, platform android-34).
```bash
cd <project-root>
./build.sh
```
This compiles (`javac`), dexes (`d8`), packages (`aapt2`), signs (debug) and runs
`TestBillFetch.java` (unit tests, must be ALL PASS). Outputs go to `build/`.

## Project layout
| Path | What |
|---|---|
| `AndroidManifest.xml` | Manifest — **bump `versionCode`/`versionName` here** for releases |
| `build.sh` | The whole build: edit carefully, it's the source of truth |
| `java/` | All Java sources (`com.hatch.bijlibill.*`) |
| `res/` | Layouts, `values/strings.xml` (EN) + `values-ur/strings.xml` (Urdu — keep in parity!) |
| `assets/` | Bundled files (e.g. fallback rates JSON) |
| `TestBillFetch.java` | Headless unit tests — run via build.sh |
| `testdata/` | Test fixtures |
| `debug.keystore` | DEBUG signing key only — Play Store needs a NEW release keystore (and its backup!) |

## Config hotspots (where to change things)
- `java/.../Tariff.java` — NEPRA slab rates (ONE section, easy to update; rates go stale — verify before any public release)
- `java/.../RatesConfig.java` — remote `rates.json` URL (auto rate updates)
- `java/.../AdsConfig.java` — AdMob IDs (currently Google TEST IDs → swap real IDs before publishing)
- `BillFetch.java` (or similarly named) — PITC fetch chain: `https://bill.pitc.com.pk/lescobill/general?refno=<14_DIGITS>` (strip U/R suffix)
- `MeterCheckCalc.java` — meter-check math: base = bill PRESENT reading; doubles throughout
- `KwhAnchorPicker.java` — OCR kWh-anchored reading picker (pure, unit-tested)
- `FramePrep.java` — frame sharpness ranking + preprocessing for OCR

## Standing rules (don't break)
- Official bill HTML must always render in the WebView; parsing never blocks it.
- EN/Urdu string parity 100% (`values/strings.xml` vs `values-ur/strings.xml`).
- Urdu uses Noto Nastaliq rendering — check new Urdu strings for glyph coverage.
- All bill/tax figures shown as ANDAZA (estimate); final bill is always LESCO/PITC's.
- Before Play Store: release keystore, real AdMob IDs, Play Billing (if Premium stays), privacy policy, fresh tariff check.
