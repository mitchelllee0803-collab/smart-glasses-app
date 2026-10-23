# Smart Glasses

Android app for offline real-time speech translation shown on VenusOS smart glasses.

- **Speech recognition and translation** run on the phone, offline. The engine comes from the
  Translation project (`D:\Data\My project\Translation`). It uses SenseVoice-Small through
  sherpa-onnx, and CTranslate2 + SentencePiece + OpenCC.
- **AI translation (optional):** finished sentences can be translated by Tencent **Hy-MT2 1.8B**
  (Apache-2.0) running on the phone through llama.cpp. The model file (~1.1 GB) is imported once;
  without it, or on phones that cannot run it, the standard translator is used.
- **Glasses link** goes through the VenusSDK 1.8.4 AAR: Classic Bluetooth, `VNCommon`, and the
  glasses' built-in translation view.
- **Languages:** English, Korean, Chinese (Simplified), Chinese (Traditional).
- **Phones:** 64-bit ARM (arm64-v8a) only, Android 7.0 or newer.

## Modules

| Module    | Contents |
| --------- | -------- |
| `:engine` | The Translation project's Android engine, copied unchanged: `com.offlinetranslate.engine` and `com.offlinetranslate.app.engine`, plus the JNI library `libotengine.so` and its 38 parity tests. The package names must not change, because the JNI symbols depend on them. |
| `:llm`    | llama.cpp (release b11102) as a static library plus a small JNI bridge (`libllmbridge.so`, `LlmModel`): load a GGUF model, generate, cancel. CPU only, built for ARMv8.2 + dot-product + FP16; `LlmModel.isCpuSupported()` checks the phone before loading. |
| `:app`    | Jetpack Compose UI, Hilt, VenusSDK integration, microphone pipeline. |

```
app/src/main/java/com/smartglasses/app/
  data/bluetooth/    ClassicDeviceScanner (discovery; the SDK has no scan API), BluetoothAvailabilityMonitor
  data/venus/        VenusClient (SDK wrapper), GlassesRepository (connection state machine),
                     VenusSubtitleSink + SubtitleQueue (subtitles on the glasses)
  data/speech/       MicrophoneCapture (AudioRecord 16 kHz), PhraseSegmenter (port of pcm.ts)
  data/translation/  EngineRepository (engine -> coroutines), TranslationSessionManager (pipeline),
                     HyMtTranslator + HyMtPrompt + GgufHeader (AI translation), NoOpTranscriptCorrector
  domain/            SubtitleSink and OfflineSpeechEngine interfaces, models
  data/history/      HistoryDatabase (Room) + HistoryRepository: saved sessions, on this phone only
  feature/           home, translate (hub + live session), history (record view), settings, models
  service/           TranslationSessionService (foreground service while listening)
```

How data flows through the app:

```
microphone -> PhraseSegmenter -> engine.transcribe (sherpa-onnx SenseVoice)
   live text:         engine.translate (fast standard translator)
   finished sentence: TranscriptCorrector (slot, no-op for now)
                      -> Hy-MT2 1.8B (one sentence at a time; context is off, see HyMtTranslator.USE_CONTEXT)
                         (falls back to engine.translate when off, missing, failing or > 15 s)
-> TranslationSessionManager -> UI state + history
                             -> SubtitleSink -> VNCommon.updateSttInfo(TRANSLATE)
```

Simplified <-> Traditional Chinese always uses the exact OpenCC conversion, not the AI model.

## Building

You need:
- JDK 21 (`D:\Android\jdk-21`)
- Android SDK 36
- NDK 27.1.12297006
- CMake 3.22.1

```powershell
$env:JAVA_HOME = "D:\Android\jdk-21"
.\gradlew :app:assembleRelease          # -> app\build\outputs\apk\release\app-release.apk (~17 MB)
.\gradlew :app:assembleDebug            # -> app\build\outputs\apk\debug\app-debug.apk (not shrunk, larger)
.\gradlew :engine:testDebugUnitTest :app:testDebugUnitTest
```

The first build compiles the native engine and llama.cpp, which takes several minutes.

llama.cpp sources (git-ignored) are fetched once, checksum-verified:

```powershell
powershell -ExecutionPolicy Bypass -File scriptsetch-llama-cpp.ps1
```

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
   - **AI translation model:** download `Hy-MT2-1.8B-Q4_K_M.gguf` (1.13 GB) from https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF on a PC, copy it to the phone, then in *Offline models* → *AI translation (Hy-MT2 1.8B)* tap *Choose file*. The app checks it is a Hy-MT2 model before copying. Needs 6 GB RAM or more. Check speed and memory with `adb logcat -s HyMtTranslator llmbridge`.
3. **Find the glasses:** turn them on and open their Bluetooth pairing screen. In the app, tap **Scan**. The glasses should appear in the list, with their signal strength.
4. **Connect:** tap the glasses. The card shows *Connecting…*. On success you see *Connected to …* with the battery level.
   - If it fails, check the log for the SDK callbacks: `adb logcat -s VenusClient GlassesRepository VenusSubtitleSink`.
   - **Brightness:** tap ☀ under the connection bar. The slider opens at the glasses' current level; dragging changes the glasses live (5–100 %, mapped to the SDK's 0–255) and turns *Auto* off. *Auto* is hidden if the glasses reject it (no light sensor). Rejected changes show `setBrightness(...) -> FAILED/TIMEOUT` in the `VenusClient` log.
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
- **AI translation:** the first finished sentence after starting waits for the model to load (a few seconds). Each finished sentence then takes roughly 1–3 s on a recent phone (to be measured on your phone); if it takes over 15 s the standard translation is shown instead. The text-correction step is a no-op placeholder for now.
- **App password:** asked when the app starts; default `123321`, changed in ☰ → Settings (stored as a salted PBKDF2 hash). There is no reset: a forgotten password means clearing the app's data (models must then be imported again).
- **Languages:** English and Simplified Chinese (`values-zh`). Messages produced inside the reused offline engine (e.g. model-import problems) stay English.
- **Test device:** "test_device" appears in the scan list of every build, so the dashboard can be checked without glasses (nothing is sent to the VenusSDK). Set `SHOW_TEST_DEVICE` to `false` in `app/build.gradle.kts` before publishing to users.
- **Release build:** R8 code and resource shrinking is on, and native libraries are compressed in the APK. Names used by native code or reflection (VenusSDK, sherpa-onnx, Tesseract, the JNI bridges, stored enum names) are kept by `app/proguard-rules.pro`. Until a release key exists, the release APK is signed with the debug key (fine for adb installs, not for a store).
