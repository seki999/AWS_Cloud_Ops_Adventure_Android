# Executed validation — 2026-10-17 (Asia/Tokyo), app 0.2.0 / content v2

Environment: Windows, Microsoft JDK 17.0.20, Gradle wrapper 8.9, AGP 8.7.3, Android SDK platform 35.

| Check | Actual result |
|---|---|
| `python tools/validate_content.py` | PASS: 60 playable mission schemas/references; 10 chapters; 60 unique catalog entries |
| `python tools/generate_content.py` | Deterministic: regenerating before the aws-016 change reproduced every asset byte-for-byte |
| `gradlew :core:test` | PASS: 19 tests, 0 failed/skipped (`EngineTest` 8, `SimulationRulesTest` 11) |
| `gradlew testDebugUnitTest` | Task successful (no JVM tests in `app`; engine tests live in `core`) |
| `gradlew lintDebug` | PASS: 0 errors, 15 warnings (dependency-update notices, KAPT→KSP, launcher icon, data-extraction rules) |
| `gradlew assembleDebug` | PASS |
| `aapt dump permissions` | No `INTERNET` permission; only the AndroidX-generated receiver permission |
| `gradlew connectedDebugAndroidTest` | **Not re-run for 0.2.0.** AVD `commander_review_35` fails to boot: its API 35 system image is no longer installed. Last pass was the v0.1 code (3 tests). |

APK: `app/build/outputs/apk/debug/app-debug.apk` — 24,701,121 bytes, SHA-256 `2eaba1b96df76d710a4175dc2b1bf8974e28c5129e856f65354152f3fc7823db`. Contains 60 mission assets.

## Issues found and fixed in this pass

- `app` did not compile: `MainActivity` referenced a `Telemetry` composable that did not exist. Added `Telemetry.kt` (metric chart over recorded samples).
- aws-016 passed with a `0.0.0.0/0 → 5432` security-group rule still present. Added the `noOpenIngress` constraint; a dedicated test proves the mission fails until the rule is removed.
- Save loading rejected any non-v1 snapshot. Replaced with `SaveCodec` and a tested v1→v2 migration that keeps progress.

## Not verified

Physical phone/tablet/landscape matrix, TalkBack, flight-mode run on a device, and the instrumentation suite on the 0.2.0 build.
