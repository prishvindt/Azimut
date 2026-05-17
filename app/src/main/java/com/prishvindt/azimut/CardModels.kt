package com.prishvindt.azimut

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class CardMediaType(val value: String) {
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video");

    companion object {
        fun fromValue(value: String): CardMediaType? {
            return entries.firstOrNull { it.value == value }
        }

        fun fromFileName(fileName: String): CardMediaType? {
            return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "jpg", "jpeg", "png", "webp", "gif", "svg" -> IMAGE
                "mp3", "m4a", "wav", "ogg", "aac" -> AUDIO
                "mp4", "webm", "3gp", "mkv" -> VIDEO
                else -> null
            }
        }
    }
}

enum class CardSide {
    FRONT,
    BACK
}

enum class CardPackageType {
    APKG,
    ZIP_DECK
}

data class CardMediaRef(
    val type: CardMediaType,
    val fileName: String,
    val relativePath: String,
    val uri: String,
    val mime: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.value)
        put("fileName", fileName)
        put("relativePath", relativePath)
        put("uri", uri)
        put("mime", mime)
    }

    companion object {
        fun fromJson(json: JSONObject): CardMediaRef? {
            val type = CardMediaType.fromValue(json.optString("type")) ?: return null
            val fileName = json.optString("fileName")
            val relativePath = json.optString("relativePath")
            if (fileName.isBlank() || relativePath.isBlank()) return null
            return CardMediaRef(
                type = type,
                fileName = fileName,
                relativePath = relativePath,
                uri = json.optString("uri"),
                mime = json.optString("mime", mimeForCardMedia(type, fileName))
            )
        }
    }
}

data class ImportedCard(
    val id: String,
    val frontHtml: String,
    val backHtml: String,
    val frontText: String,
    val backText: String,
    val frontMedia: List<CardMediaRef>,
    val backMedia: List<CardMediaRef>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("frontHtml", frontHtml)
        put("backHtml", backHtml)
        put("frontText", frontText)
        put("backText", backText)
        put("frontMedia", JSONArray().apply { frontMedia.forEach { put(it.toJson()) } })
        put("backMedia", JSONArray().apply { backMedia.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): ImportedCard = ImportedCard(
            id = json.optString("id", UUID.randomUUID().toString()),
            frontHtml = json.optString("frontHtml"),
            backHtml = json.optString("backHtml"),
            frontText = json.optString("frontText"),
            backText = json.optString("backText"),
            frontMedia = json.optJSONArray("frontMedia").toCardMediaRefList(),
            backMedia = json.optJSONArray("backMedia").toCardMediaRefList()
        )
    }
}

data class SourcePackageRef(
    val name: String,
    val relativePath: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("relativePath", relativePath)
    }

    companion object {
        fun fromJson(json: JSONObject): SourcePackageRef = SourcePackageRef(
            name = json.optString("name"),
            relativePath = json.optString("relativePath")
        )
    }
}

data class AssembledDeckSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val cardCount: Int,
    val sourcePackages: List<SourcePackageRef>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("cardCount", cardCount)
        put("sourcePackages", JSONArray().apply { sourcePackages.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): AssembledDeckSummary = AssembledDeckSummary(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Колода"),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
            cardCount = json.optInt("cardCount", 0),
            sourcePackages = json.optJSONArray("sourcePackages").toSourcePackageRefList()
        )
    }
}

