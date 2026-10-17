# Executed validation — 2026-10-17 (Asia/Tokyo)

Environment: Windows PowerShell, Microsoft JDK 17, Gradle wrapper 8.9, Android SDK API 35. Android emulator `commander_review_35`, Android 15/API 35, x86_64.

| Check | Actual result |
|---|---|
| `python tools/validate_content.py` | PASS: 12 executable mission schemas and references; 60 unique catalog entries; 48 explicitly planned |
| `gradlew :core:test` | PASS: 8 JUnit test methods, 0 failed/skipped; one method runs the five required state-path checks for each of the 12 missions |
| `gradlew testDebugUnitTest` | Task successful, NO-SOURCE in app; JVM tests live in core and must be run explicitly |
| `gradlew lintDebug` | PASS: 0 errors, 15 warnings |
| `gradlew assembleDebug` | PASS: installable debug-signed APK produced |
| `gradlew connectedDebugAndroidTest` | PASS: 3 tests, native map navigation, Room close/reopen snapshot, actual repository restores completion plus unfinished mission resources |
| `adb install -r .../app-debug.apk` | Success; native application launches on API 35 emulator |
| Manual process-death check | Created base-c through in-app CLI in mission 1; `am force-stop`, relaunched, continued; console still reports base-c AZ objective satisfied |

The full combined core/unit/lint/assemble/connected gate passed. After adding the third repository instrumentation test, connected tests passed again and lint was rerun successfully. No source changes to the application followed the final application build.

Lint warnings: 12 newer-dependency notices, KAPT vs KSP recommendation, missing custom launcher icon, and Android data-extraction-rule recommendation. They are not hidden or suppressed. Toolchain also reports SDK XML metadata version differences, Gradle deprecations and unrecognized KAPT options for the test processor; none prevented build or test execution.

Two initial failures were fixed before final runs: a missing Compose Row brace caused Kotlin compilation failure; the Room instrumentation test inferred Boolean return type from deleteDatabase and was corrected to Unit for JUnit. These failures are not counted as successful validation.

APK: `app/build/outputs/apk/debug/app-debug.apk`

Size: 24,473,958 bytes (about 23.3 MiB).

SHA-256: `6D6F94CF6A1C29764B4AC70BA45428C7F84B05E0F3DFC251A66C73D30E42A884`

Reports: `core/build/reports/tests/test/index.html`, `app/build/reports/lint-results-debug.html`, `app/build/reports/androidTests/connected/debug/index.html`. Home and post-relaunch console screenshots are in `app/build/cloudops-home.png` and `app/build/cloudops-restored.png` (generated artifacts, ignored by Git).

Limits: no physical phone, tablet or landscape matrix was run. No historical save-format migration, comprehensive accessibility or actual network packet rules are claimed. The first 12 missions are simplified, not full fidelity to every item in the original brief. See ROADMAP.md and MISSIONS.md.
