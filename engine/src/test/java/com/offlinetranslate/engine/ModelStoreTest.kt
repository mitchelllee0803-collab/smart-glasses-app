package com.offlinetranslate.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelStoreTest {
    private val tmp: File = Files.createTempDirectory("models-test").toFile()
    private val source = File(tmp, "Translate-models")
    private val installDir = File(tmp, "app/models")

    @After fun cleanUp() {
        tmp.deleteRecursively()
    }

    private val contents = mapOf(
        "models.manifest.json" to "{}",
        "argos/packages/en_ko/metadata.json" to """{"from_code":"en","to_code":"ko"}""",
        "argos/packages/en_ko/model/model.bin" to "x".repeat(3000),
        "speech/sense-voice-small/model.bin" to "s".repeat(2000),
        "tessdata/eng.traineddata" to "t".repeat(500),
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun index(files: Map<String, String> = contents) = ModelIndex.parse(
        JSONObject().put("id", "test").put("files", JSONArray(files.map { (p, c) ->
            JSONObject().put("path", p).put("size", c.toByteArray().size).put("sha256", sha(c.toByteArray()))
        })).toString(),
    )

    private fun writeSource(files: Map<String, String> = contents) = files.forEach { (p, c) ->
        File(source, p).apply { parentFile.mkdirs() }.writeText(c)
    }

    private fun import(store: ModelStore, free: Long = Long.MAX_VALUE, cancel: CancelCheck = NO_CANCEL, progress: (ImportProgress) -> Unit = {}): StagedImport =
        store.stage(DirectorySource(source), free, cancel, progress).also(store::commit)

    @Test fun `a complete folder is copied, verified and recorded`() {
        writeSource()
        val store = ModelStore(index(), installDir)
        val seen = mutableListOf<ImportProgress>()
        val r = import(store) { seen += it }
        assertEquals(5, r.files.size)
        assertEquals(contents.values.sumOf { it.length.toLong() }, r.bytes)
        assertEquals(emptyList<String>(), r.missing)
        for ((p, c) in contents) {
            assertTrue(p, store.isInstalled(p))
            assertEquals(c, store.file(p).readText())
        }
        assertTrue(store.isComplete("argos/packages/en_ko/"))
        assertEquals(seen.last().totalBytes, seen.last().copiedBytes)
        assertFalse(File(tmp, "app/models.importing").exists())
    }

    @Test fun `importing again skips verified files, and a partial folder only adds what it has`() {
        writeSource(contents.filterKeys { !it.startsWith("speech/") })
        val store = ModelStore(index(), installDir)
        val first = import(store)
        assertEquals(listOf("speech/sense-voice-small/model.bin"), first.missing)
        assertFalse(store.isComplete("speech/sense-voice-small/"))
        writeSource()
        val second = import(store)
        assertEquals(listOf("speech/sense-voice-small/model.bin"), second.files.map { it.path })
        assertEquals(4, second.alreadyInstalled)
        assertTrue(store.isComplete("speech/sense-voice-small/"))
    }

    @Test fun `a damaged file stops the import and leaves installed models untouched`() {
        writeSource()
        val store = ModelStore(index(), installDir)
        import(store)
        val good = store.file("argos/packages/en_ko/model/model.bin").readText()
        // Same size, different content: only the checksum can tell.
        File(source, "argos/packages/en_ko/model/model.bin").writeText("y".repeat(3000))
        File(installDir, ".installed.json").delete() // force a re-copy
        val e = assertThrows(EngineException::class.java) { import(store) }
        assertEquals(ErrorCode.MODELS_DAMAGED, e.code)
        assertTrue(e.message!!.contains("model.bin"))
        assertEquals(good, store.file("argos/packages/en_ko/model/model.bin").readText())
        assertFalse(File(tmp, "app/models.importing").exists())
    }

    @Test fun `a truncated file is rejected before copying`() {
        writeSource()
        File(source, "tessdata/eng.traineddata").writeText("t".repeat(10))
        val e = assertThrows(EngineException::class.java) { import(ModelStore(index(), installDir)) }
        assertEquals(ErrorCode.MODELS_DAMAGED, e.code)
        assertFalse(File(installDir, "tessdata/eng.traineddata").exists())
    }

    @Test fun `a folder without models, too little space and cancellation are reported`() {
        source.mkdirs()
        File(source, "holiday.jpg").writeText("not a model")
        assertEquals(ErrorCode.MODELS_NOT_FOUND, assertThrows(EngineException::class.java) { import(ModelStore(index(), installDir)) }.code)

        writeSource()
        assertEquals(ErrorCode.INSUFFICIENT_STORAGE, assertThrows(EngineException::class.java) { import(ModelStore(index(), installDir), free = 1000) }.code)

        var chunks = 0
        val cancel = CancelCheck { if (++chunks > 2) throw EngineException(ErrorCode.CANCELLED, "cancelled") }
        val store = ModelStore(index(), installDir)
        assertEquals(ErrorCode.CANCELLED, assertThrows(EngineException::class.java) { import(store, cancel = cancel) }.code)
        assertTrue(store.installed().isEmpty())
        assertFalse(File(tmp, "app/models.importing").exists())
    }

    @Test fun `files of an older model set are removed, unsafe index paths are refused`() {
        writeSource()
        val store = ModelStore(index(), installDir)
        File(installDir, "argos/packages/old_pkg/model.bin").apply { parentFile.mkdirs() }.writeText("old")
        import(store)
        assertFalse(File(installDir, "argos/packages/old_pkg").exists())
        assertTrue(File(installDir, ".installed.json").isFile)

        for (bad in listOf("../evil", "/abs/path", "a//b", "a/./b", "c:\\x", "")) assertFalse(bad, ModelIndex.isSafePath(bad))
        assertTrue(ModelIndex.isSafePath("argos/packages/en_ko/model/model.bin"))
        assertThrows(IllegalArgumentException::class.java) { index(mapOf("../outside" to "x")) }
    }
}
