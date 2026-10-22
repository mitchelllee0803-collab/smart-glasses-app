package com.offlinetranslate.engine

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/*
 * The models are not inside the APK. The user copies the "Translate-models" folder to the phone
 * and imports it into app storage. The APK carries the expected file list (models.expected.json:
 * path, size and SHA-256 of every file), generated at build time from the same verified files as
 * the Windows build. Nothing in the copied folder is trusted: only listed files are copied, and
 * each must match its checksum.
 */

const val MODELS_FOLDER_NAME = "Translate-models"
const val MODELS_MANIFEST = "models.manifest.json"

data class ModelFile(val path: String, val size: Long, val sha256: String)

class ModelIndex(val id: String, val files: List<ModelFile>) {
    val byPath: Map<String, ModelFile> = files.associateBy { it.path }

    fun under(prefix: String): List<ModelFile> = files.filter { it.path.startsWith(prefix) }

    companion object {
        fun parse(json: String): ModelIndex {
            val o = JSONObject(json)
            val list = o.getJSONArray("files")
            val files = (0 until list.length()).map { i ->
                val f = list.getJSONObject(i)
                ModelFile(f.getString("path"), f.getLong("size"), f.getString("sha256").lowercase(Locale.ROOT))
            }
            for (f in files) require(isSafePath(f.path)) { "Unsafe path in model index: ${f.path}" }
            return ModelIndex(o.getString("id"), files)
        }

        /** Relative, '/'-separated, no empty / "." / ".." segments: cannot escape the models folder. */
        fun isSafePath(p: String): Boolean =
            p.isNotEmpty() && !p.startsWith("/") && '\\' !in p && ':' !in p && p.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

/** Where an import reads from: the folder the user picked (Android) or a plain directory (tests). */
interface ModelSource {
    /** Size of the file at [path] (relative, '/'-separated), or null if the folder does not have it. */
    fun size(path: String): Long?
    fun open(path: String): InputStream
}

class DirectorySource(private val root: File) : ModelSource {
    override fun size(path: String): Long? = File(root, path).takeIf { it.isFile }?.length()
    override fun open(path: String): InputStream = File(root, path).inputStream()
}

data class ImportProgress(val copiedBytes: Long, val totalBytes: Long, val file: String?)

/** Files copied and verified into the staging folder, not yet installed. */
class StagedImport(val files: List<ModelFile>, val alreadyInstalled: Int, val missing: List<String>) {
    val bytes: Long get() = files.sumOf { it.size }

    fun toMap(): Map<String, Any?> = mapOf(
        "importedFiles" to files.size,
        "importedBytes" to bytes,
        "alreadyInstalled" to alreadyInstalled,
        "missing" to missing,
    )
}

/** The installed models in [dir] (app storage) and the import into it. */
class ModelStore(val index: ModelIndex, val dir: File) {
    private val staging = File(dir.parentFile, "${dir.name}.importing")
    private val record = File(dir, ".installed.json")

    fun file(path: String): File = File(dir, path)

    /** path -> SHA-256 of every file verified by an import. */
    fun installed(): Map<String, String> = try {
        val o = JSONObject(record.readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Imported with the expected checksum and still the expected size. */
    fun isInstalled(path: String, installed: Map<String, String> = installed()): Boolean {
        val e = index.byPath[path] ?: return false
        val f = file(path)
        return installed[path] == e.sha256 && f.isFile && f.length() == e.size
    }

    /** True when every listed file under [prefix] is installed (and there is at least one). */
    fun isComplete(prefix: String, installed: Map<String, String> = installed()): Boolean {
        val files = index.under(prefix)
        return files.isNotEmpty() && files.all { isInstalled(it.path, installed) }
    }

    fun cleanStaging() {
        staging.deleteRecursively()
    }

    /**
     * Copies every listed file found in [source] into a staging folder, checking size and SHA-256.
     * Installed models are untouched until [commit]. Files already installed with the right checksum
     * are skipped, so importing again after an app update only copies what changed.
     */
    fun stage(source: ModelSource, freeBytes: Long, cancel: CancelCheck, progress: (ImportProgress) -> Unit): StagedImport {
        cleanStaging()
        val installed = installed()
        val toCopy = mutableListOf<ModelFile>()
        val missing = mutableListOf<String>()
        var skipped = 0
        var found = 0
        for (e in index.files) {
            val size = source.size(e.path)
            if (size != null) found++
            when {
                size != null && size != e.size -> throw EngineException(
                    ErrorCode.MODELS_DAMAGED,
                    "${e.path} is incomplete or damaged ($size bytes instead of ${e.size}). Copy the $MODELS_FOLDER_NAME folder to the phone again.",
                )
                isInstalled(e.path, installed) -> skipped++
                size == null -> missing += e.path
                else -> toCopy += e
            }
        }
        if (found == 0) {
            throw EngineException(ErrorCode.MODELS_NOT_FOUND, "This folder does not contain the models. Choose the $MODELS_FOLDER_NAME folder.")
        }
        val total = toCopy.sumOf { it.size }
        val needed = total + SPACE_MARGIN
        if (toCopy.isNotEmpty() && freeBytes < needed) {
            throw EngineException(
                ErrorCode.INSUFFICIENT_STORAGE,
                "Not enough free space: the models need %.0f MB, but only %.0f MB is free.".format(Locale.ROOT, needed / MB, freeBytes / MB),
            )
        }
        try {
            var copied = 0L
            progress(ImportProgress(0, total, null))
            val buffer = ByteArray(1 shl 20)
            for (e in toCopy) {
                val target = File(staging, e.path)
                target.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                source.open(e.path).use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            cancel.check()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            length += n
                            copied += n
                            progress(ImportProgress(copied, total, e.path))
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
                if (length != e.size || sha != e.sha256) {
                    throw EngineException(
                        ErrorCode.MODELS_DAMAGED,
                        "${e.path} is damaged (checksum mismatch). Copy the $MODELS_FOLDER_NAME folder to the phone again.",
                    )
                }
            }
            return StagedImport(toCopy, skipped, missing)
        } catch (t: Throwable) {
            cleanStaging()
            throw t
        }
    }

    /**
     * Moves the verified files into place and records them. Call only while no model is loaded.
     * Files that are not in the current index (left from an older model set) are removed.
     */
    fun commit(staged: StagedImport) {
        dir.mkdirs()
        val installed = installed().filterKeys { it in index.byPath }.toMutableMap()
        for (e in staged.files) {
            val from = File(staging, e.path)
            val to = file(e.path)
            to.parentFile?.mkdirs()
            if (to.exists() && !to.delete()) throw IOException("Cannot replace ${e.path}")
            if (!from.renameTo(to)) throw IOException("Cannot install ${e.path}")
            installed[e.path] = e.sha256
        }
        val tmp = File(dir, ".installed.json.tmp")
        tmp.writeText(JSONObject(installed as Map<*, *>).toString())
        if (!tmp.renameTo(record)) {
            record.delete()
            if (!tmp.renameTo(record)) throw IOException("Cannot write the model record")
        }
        cleanStaging()
        prune()
    }

    private fun prune() {
        dir.walkBottomUp().forEach { f ->
            if (f == dir) return@forEach
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (f.isFile && rel != record.name && rel !in index.byPath) f.delete()
            if (f.isDirectory && f.list().isNullOrEmpty()) f.delete()
        }
    }

    private companion object {
        const val SPACE_MARGIN = 64L * 1024 * 1024
        const val MB = 1024.0 * 1024.0
    }
}
