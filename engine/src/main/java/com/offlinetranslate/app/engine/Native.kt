package com.offlinetranslate.app.engine

/** JNI entry points of libotengine.so (android/app/src/main/cpp/engine_jni.cpp). */
object Native {
    init {
        System.loadLibrary("otengine")
    }

    @JvmStatic external fun nativeInit()

    @JvmStatic external fun translatorCreate(modelDir: String, threads: Int): Long
    @JvmStatic external fun translatorFree(handle: Long)
    @JvmStatic external fun translatorTranslate(
        handle: Long, batch: Array<Array<String>>, beam: Int, lengthPenalty: Float, maxDecoding: Int,
    ): Array<Array<String>>

    @JvmStatic external fun spCreate(modelPath: String): Long
    @JvmStatic external fun spFree(handle: Long)
    @JvmStatic external fun spEncode(handle: Long, text: String): Array<String>
    @JvmStatic external fun spDecode(handle: Long, pieces: Array<String>): String

    @JvmStatic external fun openccCreate(configPath: String): Long
    @JvmStatic external fun openccFree(handle: Long)
    @JvmStatic external fun openccConvert(handle: Long, text: String): String
}
