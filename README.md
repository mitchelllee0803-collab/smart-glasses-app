# Smart Glasses

Android app for offline real-time speech translation shown on VenusOS smart glasses.

- **Speech recognition and translation** run on the phone, offline. The engine comes from the
  Translation project (`D:\Data\My project\Translation`). It uses SenseVoice-Small through
  sherpa-onnx, and CTranslate2 + SentencePiece + OpenCC.
- **Glasses link** goes through the VenusSDK 1.8.4 AAR: Classic Bluetooth, `VNCommon`, and the
  glasses' built-in translation view.
- **Languages:** English, Korean, Chinese (Simplified), Chinese (Traditional).
- **Phones:** 64-bit ARM (arm64-v8a) only, Android 7.0 or newer.

## Modules

| Module    | Contents |
| --------- | -------- |
| `:engine` | The Translation project's Android engine, copied unchanged: `com.offlinetranslate.engine` and `com.offlinetranslate.app.engine`, plus the JNI library `libotengine.so` and its 38 parity tests. The package names must not change, because the JNI symbols depend on them. |
| `:app`    | Jetpack Compose UI, Hilt, VenusSDK integration, microphone pipeline. |

```
app/src/main/java/com/smartglasses/app/
  data/bluetooth/    ClassicDeviceScanner (discovery; the SDK has no scan API), BluetoothAvailabilityMonitor
  data/venus/        VenusClient (SDK wrapper), GlassesRepository (connection state machine),
                     VenusSubtitleSink + SubtitleQueue (subtitles on the glasses)
  data/speech/       MicrophoneCapture (AudioRecord 16 kHz), PhraseSegmenter (port of pcm.ts)
  data/translation/  EngineRepository (engine -> coroutines), TranslationSessionManager (pipeline)
  domain/            SubtitleSink and OfflineSpeechEngine interfaces, models
  data/history/      HistoryDatabase (Room) + HistoryRepository: saved sessions, on this phone only
  feature/           home, translate (hub + live session), history (record view), settings, models
  service/           TranslationSessionService (foreground service while listening)
```

How data flows through the app:

```
microphone -> PhraseSegmenter -> engine.transcribe (partial / final) -> engine.translate
          -> TranslationSessionManager -> UI state
                                       -> SubtitleSink -> VNCommon.updateSttInfo(TRANSLATE)
```

## Building

You need:
- JDK 21 (`D:\Android\jdk-21`)
- Android SDK 36
- NDK 27.1.12297006
- CMake 3.22.1

```powershell
$env:JAVA_HOME = "D:\Android\jdk-21"
.\gradlew :app:assembleDebug            # -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew :engine:testDebugUnitTest :app:testDebugUnitTest
```

The first build compiles the native engine, which takes several minutes.

The native sources in `engine/src/main/cpp/third_party` were copied from the Translation project, where `scripts/android/fetch-native-deps.ps1` fetches them at pinned versions.
- They are git-ignored here because they are large.
- On a new machine, copy them over again, including `sentencepiece/third_party/abseil-cpp` and the `absl` junction.

Changes compared with the Translation build:
- **Abseil is no longer downloaded during the build.** `engine/src/main/cpp/CMakeLists.txt` adds the local Abseil checkout itself. SentencePiece's `FetchContent` step used to re-clone Abseil from GitHub in every fresh build directory, after deleting the local copy.
- **The model manifests come pre-generated.** `models.manifest.json` and `models.expected.json` sit in `engine/src/main/assets/models`, taken from the Translation build. As a result, the existing `Translate-models` folder imports unchanged.

## Testing on the phone and glasses

These steps have **not** been run on hardware yet:

1. **Install:** `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
2. **Import models:** open ☰ → *Offline models* → *Choose folder*. Select the `Translate-models` folder you copied to the phone. The import takes 1–3 minutes.
3. **Find the glasses:** turn them on and open their Bluetooth pairing screen. In the app, tap **Scan**. The glasses should appear in the list, with their signal strength.
4. **Connect:** tap the glasses. The card shows *Connecting…*. On success you see *Connected to …* with the battery level.
   - If it fails, check the log for the SDK callbacks: `adb logcat -s VenusClient GlassesRepository VenusSubtitleSink`.
5. **Glasses microphone:** first pair the glasses in Android's Bluetooth settings so they also connect for calls/audio. Open **Translate** and tap ⚙: *Glasses* is selected by default. On the session page the status should read *Showing on glasses*.
6. **Live translation:** pick the languages on the Translate page and tap **Start Translation**. A fresh page opens and starts listening; speak into the glasses (repeat with *Phone* selected). Live text appears while you speak; at each pause the sentence is finished and translated, on the phone and on the glasses.
7. **Records:** go back; the session is listed under *All Records* with date and time. Open it to review, rename it with the pencil icon, search with the magnifier, and select/delete with the edit icon.
8. **Translation Settings (⚙):** try *Show Original and Translation* versus *Show Translation*, and *History* versus *Newest*. Each change is applied to the glasses immediately. Switching the microphone while listening restarts listening with the new one.
9. **Glasses ↔ phone sync:** with the app open on any page (e.g. Translation Settings), open *Translate* on the glasses with the touchpad: the phone should switch to the live page and start listening. Leave *Translate* on the glasses: the phone stops, saves the session and returns to the Translate home. With the phone locked, opening *Translate* on the glasses shows a *Tap to start translating* notification. Check what the glasses report with `adb logcat -s VenusClient GlassesSync`.
10. **Screen off and disconnects:** put the phone to sleep while listening; translation should continue, with a notification. Then turn the glasses off. The app should show *Reconnecting…*, translation continues on the phone, and subtitles resume after the glasses reconnect.
11. **Offline check:** repeat step 6 in airplane mode (with Bluetooth on).

## Known limitations

- **Untested on hardware:** the VenusSDK behaviour, the engine on a real phone, and audio capture.
- **Pairing:** the app does not create a Bluetooth bond. The SDK opens an insecure RFCOMM socket, which should not need one. If the glasses refuse unpaired connections, pair them in Android's Bluetooth settings first.
- **msgId format:** the AAR's `VNSttInfo.setMsgId` takes a `String`, while the guide documents an integer. The app sends numeric strings that fit in an `int`, so either reading works.
- **Glasses microphone:** it reaches the phone over Bluetooth hands-free (HFP/SCO), so the glasses must be paired in Android's Bluetooth settings. Depending on the link, audio is 16 kHz or 8 kHz; at 8 kHz recognition accuracy may drop. While it is in use, Android treats audio like a call (music through the glasses pauses). If it cannot be opened, the app offers *Use phone*.
- **Release build:** R8 is disabled until a minified build has been verified with the glasses. The keep rules are already in `app/proguard-rules.pro`.
