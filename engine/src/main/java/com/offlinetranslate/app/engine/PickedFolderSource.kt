package com.offlinetranslate.app.engine

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.offlinetranslate.engine.EngineException
import com.offlinetranslate.engine.ErrorCode
import com.offlinetranslate.engine.MODELS_FOLDER_NAME
import com.offlinetranslate.engine.MODELS_MANIFEST
import com.offlinetranslate.engine.ModelSource
import java.io.IOException
import java.io.InputStream

/**
 * A folder chosen with Android's folder picker (Storage Access Framework). Works on every Android
 * version without the broad "All files access" permission. Accepts the Translate-models folder
 * itself or a folder that contains it (for example Download).
 */
class PickedFolderSource private constructor(
    private val context: Context,
    private val files: Map<String, DocumentFile>,
) : ModelSource {
    private val sizes = HashMap<String, Long>()

    override fun size(path: String): Long? {
        val f = files[path] ?: return null
        return sizes.getOrPut(path) { f.length() }
    }

    override fun open(path: String): InputStream {
        val f = files[path] ?: throw IOException("Not in the folder: $path")
        return context.contentResolver.openInputStream(f.uri) ?: throw IOException("Cannot read $path")
    }

    companion object {
        private const val MAX_DEPTH = 6

        /** Slow on large folders (each listing is a content-provider query): call off the main thread. */
        fun open(context: Context, treeUri: Uri): PickedFolderSource {
            val picked = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw EngineException(ErrorCode.MODELS_NOT_FOUND, "This folder cannot be read. Choose the $MODELS_FOLDER_NAME folder.")
            val root = if (hasManifest(picked)) picked else {
                val dirs = picked.listFiles().filter { it.isDirectory }
                dirs.firstOrNull { it.name.equals(MODELS_FOLDER_NAME, ignoreCase = true) && hasManifest(it) }
                    ?: dirs.firstOrNull { hasManifest(it) }
                    ?: throw EngineException(
                        ErrorCode.MODELS_NOT_FOUND,
                        "No models found in this folder. Copy the $MODELS_FOLDER_NAME folder to the phone, then choose it.",
                    )
            }
            val files = HashMap<String, DocumentFile>()
            fun walk(dir: DocumentFile, prefix: String, depth: Int) {
                if (depth > MAX_DEPTH) return
                for (f in dir.listFiles()) {
                    val name = f.name ?: continue
                    if (f.isDirectory) walk(f, "$prefix$name/", depth + 1) else files["$prefix$name"] = f
                }
            }
            walk(root, "", 0)
            return PickedFolderSource(context.applicationContext, files)
        }

        private fun hasManifest(dir: DocumentFile): Boolean = dir.findFile(MODELS_MANIFEST)?.isFile == true
    }
}
