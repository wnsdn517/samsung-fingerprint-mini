# Samsung Fingerprint Mini

Xposed(LSPosed) module that shrinks Samsung's biometric prompt down to just the fingerprint indicator, so the app behind it stays visible and touchable. Works in any app that uses the system fingerprint prompt (banking, KakaoTalk, etc.).

삼성 지문 인증창을 지문 아이콘만 남기고 축소하는 Xposed 모듈입니다. 시스템 지문 인증을 쓰는 모든 앱에서 동작합니다.

## Features
- Collapses the biometric prompt card; touches pass through to the app
- State-aware glow (idle / scanning / success / fail) at the physical sensor on devices without an under-display sensor (prompt, keyguard side-FPS, enrollment)

## Install
1. Install the APK from [Releases](../../releases).
2. Enable it in LSPosed; scope: `com.samsung.android.biometrics.app.setting`.
3. Force-stop that package or reboot.

Requires Android 10+ (minSdk 29), Samsung One UI, LSPosed.

## Build
```
./gradlew :module:assembleDebug
```
`testapp` is a small app for triggering the biometric prompt; `xposed-stub` provides compile-only Xposed API stubs.
