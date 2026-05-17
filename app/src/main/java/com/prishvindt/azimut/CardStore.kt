package com.prishvindt.azimut

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

class CardStore(private val activity: Activity) {
    private val resolver: ContentResolver = activity.contentResolver

    data class MediaWriteSession(
        val deckId: String,
        val deckMediaDir: Uri,
        val usedNames: MutableSet<String>
    )

    fun loadDeckSummaries(rootTreeUri: Uri): List<AssembledDeckSummary> {
        val cardsDir = ensureCardsDir(rootTreeUri)
        val indexFile = findChild(cardsDir, INDEX_FILE) ?: return emptyList()
        return readDeckIndex(indexFile.uri)
    }

    fun saveDeck(rootTreeUri: Uri, deck: AssembledDeck) {
        val cardsDir = ensureCardsDir(rootTreeUri)
        writeOrCreateText(cardsDir, deckFileName(deck.id), deck.toJson().toString(2))
        val summaries = readDeckIndex(findChild(cardsDir, INDEX_FILE)?.uri)
            .filterNot { it.id == deck.id }
            .toMutableList()
        summaries += deck.toSummary()
        writeDeckIndex(cardsDir, summaries.sortedBy { it.name.lowercase(Locale.ROOT) })
    }

    fun loadDeck(rootTreeUri: Uri, deckId: String): AssembledDeck? {
        val cardsDir = ensureCardsDir(rootTreeUri)
        val deckFile = findChild(cardsDir, deckFileName(deckId)) ?: return null
        return AssembledDeck.fromJson(JSONObject(readText(deckFile.uri)))
    }

    fun deleteDeck(rootTreeUri: Uri, deckId: String) {
        val cardsDir = ensureCardsDir(rootTreeUri)
        findChild(cardsDir, deckFileName(deckId))?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
        discardDeckMedia(rootTreeUri, deckId)
        val summaries = readDeckIndex(findChild(cardsDir, INDEX_FILE)?.uri).filterNot { it.id == deckId }
        writeDeckIndex(cardsDir, summaries)
        val state = loadCardsState(rootTreeUri)
        state.removeDeck(deckId)
        saveCardsState(rootTreeUri, state)
    }

    fun prepareDeckMediaDir(rootTreeUri: Uri, deckId: String): Uri {
        val cardsDir = ensureCardsDir(rootTreeUri)
        val mediaDir = ensureDir(cardsDir, MEDIA_DIR)
        ensureNoMedia(mediaDir)
        val deckMediaDir = ensureDir(mediaDir, deckId)
        ensureNoMedia(deckMediaDir)
        return deckMediaDir
    }

    fun openMediaWriteSession(rootTreeUri: Uri, deckId: String): MediaWriteSession {
        val deckMediaDir = prepareDeckMediaDir(rootTreeUri, deckId)
        val usedNames = listChildren(deckMediaDir)
            .filterNot { it.isDirectory }
            .map { it.name.lowercase(Locale.ROOT) }
            .toMutableSet()
        return MediaWriteSession(deckId, deckMediaDir, usedNames)
    }

    fun discardDeckMedia(rootTreeUri: Uri, deckId: String) {
        runCatching {
            val cardsDir = findCardsDir(rootTreeUri) ?: return
            val mediaDir = findChild(cardsDir, MEDIA_DIR)?.uri ?: return
            val deckMediaDir = findChild(mediaDir, deckId)?.uri ?: return
            deleteDocumentTree(deckMediaDir)
        }
    }

    fun saveMedia(
        rootTreeUri: Uri,
        deckId: String,
        preferredFileName: String,
        type: CardMediaType,
        input: InputStream
    ): CardMediaRef {
        val deckMediaDir = prepareDeckMediaDir(rootTreeUri, deckId)
        val fileName = uniqueChildName(deckMediaDir, sanitizeFileName(preferredFileName))
        val mime = mimeForCardMedia(type, fileName)
        val uri = DocumentsContract.createDocument(resolver, deckMediaDir, mime, fileName)
            ?: throw LostFolderAccessException()
        resolver.openOutputStream(uri, "wt")?.use { out -> input.copyTo(out) }
            ?: throw LostFolderAccessException()
        val actualName = queryName(uri) ?: fileName
        return CardMediaRef(
            type = type,
            fileName = actualName,
            relativePath = "$MEDIA_DIR/$deckId/$actualName",
            uri = uri.toString(),
            mime = mime
        )
    }

    fun saveMedia(
        session: MediaWriteSession,
        preferredFileName: String,
        type: CardMediaType,
        input: InputStream
    ): CardMediaRef {
        val fileName = uniqueChildName(session.usedNames, sanitizeFileName(preferredFileName))
        val mime = mimeForCardMedia(type, fileName)
        val uri = DocumentsContract.createDocument(resolver, session.deckMediaDir, mime, fileName)
            ?: throw LostFolderAccessException()
        resolver.openOutputStream(uri, "wt")?.use { out -> input.copyTo(out) }
            ?: throw LostFolderAccessException()
        session.usedNames += fileName.lowercase(Locale.ROOT)
        return CardMediaRef(
            type = type,
            fileName = fileName,
            relativePath = "$MEDIA_DIR/${session.deckId}/$fileName",
            uri = uri.toString(),
            mime = mime
        )
    }

    fun markCardAnswer(rootTreeUri: Uri, deckId: String, cardId: String, known: Boolean) {
        val state = loadCardsState(rootTreeUri)
        state.record(deckId, cardId, known)
        saveCardsState(rootTreeUri, state)
    }