data class AssembledDeck(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val cards: List<ImportedCard>,
    val sourcePackages: List<SourcePackageRef>
) {
    fun toSummary(): AssembledDeckSummary = AssembledDeckSummary(
        id = id,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        cardCount = cards.size,
        sourcePackages = sourcePackages
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("sourcePackages", JSONArray().apply { sourcePackages.forEach { put(it.toJson()) } })
        put("cards", JSONArray().apply { cards.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): AssembledDeck = AssembledDeck(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Колода"),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
            sourcePackages = json.optJSONArray("sourcePackages").toSourcePackageRefList(),
            cards = json.optJSONArray("cards").toImportedCardList()
        )
    }
}

data class CardProgress(
    var knownCount: Int = 0,
    var unknownCount: Int = 0,
    var lastAnsweredAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("knownCount", knownCount)
        put("unknownCount", unknownCount)
        put("lastAnsweredAt", lastAnsweredAt)
    }

    companion object {
        fun fromJson(json: JSONObject): CardProgress = CardProgress(
            knownCount = json.optInt("knownCount", 0),
            unknownCount = json.optInt("unknownCount", 0),
            lastAnsweredAt = json.optLong("lastAnsweredAt", 0L)
        )
    }
}

data class CardsState(
    val decks: MutableMap<String, MutableMap<String, CardProgress>> = mutableMapOf()
) {
    fun record(deckId: String, cardId: String, known: Boolean) {
        val cards = decks.getOrPut(deckId) { mutableMapOf() }
        val progress = cards.getOrPut(cardId) { CardProgress() }
        if (known) progress.knownCount += 1 else progress.unknownCount += 1
        progress.lastAnsweredAt = System.currentTimeMillis()
    }

    fun removeDeck(deckId: String) {
        decks.remove(deckId)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("decks", JSONObject().apply {
            decks.forEach { (deckId, cards) ->
                put(deckId, JSONObject().apply {
                    put("cards", JSONObject().apply {
                        cards.forEach { (cardId, progress) -> put(cardId, progress.toJson()) }
                    })
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): CardsState {
            val result = CardsState()
            val decksJson = json.optJSONObject("decks") ?: JSONObject()
            val deckKeys = decksJson.keys()
            while (deckKeys.hasNext()) {
                val deckId = deckKeys.next()
                val cardsJson = decksJson.optJSONObject(deckId)?.optJSONObject("cards") ?: JSONObject()
                val cards = mutableMapOf<String, CardProgress>()
                val cardKeys = cardsJson.keys()
                while (cardKeys.hasNext()) {
                    val cardId = cardKeys.next()
                    cards[cardId] = CardProgress.fromJson(cardsJson.optJSONObject(cardId) ?: JSONObject())
                }
                result.decks[deckId] = cards
            }
            return result
        }
    }
}

data class FoundApkgPackage(
    val name: String,
    val relativePath: String,
    val documentId: String,
    val uri: Uri,
    val type: CardPackageType = CardPackageType.APKG
)

data class ApkgImportResult(
    val deck: AssembledDeck,
    val importedCardCount: Int,
    val skippedCardCount: Int,
    val failedPackageCount: Int,
    val sourcePackageCount: Int,
    val skippedMediaCount: Int = 0
)

data class CardImportProgress(
    val message: String
)

fun mimeForCardMedia(type: CardMediaType, fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (type) {
        CardMediaType.IMAGE -> when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            else -> "image/*"
        }
        CardMediaType.AUDIO -> when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            else -> "audio/*"
        }
        CardMediaType.VIDEO -> when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            else -> "video/*"
        }
    }
}

private fun JSONArray?.toCardMediaRefList(): List<CardMediaRef> {
    if (this == null) return emptyList()
    val result = mutableListOf<CardMediaRef>()
    for (i in 0 until length()) {
        val ref = optJSONObject(i)?.let { CardMediaRef.fromJson(it) } ?: continue
        result += ref
    }
    return result
}

private fun JSONArray?.toImportedCardList(): List<ImportedCard> {
    if (this == null) return emptyList()
    val result = mutableListOf<ImportedCard>()
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        result += ImportedCard.fromJson(obj)
    }
    return result
}

private fun JSONArray?.toSourcePackageRefList(): List<SourcePackageRef> {
    if (this == null) return emptyList()
    val result = mutableListOf<SourcePackageRef>()
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        val source = SourcePackageRef.fromJson(obj)
        if (source.name.isNotBlank()) result += source
    }
    return result
}
