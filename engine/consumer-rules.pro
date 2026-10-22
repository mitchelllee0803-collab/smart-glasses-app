# JNI: libotengine.so binds to these names; sherpa-onnx creates its result objects from native code.
-keep class com.offlinetranslate.app.engine.Native { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
