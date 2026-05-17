package com.prishvindt.azimut

import android.app.Activity
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

internal fun isLostFolderError(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is SecurityException ||
            current is FileNotFoundException ||
            current is IllegalArgumentException ||
            current is IOException
        ) {
            return true
        }
        if (current.message?.contains("Missing file", ignoreCase = true) == true) {
            return true
        }
        current = current.cause
    }
    return false
}

class SafStore(private val activity: Activity) {
    private val resolver: ContentResolver = activity.contentResolver

    companion object {
        private val SERVICE_MIPMAP_DIRS = setOf(
            "mipmap-mdpi",
            "mipmap-hdpi",
            "mipmap-xhdpi",
            "mipmap-xxhdpi",
            "mipmap-xxxhdpi"
        )
    }

    fun displayNameForTree(treeUri: Uri): String {
        return queryName(rootDocumentUri(treeUri)) ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':')
    }

    fun listDocxFiles(rootTreeUri: Uri): List<DocumentInfo> {
        ensureNoMediaInServiceMipmapDirs(rootTreeUri)
        return listChildren(rootDocumentUri(rootTreeUri))
            .filter { !it.isDirectory && it.name.endsWith(".docx", ignoreCase = true) && !it.name.startsWith("~$") }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun loadTests(rootTreeUri: Uri): TestLoadResult {
        ensureNoMediaInServiceMipmapDirs(rootTreeUri)
        val testDir = ensureTestDir(rootTreeUri)
        ensureNoMediaInExistingAssets(testDir)
        val warnings = mutableListOf<String>()
        val tests = mutableListOf<TestFile>()
        val files = listChildren(testDir).filter { !it.isDirectory && it.name.endsWith(".json", true) }
        for (file in files) {
            try {
                val text = readText(file.uri)
                val template = TestTemplate.fromJson(JSONObject(text))
                tests += TestFile(file.name, file.uri, template)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                if (isLostFolderError(e)) throw e
                warnings += "Некоторые тесты не удалось загрузить: ${file.name}"
            }
        }
        return TestLoadResult(tests.sortedBy { it.template.title.lowercase(Locale.ROOT) }, warnings)
    }

    fun loadExistingTests(rootTreeUri: Uri): TestLoadResult {
        ensureNoMediaInServiceMipmapDirs(rootTreeUri)
        val testDir = findTestDir(rootTreeUri) ?: return TestLoadResult(emptyList(), emptyList())
        val warnings = mutableListOf<String>()
        val tests = mutableListOf<TestFile>()
        val files = listChildren(testDir).filter { !it.isDirectory && it.name.endsWith(".json", true) }
        for (file in files) {
            try {
                val text = readText(file.uri)
                val template = TestTemplate.fromJson(JSONObject(text))
                tests += TestFile(file.name, file.uri, template)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                if (isLostFolderError(e)) throw e
                warnings += "Некоторые тесты не удалось загрузить"
            }
        }
        return TestLoadResult(tests.sortedBy { it.template.title.lowercase(Locale.ROOT) }, warnings.distinct())
    }

    fun ensureNoMediaInServiceMipmapDirs(rootTreeUri: Uri) {
        val root = runCatching { rootDocumentUri(rootTreeUri) }.getOrNull() ?: return
        ensureNoMediaInServiceMipmapDirsUnder(root)
        runCatching { findTestDir(rootTreeUri) }.getOrNull()?.let { ensureNoMediaInServiceMipmapDirsUnder(it) }
    }

    fun createTestFile(rootTreeUri: Uri, name: String, content: String): Uri {
        val testDir = ensureTestDir(rootTreeUri)
        val uri = DocumentsContract.createDocument(resolver, testDir, "application/json", name)
            ?: throw LostFolderAccessException()
        writeText(uri, content)
        return uri
    }


    fun resetAttemptAssets(rootTreeUri: Uri, testFileName: String): Uri {
        val assets = ensureAssetsDir(rootTreeUri, testFileName)
        ensureNoMedia(assets)
        val existing = listChildren(assets).firstOrNull { it.isDirectory && it.name == "attempt" }
        if (existing != null) deleteDocumentTree(existing.uri)
        val attempt = DocumentsContract.createDocument(resolver, assets, DocumentsContract.Document.MIME_TYPE_DIR, "attempt")
            ?: throw LostFolderAccessException()
        ensureNoMedia(attempt)
        return attempt
    }

    fun deleteAttemptAssets(rootTreeUri: Uri, testFileName: String) {
        runCatching {
            val assets = findAssetsDir(rootTreeUri, testFileName) ?: return
            val attempt = listChildren(assets).firstOrNull { it.isDirectory && it.name == "attempt" } ?: return
            deleteDocumentTree(attempt.uri)
        }
    }

    fun deleteAssetsForTest(rootTreeUri: Uri, testFileName: String) {
        runCatching { findAssetsDir(rootTreeUri, testFileName)?.let { deleteDocumentTree(it) } }
    }

    fun saveEmbeddedImage(parentDir: Uri, image: EmbeddedImage, index: Int): ImageRef {
        val normalized = normalizeImageData(image)
        val name = "img_${index}_${UUID.randomUUID().toString().take(8)}.${normalized.extension}"
        val uri = DocumentsContract.createDocument(resolver, parentDir, normalized.mime, name)
            ?: throw LostFolderAccessException()
        resolver.openOutputStream(uri, "wt")?.use { it.write(normalized.bytes) }
            ?: throw LostFolderAccessException()
        return ImageRef(uri.toString(), normalized.mime, name)
    }

    fun writeText(uri: Uri, content: String) {
        resolver.openOutputStream(uri, "wt")?.use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw LostFolderAccessException()
    }

    fun readText(uri: Uri): String {
        return resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw LostFolderAccessException()
    }

    fun findSourceFile(rootTreeUri: Uri, source: SourceFile): Uri? {
        if (source.documentId.isNotBlank()) {
            val byId = runCatching { DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, source.documentId) }.getOrNull()
            if (byId != null && documentExists(byId)) return byId
        }
        return listDocxFiles(rootTreeUri).firstOrNull { it.name == source.name }?.uri
    }

    private fun ensureAssetsDir(rootTreeUri: Uri, testFileName: String): Uri {
        val testDir = ensureTestDir(rootTreeUri)
        val name = testFileName.substringBeforeLast('.') + "_assets"
        val existing = listChildren(testDir).firstOrNull { it.isDirectory && it.name == name }
        if (existing != null) {
            ensureNoMedia(existing.uri)
            return existing.uri
        }
        val created = DocumentsContract.createDocument(resolver, testDir, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw LostFolderAccessException()
        ensureNoMedia(created)
        return created
    }

    private fun findAssetsDir(rootTreeUri: Uri, testFileName: String): Uri? {
        val testDir = ensureTestDir(rootTreeUri)
        val name = testFileName.substringBeforeLast('.') + "_assets"
        return listChildren(testDir).firstOrNull { it.isDirectory && it.name == name }?.uri
    }

    private fun ensureNoMediaInExistingAssets(testDir: Uri) {
        try {
            listChildren(testDir)
                .filter { it.isDirectory && it.name.endsWith("_assets") }
                .forEach { assets ->
                    ensureNoMedia(assets.uri)
                    listChildren(assets.uri).filter { it.isDirectory }.forEach { child -> ensureNoMedia(child.uri) }
                }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            if (isLostFolderError(e)) throw e
        }
    }

    private fun ensureNoMediaInServiceMipmapDirsUnder(parentDir: Uri) {
        runCatching {
            listChildren(parentDir)
                .filter { it.isDirectory && it.name in SERVICE_MIPMAP_DIRS }
                .forEach { ensureNoMediaQuietly(it.uri) }
        }
    }

    private fun ensureNoMediaQuietly(dir: Uri) {
        runCatching { ensureNoMedia(dir) }
    }

    private fun ensureNoMedia(dir: Uri) {
        try {
            val exists = listChildren(dir).any { !it.isDirectory && it.name == ".nomedia" }
            if (!exists) {
                val uri = DocumentsContract.createDocument(resolver, dir, "application/octet-stream", ".nomedia")
                if (uri != null) resolver.openOutputStream(uri, "wt")?.use { }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            if (isLostFolderError(e)) throw e
        }
    }

    private fun deleteDocumentTree(uri: Uri) {
        runCatching {
            val children = listChildren(uri)
            for (child in children) {
                if (child.isDirectory) deleteDocumentTree(child.uri) else DocumentsContract.deleteDocument(resolver, child.uri)
            }
        }
        DocumentsContract.deleteDocument(resolver, uri)
    }

    private data class NormalizedImage(val bytes: ByteArray, val extension: String, val mime: String)

    private fun normalizeImageData(image: EmbeddedImage): NormalizedImage {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) }.getOrNull()
        if (bitmap == null) return NormalizedImage(image.bytes, image.extension, image.mime)
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= 1200) {
            bitmap.recycle()
            return NormalizedImage(image.bytes, image.extension, image.mime)
        }
        val scale = 1200f / maxSide.toFloat()
        val newWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (scaled != bitmap) scaled.recycle()
        bitmap.recycle()
        return NormalizedImage(out.toByteArray(), "jpg", "image/jpeg")
    }

    private fun ensureTestDir(rootTreeUri: Uri): Uri {
        findTestDir(rootTreeUri)?.let { return it }
        val root = rootDocumentUri(rootTreeUri)
        return DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "test")
            ?: throw LostFolderAccessException()
    }

    private fun findTestDir(rootTreeUri: Uri): Uri? {
        val root = rootDocumentUri(rootTreeUri)
        return listChildren(root).firstOrNull { it.isDirectory && it.name == "test" }?.uri
    }

    private fun rootDocumentUri(treeUri: Uri): Uri {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    private fun listChildren(parentUri: Uri): List<DocumentInfo> {
        val result = mutableListOf<DocumentInfo>()
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: "Без имени"
                val mime = cursor.getString(mimeCol) ?: ""
                val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, id)
                result += DocumentInfo(name, id, uri, mime == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return result
    }

    private fun queryName(uri: Uri): String? {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun documentExists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)
}
