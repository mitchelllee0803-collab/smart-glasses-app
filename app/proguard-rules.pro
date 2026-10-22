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
