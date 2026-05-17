package com.prishvindt.azimut

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

data class RawQuestion(
    val text: String,
    val textImages: MutableList<EmbeddedImage> = mutableListOf(),
    var comment: String = "",
    val commentImages: MutableList<EmbeddedImage> = mutableListOf(),
    val choiceOptions: MutableList<AnswerOption> = mutableListOf(),
    val freeAnswers: MutableList<String> = mutableListOf(),
    val orderOptions: MutableList<OrderAnswer> = mutableListOf(),
    var invalidReason: String? = null
) {
    fun toParsedQuestion(): ValidationResult {
        if (text.isBlank() && textImages.isEmpty()) return ValidationResult(null, "Пустой вопрос")
        if (invalidReason != null) return ValidationResult(null, invalidReason)
        val plus = freeAnswers.filter { it.isNotBlank() }
        val options = choiceOptions.filter { it.text.isNotBlank() || it.images.isNotEmpty() }
        val order = orderOptions.filter { it.order > 0 && (it.text.isNotBlank() || it.images.isNotEmpty()) }
        if (order.isNotEmpty()) {
            if (plus.isNotEmpty() || options.isNotEmpty()) return ValidationResult(null, "Смешанный тип вопроса")
            if (order.size < 2) return ValidationResult(null, "Вопрос с порядком ответов содержит меньше двух вариантов")
            if (order.map { it.order }.distinct().size != order.size) return ValidationResult(null, "Вопрос с порядком ответов содержит повторяющиеся номера")
            val correctOrder = order.sortedBy { it.order }
            return ValidationResult(
                ParsedQuestion(
                    text,
                    comment,
                    QuestionType.ORDER.value,
                    correctOrder.map { it.text },
                    correctOrder.map { it.text },
                    textImages,
                    correctOrder.map { it.images },
                    commentImages,
                    correctOrder.map { it.images }
                ),
                null
            )
        }
        val correctChoice = options.filter { it.correct }
        if (plus.isNotEmpty()) {
            if (options.isNotEmpty()) return ValidationResult(null, "Смешанный тип вопроса")
            return ValidationResult(ParsedQuestion(text, comment, QuestionType.FREE_TEXT.value, emptyList(), plus, textImages, emptyList(), commentImages, emptyList()), null)
        }
        if (options.size < 2) return ValidationResult(null, "Вопрос выбора с количеством вариантов меньше двух")
        if (correctChoice.isEmpty()) return ValidationResult(null, "Вопрос без правильного ответа")
        val type = if (correctChoice.size == 1) QuestionType.SINGLE.value else QuestionType.MULTIPLE.value
        return ValidationResult(
            ParsedQuestion(
                text,
                comment,
                type,
                options.map { it.text },
                correctChoice.map { it.text },
                textImages,
                options.map { it.images },
                commentImages,
                correctChoice.map { it.images }
            ),
            null
        )
    }
}

data class EmbeddedImage(val bytes: ByteArray, val extension: String, val mime: String)
data class ImageRef(val uri: String, val mime: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("mime", mime)
        put("name", name)
    }
    companion object {
        fun fromJson(json: JSONObject): ImageRef = ImageRef(json.optString("uri", ""), json.optString("mime", ""), json.optString("name", ""))
    }
}
data class AnswerOption(val text: String, val correct: Boolean, val images: List<EmbeddedImage> = emptyList())
data class OrderAnswer(val order: Int, val text: String, val images: List<EmbeddedImage> = emptyList())
data class ValidationResult(val question: ParsedQuestion?, val reason: String?)
data class ParseReport(val questions: List<ParsedQuestion>, val skippedReasons: List<String>)
data class SourceQuestions(val sourceName: String, val questions: List<ParsedQuestion>)

data class ParsedQuestion(
    val text: String,
    val comment: String,
    val type: String,
    val options: List<String>,
    val correctAnswers: List<String>,
    val questionImages: List<EmbeddedImage> = emptyList(),
    val optionImages: List<List<EmbeddedImage>> = emptyList(),
    val commentImages: List<EmbeddedImage> = emptyList(),
    val correctAnswerImages: List<List<EmbeddedImage>> = emptyList()
) {
    fun toAttemptQuestion(number: Int, imageSaver: (EmbeddedImage) -> ImageRef): AttemptQuestion {
        val questionImageRefs = questionImages.map(imageSaver).toMutableList()
        val commentImageRefs = commentImages.map(imageSaver).toMutableList()
        val pairs = options.mapIndexed { index, option ->
            option to optionImages.getOrElse(index) { emptyList() }.map(imageSaver).toMutableList()
        }
        val shuffledPairs = if (type == QuestionType.FREE_TEXT.value) emptyList() else pairs.shuffled(Random(System.nanoTime()))
        val correctImageRefs = correctAnswerImages.map { images -> images.map(imageSaver).toMutableList() }
        return AttemptQuestion(
            id = UUID.randomUUID().toString(),
            number = number,
            text = text,
            comment = comment,
            type = type,
            options = shuffledPairs.map { it.first }.toMutableList(),
            optionImages = shuffledPairs.map { it.second }.toMutableList(),
            correctAnswers = correctAnswers,
            correctAnswerImages = correctImageRefs.toMutableList(),
            questionImages = questionImageRefs,
            commentImages = commentImageRefs,
            selectedIndices = mutableListOf(),
            userInput = "",
            status = AnswerStatus.UNANSWERED.value,
            skipped = false
        )
    }
}

