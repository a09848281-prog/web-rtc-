# Android APK

- Java / Android Gradle
- Package: `jp.a09848281.securitycamera`
- minSdk 26 / compileSdk 35 / targetSdk 35
- WebRTC native library: `io.github.webrtc-sdk:android:150.7871.04`

Open the `android` folder in Android Studio or use the GitHub Actions APK workflow.

## Start sequence
1. Launch app while phone is unlocked.
2. Enter GAS Web App URL and device display name.
3. Generate a six-digit pairing code on the web page and enter it in Android.
4. Grant notifications, camera and microphone permissions when requested.
5. Start the command-monitor foreground service.
6. To begin a camera session, open the app while visible and tap the media-start button. The active session shows an ongoing foreground notification.

## Regression prevention
- `ACCESS_NETWORK_STATE` is explicitly declared. It was missing in an earlier WebRTC Link iteration.
- Camera, microphone and notification permissions are checked before starting services.
- WebRTC native initialization and camera initialization are isolated from the main thread.
- Startup errors retain a stage name and are shown in the app / ongoing notification.
- Rear camera is selected first; Camera1 fallback remains available.
- TURN and STUN scheme URLs are accepted by the native ICE parser.

## OS and privacy limits
A remote web request can display a start notification, but it cannot silently start a new camera/microphone session from a locked/background state. The owner must tap into the app and initiate media while it is visible. Existing foreground media sessions may continue with an ongoing notification, subject to OEM power management.

The command-monitor fallback polls GAS at a low frequency and does not hold a long partial WakeLock. FCM will replace this fallback after Firebase app configuration is supplied. This build does not include an FCM credential or any service-account secret.
