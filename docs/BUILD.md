# Build / signing

Use JDK 17 + Android SDK 35 and the committed wrapper. `gradlew :core:test testDebugUnitTest lintDebug assembleDebug` is the baseline gate. Instrumented tests require a booted API 29+ emulator/device and `connectedDebugAndroidTest`. Debug build is signed with the standard local Android debug key and intended for development.

Production release signing is deliberately not configured with embedded secrets. Generate a private keystore outside the repository using JDK keytool, back it up securely, build an unsigned release with `gradlew assembleRelease`, align and sign with Android SDK `zipalign` and `apksigner`, supplying passwords interactively or via a secure CI secret mechanism. Verify the signed file with `apksigner verify --verbose`. Preserve the same release signing identity for future updates. Never commit keystores, key passwords, AWS credentials or private signing material.

Workflow uploads Debug APK only. It does not publish releases, install on real phones automatically or use production keys.
