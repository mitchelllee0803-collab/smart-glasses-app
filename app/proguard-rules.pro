# R8 rules for the release build. Library code that is reached by name (JNI, reflection, Gson)
# must keep its names; everything else may be shrunk and renamed.

# VenusSDK (guide §3.3). Its internals are already obfuscated into short top-level packages
# (e.g. AAydSR, A7UmmK) and its beans are serialised with Gson, so nothing in it may be renamed.
-keep class com.jueyuantech.venussdk.** { *; }
-dontwarn com.jueyuantech.venussdk.**
-keep class com.bes.** { *; }
-dontwarn com.bes.**
-keep class A*.** { *; }

# Gson
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory

# sherpa-onnx (speech recognition): its native library looks up these classes and reads their
# fields by name (FindClass / GetFieldID), and it ships no rules of its own.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Tesseract4Android (part of the reused engine): its native libraries call back into these classes.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# JNI bridges of the offline engine (libotengine.so) and the AI translator (libllmbridge.so):
# the native symbols are derived from these class and method names.
-keep class com.offlinetranslate.app.engine.Native { *; }
-keep class com.smartglasses.llm.LlmNative { *; }

# Settings are stored by enum constant name (e.g. "GLASSES"); renamed constants would reset them
# after an update.
-keepclassmembers enum com.smartglasses.app.domain.model.** { <fields>; }

# Line numbers in crash reports (the mapping file restores names).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