enum class QuestionType(val value: String) { SINGLE("single"), MULTIPLE("multiple"), FREE_TEXT("free_text"), ORDER("order") }
enum class AnswerStatus(val value: String) { UNANSWERED("unanswered"), CORRECT("correct"), INCORRECT("incorrect") }

data class SourceFile(val name: String, val documentId: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("documentId", documentId)
    }
    companion object {
        fun fromJson(json: JSONObject): SourceFile = SourceFile(
            json.optString("name", ""),
            json.optString("documentId", "")
        )
    }
}

data class TestTemplate(
    var title: String,
    val sourceFiles: MutableList<SourceFile>,
    val questionCount: Int,
    var strictFreeText: Boolean,
    val attempts: MutableList<AttemptResult>,
    var activeAttempt: AttemptState?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 3)
        put("title", title)
        put("sourceFiles", JSONArray().apply { sourceFiles.forEach { put(it.toJson()) } })
        put("sourceFileName", sourceFiles.firstOrNull()?.name.orEmpty())
        put("sourceFileId", sourceFiles.firstOrNull()?.documentId.orEmpty())
        put("questionCount", questionCount)
        put("strictFreeText", strictFreeText)
        put("attempts", JSONArray().apply { attempts.forEach { put(it.toJson()) } })
        if (activeAttempt == null) put("activeAttempt", JSONObject.NULL) else put("activeAttempt", activeAttempt!!.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): TestTemplate {
            val attemptsArray = json.optJSONArray("attempts") ?: JSONArray()
            val attempts = mutableListOf<AttemptResult>()
            for (i in 0 until attemptsArray.length()) attempts += AttemptResult.fromJson(attemptsArray.getJSONObject(i))
            val sources = mutableListOf<SourceFile>()
            val sourcesArray = json.optJSONArray("sourceFiles")
            if (sourcesArray != null && sourcesArray.length() > 0) {
                for (i in 0 until sourcesArray.length()) sources += SourceFile.fromJson(sourcesArray.getJSONObject(i))
            } else {
                sources += SourceFile(json.optString("sourceFileName", ""), json.optString("sourceFileId", ""))
            }
            val active = runCatching { json.optJSONObject("activeAttempt")?.let { AttemptState.fromJson(it) } }.getOrNull()
            return TestTemplate(
                title = json.optString("title", "Тест без имени"),
                sourceFiles = sources.filter { it.name.isNotBlank() }.toMutableList(),
                questionCount = json.optInt("questionCount", 0),
                strictFreeText = json.optBoolean("strictFreeText", true),
                attempts = attempts,
                activeAttempt = active
            )
        }
    }
}

data class AttemptResult(val timestampMillis: Long, val total: Int, val correct: Int, val wrong: Int, val percent: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestampMillis", timestampMillis)
        put("total", total)
        put("correct", correct)
        put("wrong", wrong)
        put("percent", percent)
    }
    companion object {
        fun fromJson(json: JSONObject): AttemptResult = AttemptResult(
            json.optLong("timestampMillis", 0L),
            json.optInt("total", 0),
            json.optInt("correct", 0),
            json.optInt("wrong", 0),
            json.optInt("percent", 0)
        )
    }
}

data class AttemptState(
    val id: String,
    val startedAtMillis: Long,
    var currentIndex: Int,
    var feedbackPending: Boolean,
    val questions: MutableList<AttemptQuestion>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("assetSchemaVersion", 1)
        put("id", id)
        put("startedAtMillis", startedAtMillis)
        put("currentIndex", currentIndex)
        put("feedbackPending", feedbackPending)
        put("questions", JSONArray().apply { questions.forEach { put(it.toJson()) } })
    }
    companion object {
        fun fromJson(json: JSONObject): AttemptState {
            if (json.optInt("assetSchemaVersion", 0) < 1) throw UserVisibleException("Незавершенная попытка старого формата")
            val arr = json.optJSONArray("questions") ?: JSONArray()
            val questions = mutableListOf<AttemptQuestion>()
            for (i in 0 until arr.length()) questions += AttemptQuestion.fromJson(arr.getJSONObject(i))
            if (questions.isEmpty()) throw UserVisibleException("Незавершенная попытка повреждена")
            return AttemptState(
                json.optString("id", UUID.randomUUID().toString()),
                json.optLong("startedAtMillis", 0L),
                json.optInt("currentIndex", 0).coerceIn(0, (questions.size - 1).coerceAtLeast(0)),
                json.optBoolean("feedbackPending", false),
                questions
            )
        }
    }
}