    fun resolveMediaUri(rootTreeUri: Uri, ref: CardMediaRef): Uri? {
        if (ref.uri.isNotBlank()) {
            val stored = runCatching { Uri.parse(ref.uri) }.getOrNull()
            if (stored != null && documentExists(stored)) return stored
        }
        val cardsDir = findCardsDir(rootTreeUri) ?: return null
        val parts = ref.relativePath.split('/').filter { it.isNotBlank() }
        val normalized = if (parts.firstOrNull() == CARDS_DIR) parts.drop(1) else parts
        if (normalized.size < 3 || normalized[0] != MEDIA_DIR) return null
        var current = findChild(cardsDir, MEDIA_DIR)?.uri ?: return null
        for (folder in normalized.drop(1).dropLast(1)) {
            current = findChild(current, folder)?.uri ?: return null
        }
        return findChild(current, normalized.last())?.uri
    }

    fun mediaExists(rootTreeUri: Uri, ref: CardMediaRef): Boolean {
        return resolveMediaUri(rootTreeUri, ref) != null
    }

    private fun loadCardsState(rootTreeUri: Uri): CardsState {
        val cardsDir = ensureCardsDir(rootTreeUri)
        val stateFile = findChild(cardsDir, STATE_FILE) ?: return CardsState()
        return runCatching { CardsState.fromJson(JSONObject(readText(stateFile.uri))) }.getOrElse { CardsState() }
    }

    private fun saveCardsState(rootTreeUri: Uri, state: CardsState) {
        val cardsDir = ensureCardsDir(rootTreeUri)
        writeOrCreateText(cardsDir, STATE_FILE, state.toJson().toString(2))
    }

    private fun readDeckIndex(uri: Uri?): List<AssembledDeckSummary> {
        if (uri == null) return emptyList()
        return runCatching {
            val json = JSONObject(readText(uri))
            val arr = json.optJSONArray("decks") ?: JSONArray()
            val result = mutableListOf<AssembledDeckSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                result += AssembledDeckSummary.fromJson(obj)
            }
            result
        }.getOrElse { emptyList() }
    }

    private fun writeDeckIndex(cardsDir: Uri, summaries: List<AssembledDeckSummary>) {
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("decks", JSONArray().apply { summaries.forEach { put(it.toJson()) } })
        }
        writeOrCreateText(cardsDir, INDEX_FILE, json.toString(2))
    }

    private fun writeOrCreateText(parentDir: Uri, name: String, content: String) {
        val existing = findChild(parentDir, name)
        val uri = existing?.uri ?: (DocumentsContract.createDocument(resolver, parentDir, "application/json", name)
            ?: throw LostFolderAccessException())
        writeText(uri, content)
    }

    private fun writeText(uri: Uri, content: String) {
        resolver.openOutputStream(uri, "wt")?.use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw LostFolderAccessException()
    }

    private fun readText(uri: Uri): String {
        return resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw LostFolderAccessException()
    }

    private fun ensureCardsDir(rootTreeUri: Uri): Uri {
        val root = rootDocumentUri(rootTreeUri)
        val cardsDir = ensureDir(root, CARDS_DIR)
        ensureNoMedia(cardsDir)
        val mediaDir = ensureDir(cardsDir, MEDIA_DIR)
        ensureNoMedia(mediaDir)
        return cardsDir
    }

    private fun findCardsDir(rootTreeUri: Uri): Uri? {
        val root = rootDocumentUri(rootTreeUri)
        return findChild(root, CARDS_DIR)?.takeIf { it.isDirectory }?.uri
    }

    private fun ensureDir(parentDir: Uri, name: String): Uri {
        val existing = findChild(parentDir, name)
        if (existing != null && existing.isDirectory) return existing.uri
        return DocumentsContract.createDocument(resolver, parentDir, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw LostFolderAccessException()
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

    private fun uniqueChildName(parentDir: Uri, preferredName: String): String {
        val existing = listChildren(parentDir).map { it.name.lowercase(Locale.ROOT) }.toSet()
        return uniqueChildName(existing, preferredName)
    }

    private fun uniqueChildName(existing: Set<String>, preferredName: String): String {
        if (preferredName.lowercase(Locale.ROOT) !in existing) return preferredName
        val base = preferredName.substringBeforeLast('.', preferredName)
        val ext = preferredName.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (ext.isBlank()) "${base}_$index" else "${base}_$index.$ext"
            if (candidate.lowercase(Locale.ROOT) !in existing) return candidate
            index += 1
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\\\/:*?\"<>|]"), "_")
            .trim()
        return cleaned.ifBlank { "media_${System.currentTimeMillis()}" }
    }

    private fun deckFileName(deckId: String): String = "assembled_$deckId.json"

    private fun rootDocumentUri(treeUri: Uri): Uri {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    private fun findChild(parentUri: Uri, name: String): DocumentInfo? {
        return listChildren(parentUri).firstOrNull { it.name == name }
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
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun documentExists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun deleteDocumentTree(uri: Uri) {
        runCatching {
            val children = listChildren(uri)
            for (child in children) {
                if (child.isDirectory) deleteDocumentTree(child.uri) else DocumentsContract.deleteDocument(resolver, child.uri)
            }
        }
        DocumentsContract.deleteDocument(resolver, uri)
    }

    companion object {
        private const val CARDS_DIR = "cards"
        private const val MEDIA_DIR = "media"
        private const val INDEX_FILE = "assembled_decks.json"
        private const val STATE_FILE = "cards_state.json"
    }
}
