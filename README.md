# پیام‌رسان من

Phase 3: native Kotlin/Jetpack Compose local UI prototype. Messages live in memory, no server connection or credentials. Phase 4 will add authentication and synchronization.

Build with JDK 17, Android SDK 35 and Gradle 8.9: `gradle :app:testDebugUnitTest :app:assembleDebug`. The font build task fetches Vazirmatn v33.003 and its OFL license. GitHub Actions publishes a debug-signed APK and SHA256 checksum. Debug builds are not production releases.