data class AttemptQuestion(
    val id: String,
    val number: Int,
    val text: String,
    val comment: String,
    val type: String,
    val options: MutableList<String>,
    val optionImages: MutableList<MutableList<ImageRef>>,
    val correctAnswers: List<String>,
    val correctAnswerImages: MutableList<MutableList<ImageRef>>,
    val questionImages: MutableList<ImageRef>,
    val commentImages: MutableList<ImageRef>,
    val selectedIndices: MutableList<Int>,
    var userInput: String,
    var status: String,
    var skipped: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("number", number)
        put("text", text)
        put("comment", comment)
        put("type", type)
        put("options", JSONArray().apply { options.forEach { put(it) } })
        put("optionImages", JSONArray().apply { optionImages.forEach { list -> put(JSONArray().apply { list.forEach { put(it.toJson()) } }) } })
        put("correctAnswers", JSONArray().apply { correctAnswers.forEach { put(it) } })
        put("correctAnswerImages", JSONArray().apply { correctAnswerImages.forEach { list -> put(JSONArray().apply { list.forEach { put(it.toJson()) } }) } })
        put("questionImages", JSONArray().apply { questionImages.forEach { put(it.toJson()) } })
        put("commentImages", JSONArray().apply { commentImages.forEach { put(it.toJson()) } })
        put("selectedIndices", JSONArray().apply { selectedIndices.forEach { put(it) } })
        put("userInput", userInput)
        put("status", status)
        put("skipped", skipped)
    }
    companion object {
        fun fromJson(json: JSONObject): AttemptQuestion = AttemptQuestion(
            json.optString("id", UUID.randomUUID().toString()),
            json.optInt("number", 0),
            json.optString("text", ""),
            json.optString("comment", ""),
            json.optString("type", QuestionType.SINGLE.value),
            json.optJSONArray("options").toStringList().toMutableList(),
            json.optJSONArray("optionImages").toNestedImageRefList().toMutableList(),
            json.optJSONArray("correctAnswers").toStringList(),
            json.optJSONArray("correctAnswerImages").toNestedImageRefList().toMutableList(),
            json.optJSONArray("questionImages").toImageRefList().toMutableList(),
            json.optJSONArray("commentImages").toImageRefList().toMutableList(),
            json.optJSONArray("selectedIndices").toIntList().toMutableList(),
            json.optString("userInput", ""),
            json.optString("status", AnswerStatus.UNANSWERED.value),
            json.optBoolean("skipped", false)
        )
    }
}

data class UpdateInfo(val versionName: String, val versionCode: Int, val apkUrl: String, val releaseNotes: String, val required: Boolean) {
    companion object {
        fun fromJson(json: JSONObject): UpdateInfo = UpdateInfo(
            versionName = json.optString("versionName", ""),
            versionCode = json.optInt("versionCode", 0),
            apkUrl = json.optString("apkUrl", ""),
            releaseNotes = json.optString("releaseNotes", ""),
            required = json.optBoolean("required", false)
        )
    }
}

data class DocumentInfo(val name: String, val documentId: String, val uri: Uri, val isDirectory: Boolean)
data class TestFile(val fileName: String, val uri: Uri, val template: TestTemplate)
data class TestLoadResult(val tests: List<TestFile>, val warnings: List<String>)
object SuccessOpen
object LostFolderAccess
class SuccessCreate(val warnings: List<String>)
class ErrorResult(val message: String)
class UserVisibleException(message: String) : Exception(message)
class LostFolderAccessException : IOException("Missing file")

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val list = mutableListOf<String>()
    for (i in 0 until length()) list += optString(i)
    return list
}
fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    val list = mutableListOf<Int>()
    for (i in 0 until length()) list += optInt(i)
    return list
}
fun JSONArray?.toImageRefList(): List<ImageRef> {
    if (this == null) return emptyList()
    val list = mutableListOf<ImageRef>()
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        val ref = ImageRef.fromJson(obj)
        if (ref.uri.isNotBlank()) list += ref
    }
    return list
}
fun JSONArray?.toNestedImageRefList(): List<MutableList<ImageRef>> {
    if (this == null) return emptyList()
    val list = mutableListOf<MutableList<ImageRef>>()
    for (i in 0 until length()) list += optJSONArray(i).toImageRefList().toMutableList()
    return list
}
fun mimeForImageExtension(ext: String): String? = when (ext.lowercase(Locale.ROOT)) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> null
}

fun Exception.safeMessage(): String = message ?: "Неизвестная ошибка"
inline fun <T> InputStream?.useRequired(block: (InputStream) -> T): T {
    val stream = this ?: throw LostFolderAccessException()
    return stream.use(block)
}
