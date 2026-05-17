package com.prishvindt.azimut

import android.app.Activity
import android.content.ContentResolver
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

class CardImportCancelledException : Exception("Сборка колоды не завершена.")

class ApkgImporter(
    private val activity: Activity,
    private val cardStore: CardStore
) {
    private val resolver: ContentResolver = activity.contentResolver

    fun findPackages(rootTreeUri: Uri, maxDepth: Int = 4): List<FoundApkgPackage> {
        val root = rootDocumentUri(rootTreeUri)
        val result = mutableListOf<FoundApkgPackage>()
        collectApkg(root, "", 0, maxDepth, result)
        return result.sortedBy { it.relativePath.lowercase(Locale.ROOT) }
    }

    fun importPackages(
        rootTreeUri: Uri,
        assembledDeckId: String,
        deckName: String,
        packages: List<FoundApkgPackage>,
        progress: ((CardImportProgress) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): ApkgImportResult {
        val mediaSession = cardStore.openMediaWriteSession(rootTreeUri, assembledDeckId)
        val cards = mutableListOf<ImportedCard>()
        var skipped = 0
        var skippedMedia = 0
        var failed = 0

        for ((packageIndex, sourcePackage) in packages.withIndex()) {
            try {
                checkImportCancelled(isCancelled)
                reportProgress(progress, "Чтение набора: ${sourcePackage.name}")
                val sourceKey = stableSourceKey("${sourcePackage.relativePath}:$packageIndex")
                val imported = when (sourcePackage.type) {
                    CardPackageType.APKG -> importSinglePackage(mediaSession, sourcePackage, sourceKey, progress, isCancelled)
                    CardPackageType.ZIP_DECK -> importZipDeckPackage(mediaSession, sourcePackage, sourceKey, progress, isCancelled)
                }
                cards += imported.cards
                skipped += imported.skippedCardCount
                skippedMedia += imported.skippedMediaCount
            } catch (e: CardImportCancelledException) {
                throw e
            } catch (_: Exception) {
                failed += 1
            }
        }
        checkImportCancelled(isCancelled)

        val now = System.currentTimeMillis()
        val deck = AssembledDeck(
            id = assembledDeckId,
            name = deckName,
            createdAt = now,
            updatedAt = now,
            cards = cards,
            sourcePackages = packages.map { SourcePackageRef(it.name, it.relativePath) }
        )
        return ApkgImportResult(
            deck = deck,
            importedCardCount = cards.size,
            skippedCardCount = skipped,
            failedPackageCount = failed,
            sourcePackageCount = packages.size,
            skippedMediaCount = skippedMedia
        )
    }

    private fun importSinglePackage(
        mediaSession: CardStore.MediaWriteSession,
        sourcePackage: FoundApkgPackage,
        sourceKey: String,
        progress: ((CardImportProgress) -> Unit)?,
        isCancelled: () -> Boolean
    ): SinglePackageResult {
        checkImportCancelled(isCancelled)
        val tempZip = copyUriToTempFile(sourcePackage.uri, ".apkg")
        return try {
            ZipFile(tempZip).use { zip ->
                val dbEntry = findDatabaseEntry(zip) ?: throw UserVisibleException("Не найдена база карточек")
                val dbFile = copyZipEntryToTempFile(zip, dbEntry.name, ".anki2")
                try {
                    checkImportCancelled(isCancelled)
                    reportProgress(progress, "Импорт карточек...")
                    val parsed = parseCardsFromDatabase(dbFile, sourceKey)
                    val mediaEntries = readMediaEntries(zip)
                    val neededMedia = parsed.cards
                        .flatMap { it.frontMediaNames + it.backMediaNames }
                        .distinctBy { it.substringAfterLast('/').lowercase(Locale.ROOT) }
                    val copiedMedia = mutableMapOf<String, CardMediaRef>()
                    var skippedMedia = 0
                    for ((index, mediaName) in neededMedia.withIndex()) {
                        checkImportCancelled(isCancelled)
                        if (shouldReportProgress(index + 1, neededMedia.size)) {
                            reportProgress(progress, "Копирование медиа: ${index + 1} из ${neededMedia.size}")
                        }
                        val entryName = mediaEntries[mediaName]
                            ?: mediaEntries[mediaName.substringAfterLast('/')]
                        if (entryName == null) {
                            skippedMedia += 1
                            continue
                        }
                        val entry = zip.getEntry(entryName)
                        if (entry == null) {
                            skippedMedia += 1
                            continue
                        }
                        val type = CardMediaType.fromFileName(mediaName.substringAfterLast('/'))
                        if (type == null) {
                            skippedMedia += 1
                            continue
                        }
                        val storedName = storedMediaFileName(sourceKey, mediaName)
                        val copied = runCatching {
                            zip.getInputStream(entry).use { input ->
                                cardStore.saveMedia(mediaSession, storedName, type, input)
                            }
                        }.getOrNull()
                        if (copied == null) {
                            skippedMedia += 1
                        } else {
                            copiedMedia[mediaName] = copied
                            copiedMedia.putIfAbsent(mediaName.substringAfterLast('/'), copied)
                        }
                    }
                    val importedCards = parsed.cards.map { pending ->
                        val frontMedia = pending.frontMediaNames.mapNotNull { copiedMedia[it] ?: copiedMedia[it.substringAfterLast('/')] }
                        val backMedia = pending.backMediaNames.mapNotNull { copiedMedia[it] ?: copiedMedia[it.substringAfterLast('/')] }
                        ImportedCard(
                            id = pending.id,
                            frontHtml = pending.frontHtml,
                            backHtml = pending.backHtml,
                            frontText = pending.frontText,
                            backText = pending.backText,
                            frontMedia = frontMedia,
                            backMedia = backMedia
                        )
                    }
                    SinglePackageResult(importedCards, parsed.skippedCardCount, skippedMedia)
                } finally {
                    dbFile.delete()
                }
            }
        } finally {
            tempZip.delete()
        }
    }

    private fun importZipDeckPackage(
        mediaSession: CardStore.MediaWriteSession,
        sourcePackage: FoundApkgPackage,
        sourceKey: String,
        progress: ((CardImportProgress) -> Unit)?,
        isCancelled: () -> Boolean
    ): SinglePackageResult {
        checkImportCancelled(isCancelled)
        val tempZip = copyUriToTempFile(sourcePackage.uri, ".zip")
        return try {
            ZipFile(tempZip).use { zip ->
                checkImportCancelled(isCancelled)
                reportProgress(progress, "Чтение deck.json...")
                val deckEntry = findZipDeckJsonEntry(zip) ?: throw UserVisibleException("Не удалось прочитать набор карточек.")
                val deckJson = JSONObject(zip.getInputStream(deckEntry).use { it.readBytes().toString(Charsets.UTF_8) })
                if (deckJson.optString("__type__") != "Deck") throw UserVisibleException("Не удалось прочитать набор карточек.")

                val deckBase = deckEntry.name.substringBeforeLast('/', "")
                val models = readZipDeckModels(deckJson)
                val mediaEntries = readZipDeckMediaEntries(zip, deckBase)
                val parsed = parseZipDeckCards(deckJson, models, sourceKey, progress, isCancelled)
                val neededMedia = parsed.cards
                    .flatMap { it.frontMediaNames + it.backMediaNames }
                    .distinctBy { it.substringAfterLast('/').lowercase(Locale.ROOT) }
                val copiedMedia = mutableMapOf<String, CardMediaRef>()
                var skippedMedia = 0
                for ((index, mediaName) in neededMedia.withIndex()) {
                    checkImportCancelled(isCancelled)
                    if (shouldReportProgress(index + 1, neededMedia.size)) {
                        reportProgress(progress, "Копирование медиа: ${index + 1} из ${neededMedia.size}")
                    }
                    val entryName = mediaEntries[mediaName]
                        ?: mediaEntries[mediaName.substringAfterLast('/')]
                    if (entryName == null) {
                        skippedMedia += 1
                        continue
                    }
                    val entry = zip.getEntry(entryName)
                    if (entry == null) {
                        skippedMedia += 1
                        continue
                    }
                    val type = CardMediaType.fromFileName(mediaName.substringAfterLast('/'))
                    if (type == null) {
                        skippedMedia += 1
                        continue
                    }
                    val storedName = storedMediaFileName(sourceKey, mediaName)
                    val copied = runCatching {
                        zip.getInputStream(entry).use { input ->
                            cardStore.saveMedia(mediaSession, storedName, type, input)
                        }
                    }.getOrNull()
                    if (copied == null) {
                        skippedMedia += 1
                    } else {
                        copiedMedia[mediaName] = copied
                        copiedMedia.putIfAbsent(mediaName.substringAfterLast('/'), copied)
                    }
                }

                val importedCards = parsed.cards.map { pending ->
                    val frontMedia = pending.frontMediaNames.mapNotNull { copiedMedia[it] ?: copiedMedia[it.substringAfterLast('/')] }
                    val backMedia = pending.backMediaNames.mapNotNull { copiedMedia[it] ?: copiedMedia[it.substringAfterLast('/')] }
                    ImportedCard(
                        id = pending.id,
                        frontHtml = pending.frontHtml,
                        backHtml = pending.backHtml,
                        frontText = pending.frontText,
                        backText = pending.backText,
                        frontMedia = frontMedia,
                        backMedia = backMedia
                    )
                }
                SinglePackageResult(importedCards, parsed.skippedCardCount, skippedMedia)
            }
        } finally {
            tempZip.delete()
        }
    }

    private fun parseCardsFromDatabase(dbFile: File, sourceKey: String): PendingPackageResult {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            val models = readModels(db)
            val notes = readNotes(db, models)
            val cards = mutableListOf<PendingImportedCard>()
            var skipped = 0
            db.rawQuery("SELECT id, nid, ord FROM cards ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val cardId = cursor.long("id")
                        val noteId = cursor.long("nid")
                        val ord = cursor.int("ord")
                        val note = notes[noteId]
                        if (note == null) {
                            skipped += 1
                            continue
                        }
                        val model = models[note.modelId]
                        if (model == null) {
                            skipped += 1
                            continue
                        }
                        val template = model.templateFor(ord)
                        if (template == null) {
                            skipped += 1
                            continue
                        }
                        val activeCloze = ord + 1
                        val frontRaw = renderTemplate(
                            template = template.frontFormat,
                            fields = note.fields,
                            frontSide = "",
                            side = CardSide.FRONT,
                            activeCloze = activeCloze,
                            templateName = template.name
                        )
                        val backRaw = renderTemplate(
                            template = template.backFormat,
                            fields = note.fields,
                            frontSide = frontRaw,
                            side = CardSide.BACK,
                            activeCloze = activeCloze,
                            templateName = template.name
                        )
                        val frontMediaNames = extractMediaFileNames(frontRaw)
                        val backMediaNames = extractMediaFileNames(backRaw)
                            .filterNot { name -> frontMediaNames.any { it.equals(name, ignoreCase = true) } }
                        val frontHtml = cleanHtmlForDisplay(frontRaw)
                        val backHtml = cleanHtmlForDisplay(backRaw)
                        val frontText = htmlToPlainText(frontHtml)
                        val backText = htmlToPlainText(backHtml)
                        if (
                            frontText.isBlank() &&
                            backText.isBlank() &&
                            frontMediaNames.isEmpty() &&
                            backMediaNames.isEmpty()
                        ) {
                            skipped += 1
                        } else {
                            cards += PendingImportedCard(
                                id = "${sourceKey}_$cardId",
                                frontHtml = frontHtml,
                                backHtml = backHtml,
                                frontText = frontText,
                                backText = backText,
                                frontMediaNames = frontMediaNames,
                                backMediaNames = backMediaNames
                            )
                        }
                    } catch (_: Exception) {
                        skipped += 1
                    }
                }
            }
            PendingPackageResult(cards, skipped)
        } finally {
            db.close()
        }
    }

    private fun readModels(db: SQLiteDatabase): Map<Long, ApkgModel> {
        val modelsJson = db.rawQuery("SELECT models FROM col LIMIT 1", null).use { cursor ->
            if (!cursor.moveToFirst()) throw UserVisibleException("Не найдены модели карточек")
            JSONObject(cursor.getString(0))
        }
        val models = mutableMapOf<Long, ApkgModel>()
        val keys = modelsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = modelsJson.optJSONObject(key) ?: continue
            val modelId = key.toLongOrNull() ?: obj.optLong("id", 0L)
            if (modelId == 0L) continue
            val fieldNames = mutableListOf<String>()
            val flds = obj.optJSONArray("flds")
            if (flds != null) {
                for (i in 0 until flds.length()) {
                    val name = flds.optJSONObject(i)?.optString("name").orEmpty()
                    fieldNames += name.ifBlank { "Field ${i + 1}" }
                }
            }
            val templates = mutableListOf<ApkgTemplate>()
            val tmpls = obj.optJSONArray("tmpls")
            if (tmpls != null) {
                for (i in 0 until tmpls.length()) {
                    val tmpl = tmpls.optJSONObject(i) ?: continue
                    templates += ApkgTemplate(
                        ord = tmpl.optInt("ord", i),
                        name = tmpl.optString("name", "Card ${i + 1}"),
                        frontFormat = tmpl.optString("qfmt"),
                        backFormat = tmpl.optString("afmt")
                    )
                }
            }
            models[modelId] = ApkgModel(
                id = modelId,
                type = obj.optInt("type", 0),
                fieldNames = fieldNames,
                templates = templates
            )
        }
        return models
    }

    private fun readNotes(db: SQLiteDatabase, models: Map<Long, ApkgModel>): Map<Long, ApkgNote> {
        val notes = mutableMapOf<Long, ApkgNote>()
        db.rawQuery("SELECT id, mid, flds FROM notes", null).use { cursor ->
            while (cursor.moveToNext()) {
                val noteId = cursor.long("id")
                val modelId = cursor.long("mid")
                val rawFields = cursor.getString(cursor.getColumnIndexOrThrow("flds")) ?: ""
                val model = models[modelId]
                val values = rawFields.split(FIELD_SEPARATOR)
                val fields = mutableMapOf<String, String>()
                if (model != null && model.fieldNames.isNotEmpty()) {
                    model.fieldNames.forEachIndexed { index, name -> fields[name] = values.getOrElse(index) { "" } }
                } else {
                    values.forEachIndexed { index, value -> fields["Field ${index + 1}"] = value }
                }
                notes[noteId] = ApkgNote(noteId, modelId, fields)
            }
        }
        return notes
    }

    private fun readZipDeckModels(deckJson: JSONObject): Map<String, ZipDeckModel> {
        val result = mutableMapOf<String, ZipDeckModel>()
        val modelsArray = deckJson.optJSONArray("note_models") ?: JSONArray()
        for (i in 0 until modelsArray.length()) {
            val obj = modelsArray.optJSONObject(i) ?: continue
            val uuid = firstNonBlank(
                obj.optString("crowdanki_uuid"),
                obj.optString("uuid"),
                obj.optString("note_model_uuid"),
                obj.optString("id"),
                obj.optString("name")
            ) ?: continue
            val fieldNames = mutableListOf<String>()
            val fieldsArray = obj.optJSONArray("flds") ?: JSONArray()
            for (fieldIndex in 0 until fieldsArray.length()) {
                fieldNames += fieldNameFromZipField(fieldsArray.opt(fieldIndex), fieldIndex)
            }
            val templates = mutableListOf<ApkgTemplate>()
            val templatesArray = obj.optJSONArray("tmpls") ?: obj.optJSONArray("templates") ?: JSONArray()
            for (templateIndex in 0 until templatesArray.length()) {
                val template = templatesArray.optJSONObject(templateIndex) ?: continue
                templates += ApkgTemplate(
                    ord = template.optInt("ord", templateIndex),
                    name = template.optString("name", "Card ${templateIndex + 1}"),
                    frontFormat = template.optString("qfmt"),
                    backFormat = template.optString("afmt")
                )
            }
            result[uuid] = ZipDeckModel(uuid, fieldNames, templates)
        }
        return result
    }

    private fun parseZipDeckCards(
        deckJson: JSONObject,
        models: Map<String, ZipDeckModel>,
        sourceKey: String,
        progress: ((CardImportProgress) -> Unit)?,
        isCancelled: () -> Boolean
    ): PendingPackageResult {
        val notesArray = deckJson.optJSONArray("notes") ?: JSONArray()
        val cards = mutableListOf<PendingImportedCard>()
        var skipped = 0
        for (noteIndex in 0 until notesArray.length()) {
            checkImportCancelled(isCancelled)
            if (shouldReportProgress(noteIndex + 1, notesArray.length())) {
                reportProgress(progress, "Импорт карточек: ${noteIndex + 1} из ${notesArray.length()}")
            }
            val note = notesArray.optJSONObject(noteIndex)
            if (note == null) {
                skipped += 1
                continue
            }
            val modelUuid = firstNonBlank(
                note.optString("note_model_uuid"),
                note.optString("model_uuid"),
                note.optString("mid"),
                note.optString("model")
            )
            val model = modelUuid?.let { models[it] }
            if (model == null || model.templates.isEmpty()) {
                skipped += 1
                continue
            }
            val fields = fieldsFromZipNote(note, model)
            val noteId = firstNonBlank(
                note.optString("guid"),
                note.optString("uuid"),
                note.optString("crowdanki_uuid"),
                note.optString("id")
            ) ?: noteIndex.toString()
            for (template in model.templates) {
                try {
                    val activeCloze = template.ord + 1
                    val frontRaw = renderTemplate(
                        template = template.frontFormat,
                        fields = fields,
                        frontSide = "",
                        side = CardSide.FRONT,
                        activeCloze = activeCloze,
                        templateName = template.name
                    )
                    val backRaw = renderTemplate(
                        template = template.backFormat,
                        fields = fields,
                        frontSide = frontRaw,
                        side = CardSide.BACK,
                        activeCloze = activeCloze,
                        templateName = template.name
                    )
                    val frontMediaNames = extractMediaFileNames(frontRaw)
                    val backMediaNames = extractMediaFileNames(backRaw)
                        .filterNot { name -> frontMediaNames.any { it.equals(name, ignoreCase = true) } }
                    val frontHtml = cleanHtmlForDisplay(frontRaw)
                    val backHtml = cleanHtmlForDisplay(backRaw)
                    val frontText = htmlToPlainText(frontHtml)
                    val backText = htmlToPlainText(backHtml)
                    if (
                        frontText.isBlank() &&
                        backText.isBlank() &&
                        frontMediaNames.isEmpty() &&
                        backMediaNames.isEmpty()
                    ) {
                        skipped += 1
                    } else {
                        cards += PendingImportedCard(
                            id = "${sourceKey}_zip_${sanitizeIdPart(noteId)}_${template.ord}",
                            frontHtml = frontHtml,
                            backHtml = backHtml,
                            frontText = frontText,
                            backText = backText,
                            frontMediaNames = frontMediaNames,
                            backMediaNames = backMediaNames
                        )
                    }
                } catch (_: Exception) {
                    skipped += 1
                }
            }
        }
        return PendingPackageResult(cards, skipped)
    }

    private fun fieldsFromZipNote(note: JSONObject, model: ZipDeckModel): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val raw = note.opt("fields")
        when (raw) {
            is JSONObject -> {
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    fields[key] = jsonValueToString(raw.opt(key))
                }
            }
            is JSONArray -> {
                model.fieldNames.forEachIndexed { index, name ->
                    fields[name] = jsonValueToString(raw.opt(index))
                }
            }
        }
        return fields
    }

    private fun fieldNameFromZipField(value: Any?, index: Int): String {
        return when (value) {
            is JSONObject -> firstNonBlank(value.optString("name"), value.optString("field_name"))
            is String -> value
            else -> null
        } ?: "Field ${index + 1}"
    }

    private fun jsonValueToString(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> value.toString()
        }
    }

    private fun renderTemplate(
        template: String,
        fields: Map<String, String>,
        frontSide: String,
        side: CardSide,
        activeCloze: Int,
        templateName: String
    ): String {
        var result = template.replace("{{FrontSide}}", frontSide)
        result = resolveConditionalSections(result, fields, frontSide, side, activeCloze, templateName)
        return replaceTemplatePlaceholders(result, fields, frontSide, side, activeCloze, templateName)
    }

    private fun resolveConditionalSections(
        template: String,
        fields: Map<String, String>,
        frontSide: String,
        side: CardSide,
        activeCloze: Int,
        templateName: String
    ): String {
        var current = template
        repeat(8) {
            val section = findConditionalSection(current) ?: return current
            val value = valueForExpression(section.expression, fields, frontSide, side, activeCloze, templateName)
            val include = if (section.marker == '#') value.isNotBlank() else value.isBlank()
            val replacement = if (include) section.content else ""
            current = current.substring(0, section.start) + replacement + current.substring(section.end)
        }
        return current
    }

    private fun findConditionalSection(template: String): ConditionalSection? {
        var openStart = template.indexOf("{{")
        while (openStart >= 0) {
            val markerIndex = openStart + 2
            if (markerIndex >= template.length) return null
            val marker = template[markerIndex]
            if (marker != '#' && marker != '^') {
                openStart = template.indexOf("{{", openStart + 2)
                continue
            }

            val openEnd = template.indexOf("}}", markerIndex + 1)
            if (openEnd < 0) return null
            val expression = template.substring(markerIndex + 1, openEnd).trim()
            if (expression.isBlank()) {
                openStart = template.indexOf("{{", openEnd + 2)
                continue
            }

            val contentStart = openEnd + 2
            var closeStart = template.indexOf("{{/", contentStart)
            while (closeStart >= 0) {
                val closeEnd = template.indexOf("}}", closeStart + 3)
                if (closeEnd < 0) return null
                val closeExpression = template.substring(closeStart + 3, closeEnd).trim()
                if (closeExpression == expression) {
                    return ConditionalSection(
                        start = openStart,
                        end = closeEnd + 2,
                        marker = marker,
                        expression = expression,
                        content = template.substring(contentStart, closeStart)
                    )
                }
                closeStart = template.indexOf("{{/", closeEnd + 2)
            }

            openStart = template.indexOf("{{", openEnd + 2)
        }
        return null
    }

    private fun replaceTemplatePlaceholders(
        template: String,
        fields: Map<String, String>,
        frontSide: String,
        side: CardSide,
        activeCloze: Int,
        templateName: String
    ): String {
        val out = StringBuilder(template.length)
        var index = 0
        while (index < template.length) {
            if (template.startsWith("{{{", index)) {
                val end = template.indexOf("}}}", index + 3)
                if (end < 0) {
                    out.append(template.substring(index))
                    break
                }
                val expression = template.substring(index + 3, end).trim()
                out.append(valueForExpression(expression, fields, frontSide, side, activeCloze, templateName))
                index = end + 3
            } else if (template.startsWith("{{", index)) {
                val end = template.indexOf("}}", index + 2)
                if (end < 0) {
                    out.append(template.substring(index))
                    break
                }
                val expression = template.substring(index + 2, end).trim()
                if (expression.startsWith("#") || expression.startsWith("^") || expression.startsWith("/")) {
                    out.append("")
                } else {
                    out.append(valueForExpression(expression, fields, frontSide, side, activeCloze, templateName))
                }
                index = end + 2
            } else {
                out.append(template[index])
                index += 1
            }
        }
        return out.toString()
    }

    private fun valueForExpression(
        rawExpression: String,
        fields: Map<String, String>,
        frontSide: String,
        side: CardSide,
        activeCloze: Int,
        templateName: String
    ): String {
        val expression = rawExpression.trim()
        if (expression == "FrontSide") return frontSide
        if (expression == "Card") return templateName
        if (expression == "Tags" || expression == "Deck" || expression == "Subdeck" || expression == "Type") return ""
        val parts = expression.split(':').map { it.trim() }.filter { it.isNotBlank() }
        val fieldName = parts.lastOrNull().orEmpty()
        val rawValue = fields[fieldName].orEmpty()
        return when {
            parts.dropLast(1).any { it == "cloze" } -> renderClozeText(rawValue, activeCloze, side)
            parts.dropLast(1).any { it == "text" } -> htmlToPlainText(rawValue)
            parts.dropLast(1).any { it == "hint" } -> rawValue
            parts.dropLast(1).any { it == "type" } -> rawValue
            else -> rawValue
        }
    }

    private fun renderClozeText(value: String, activeCloze: Int, side: CardSide): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val start = value.indexOf("{{c", index)
            if (start < 0) {
                out.append(value.substring(index))
                break
            }
            out.append(value.substring(index, start))
            val end = value.indexOf("}}", start + 3)
            if (end < 0) {
                out.append(value.substring(start))
                break
            }
            val rendered = renderSingleCloze(value.substring(start + 2, end), activeCloze, side)
            out.append(rendered ?: value.substring(start, end + 2))
            index = end + 2
        }
        return out.toString()
    }

    private fun renderSingleCloze(expression: String, activeCloze: Int, side: CardSide): String? {
        if (!expression.startsWith("c")) return null
        var digitEnd = 1
        while (digitEnd < expression.length && expression[digitEnd].isDigit()) digitEnd += 1
        if (digitEnd == 1 || !expression.startsWith("::", digitEnd)) return null
        val number = expression.substring(1, digitEnd).toIntOrNull() ?: return null
        val body = expression.substring(digitEnd + 2)
        val hintStart = body.indexOf("::")
        val hiddenText = if (hintStart >= 0) body.substring(0, hintStart) else body
        val hint = if (hintStart >= 0) body.substring(hintStart + 2) else ""
        return if (side == CardSide.FRONT && number == activeCloze) {
            val label = hint.ifBlank { "..." }
            "<b>[$label]</b>"
        } else {
            val revealed = hiddenText.ifBlank { hint }
            if (side == CardSide.BACK && number == activeCloze) "<b>$revealed</b>" else revealed
        }
    }

    private fun extractMediaFileNames(html: String): List<String> {
        val result = linkedSetOf<String>()
        collectSoundFileNames(html).forEach { normalizeMediaName(it)?.let { name -> result += name } }
        collectAttributeValues(html, "src").forEach { normalizeMediaName(it)?.let { name -> result += name } }
        collectAttributeValues(html, "href").forEach { normalizeMediaName(it)?.let { name -> result += name } }
        collectCssUrlValues(html).forEach { normalizeMediaName(it)?.let { name -> result += name } }
        return result.toList()
    }

    private fun normalizeMediaName(value: String): String? {
        val entityDecoded = runCatching { Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString() }
            .getOrElse { value }
        val decoded = Uri.decode(entityDecoded.trim().trim('"', '\''))
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (decoded.isBlank()) return null
        val lower = decoded.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) return null
        val fileName = decoded.substringAfterLast('/').substringAfterLast('\\')
        if (CardMediaType.fromFileName(fileName) == null) return null
        return decoded
    }

    private fun cleanHtmlForDisplay(value: String): String {
        return removeTagsByNames(
            removeSoundMarkers(
                removeTagBlock(removeTagBlock(value, "script"), "style")
            ),
            setOf("img", "audio", "video", "source")
        )
            .trim()
    }

    private fun htmlToPlainText(value: String): String {
        return runCatching { Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            .getOrElse { stripHtmlTags(value).trim() }
    }

    private fun collectSoundFileNames(value: String): List<String> {
        val result = mutableListOf<String>()
        var index = indexOfIgnoreCase(value, "[sound:", 0)
        while (index >= 0) {
            val end = value.indexOf(']', index + 7)
            if (end < 0) break
            result += value.substring(index + 7, end)
            index = indexOfIgnoreCase(value, "[sound:", end + 1)
        }
        return result
    }

    private fun collectAttributeValues(value: String, attribute: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            val found = indexOfIgnoreCase(value, attribute, index)
            if (found < 0) break
            val beforeOk = found == 0 || !value[found - 1].isLetterOrDigit()
            var cursor = found + attribute.length
            val afterOk = cursor >= value.length || !value[cursor].isLetterOrDigit()
            if (!beforeOk || !afterOk) {
                index = found + attribute.length
                continue
            }
            while (cursor < value.length && value[cursor].isWhitespace()) cursor += 1
            if (cursor >= value.length || value[cursor] != '=') {
                index = found + attribute.length
                continue
            }
            cursor += 1
            while (cursor < value.length && value[cursor].isWhitespace()) cursor += 1
            if (cursor >= value.length) break
            val quote = value[cursor]
            if (quote == '"' || quote == '\'') {
                val end = value.indexOf(quote, cursor + 1)
                if (end < 0) break
                result += value.substring(cursor + 1, end)
                index = end + 1
            } else {
                val start = cursor
                while (cursor < value.length && !value[cursor].isWhitespace() && value[cursor] != '>') cursor += 1
                if (cursor > start) result += value.substring(start, cursor)
                index = cursor
            }
        }
        return result
    }

    private fun collectCssUrlValues(value: String): List<String> {
        val result = mutableListOf<String>()
        var index = indexOfIgnoreCase(value, "url(", 0)
        while (index >= 0) {
            val start = index + 4
            val end = value.indexOf(')', start)
            if (end < 0) break
            result += value.substring(start, end)
            index = indexOfIgnoreCase(value, "url(", end + 1)
        }
        return result
    }

    private fun removeSoundMarkers(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val start = indexOfIgnoreCase(value, "[sound:", index)
            if (start < 0) {
                out.append(value.substring(index))
                break
            }
            out.append(value.substring(index, start))
            val end = value.indexOf(']', start + 7)
            if (end < 0) break
            index = end + 1
        }
        return out.toString()
    }

    private fun removeTagBlock(value: String, tagName: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        val openNeedle = "<$tagName"
        val closeNeedle = "</$tagName"
        while (index < value.length) {
            val start = indexOfIgnoreCase(value, openNeedle, index)
            if (start < 0) {
                out.append(value.substring(index))
                break
            }
            out.append(value.substring(index, start))
            val closeStart = indexOfIgnoreCase(value, closeNeedle, start + openNeedle.length)
            if (closeStart < 0) break
            val closeEnd = value.indexOf('>', closeStart + closeNeedle.length)
            if (closeEnd < 0) break
            index = closeEnd + 1
        }
        return out.toString()
    }

    private fun removeTagsByNames(value: String, tagNames: Set<String>): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val start = value.indexOf('<', index)
            if (start < 0) {
                out.append(value.substring(index))
                break
            }
            val end = value.indexOf('>', start + 1)
            if (end < 0) {
                out.append(value.substring(index))
                break
            }
            val name = tagNameFromToken(value.substring(start + 1, end))
            out.append(value.substring(index, start))
            if (name !in tagNames) out.append(value.substring(start, end + 1))
            index = end + 1
        }
        return out.toString()
    }

    private fun stripHtmlTags(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val start = value.indexOf('<', index)
            if (start < 0) {
                out.append(value.substring(index))
                break
            }
            out.append(value.substring(index, start))
            val end = value.indexOf('>', start + 1)
            if (end < 0) break
            out.append(' ')
            index = end + 1
        }
        return out.toString()
    }

    private fun tagNameFromToken(token: String): String {
        var index = 0
        while (index < token.length && token[index].isWhitespace()) index += 1
        if (index < token.length && token[index] == '/') index += 1
        while (index < token.length && token[index].isWhitespace()) index += 1
        val start = index
        while (index < token.length && (token[index].isLetterOrDigit() || token[index] == '-' || token[index] == ':')) index += 1
        return token.substring(start, index).lowercase(Locale.ROOT)
    }

    private fun indexOfIgnoreCase(value: String, needle: String, startIndex: Int): Int {
        if (needle.isEmpty()) return startIndex.coerceAtMost(value.length)
        var index = startIndex.coerceAtLeast(0)
        while (index <= value.length - needle.length) {
            if (value.regionMatches(index, needle, 0, needle.length, ignoreCase = true)) return index
            index += 1
        }
        return -1
    }

    private fun readMediaEntries(zip: ZipFile): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        val mediaEntry = zip.getEntry("media")
        if (mediaEntry != null) {
            runCatching {
                val json = JSONObject(zip.getInputStream(mediaEntry).use { it.readBytes().toString(Charsets.UTF_8) })
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val fileName = json.optString(key)
                    if (fileName.isNotBlank()) {
                        mapping[fileName] = key
                        mapping.putIfAbsent(fileName.substringAfterLast('/'), key)
                    }
                }
            }
        }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            if (name == "media" || name == "collection.anki2" || name == "collection.anki21") continue
            if (CardMediaType.fromFileName(name.substringAfterLast('/')) != null) {
                mapping.putIfAbsent(name, name)
                mapping.putIfAbsent(name.substringAfterLast('/'), name)
            }
        }
        return mapping
    }

    private fun readZipDeckMediaEntries(zip: ZipFile, deckBase: String): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.replace('\\', '/')
            if (!isZipDeckMediaEntry(name, deckBase)) continue
            if (CardMediaType.fromFileName(name.substringAfterLast('/')) == null) continue
            val afterMedia = name.substringAfter("/media/", name.substringAfter("media/", name))
            mapping.putIfAbsent(afterMedia, entry.name)
            mapping.putIfAbsent(afterMedia.substringAfterLast('/'), entry.name)
            mapping.putIfAbsent(name, entry.name)
        }
        return mapping
    }

    private fun isZipDeckMediaEntry(entryName: String, deckBase: String): Boolean {
        val basePrefix = deckBase.trim('/').let { if (it.isBlank()) "" else "$it/" }
        return entryName.startsWith("${basePrefix}media/", ignoreCase = true) ||
            entryName.startsWith("media/", ignoreCase = true) ||
            entryName.contains("/media/", ignoreCase = true)
    }

    private fun findDatabaseEntry(zip: ZipFile) = zip.getEntry("collection.anki21")
        ?: zip.getEntry("collection.anki2")

    private fun findZipDeckJsonEntry(zip: ZipFile): java.util.zip.ZipEntry? {
        var fallback: java.util.zip.ZipEntry? = null
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.replace('\\', '/')
            if (!name.endsWith("/deck.json", ignoreCase = true) && !name.equals("deck.json", ignoreCase = true)) continue
            val depth = name.count { it == '/' }
            if (depth == 0 || depth == 1) return entry
            if (fallback == null) fallback = entry
        }
        return fallback
    }

    private fun copyUriToTempFile(uri: Uri, suffix: String): File {
        val file = File.createTempFile("azimut_cards_", suffix, activity.cacheDir)
        resolver.openInputStream(uri).useRequired { input ->
            FileOutputStream(file).use { out -> input.copyTo(out) }
        }
        return file
    }

    private fun copyZipEntryToTempFile(zip: ZipFile, entryName: String, suffix: String): File {
        val file = File.createTempFile("azimut_collection_", suffix, activity.cacheDir)
        zip.getInputStream(zip.getEntry(entryName)).use { input ->
            FileOutputStream(file).use { out -> input.copyTo(out) }
        }
        return file
    }

    private fun storedMediaFileName(sourceKey: String, originalName: String): String {
        val fileName = originalName.substringAfterLast('/').substringAfterLast('\\').ifBlank { UUID.randomUUID().toString() }
        val cleaned = sanitizeMediaFileName(fileName)
        return "${sourceKey}_$cleaned"
    }

    private fun sanitizeMediaFileName(value: String): String {
        return buildString(value.length) {
            for (char in value) {
                if (char.code in 0..31 || char in "\\/:*?\"<>|") append('_') else append(char)
            }
        }
    }

    private fun sanitizeIdPart(value: String): String {
        return buildString(value.length) {
            for (char in value) {
                if (char.isLetterOrDigit() || char == '-' || char == '_') append(char) else append('_')
            }
        }.ifBlank { UUID.randomUUID().toString() }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun stableSourceKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun collectApkg(
        parentUri: Uri,
        relativePrefix: String,
        depth: Int,
        maxDepth: Int,
        result: MutableList<FoundApkgPackage>
    ) {
        val children = listChildren(parentUri)
        for (child in children) {
            val relativePath = if (relativePrefix.isBlank()) child.name else "$relativePrefix/${child.name}"
            if (child.isDirectory) {
                if (depth < maxDepth) collectApkg(child.uri, relativePath, depth + 1, maxDepth, result)
            } else if (child.name.endsWith(".apkg", ignoreCase = true)) {
                result += FoundApkgPackage(child.name, relativePath, child.documentId, child.uri, CardPackageType.APKG)
            } else if (child.name.endsWith(".zip", ignoreCase = true)) {
                result += FoundApkgPackage(child.name, relativePath, child.documentId, child.uri, CardPackageType.ZIP_DECK)
            }
        }
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

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun reportProgress(progress: ((CardImportProgress) -> Unit)?, message: String) {
        progress?.invoke(CardImportProgress(message))
    }

    private fun shouldReportProgress(done: Int, total: Int): Boolean {
        return total <= 10 || done == 1 || done == total || done % 25 == 0
    }

    private fun checkImportCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CardImportCancelledException()
    }

    private data class SinglePackageResult(
        val cards: List<ImportedCard>,
        val skippedCardCount: Int,
        val skippedMediaCount: Int
    )

    private data class PendingPackageResult(
        val cards: List<PendingImportedCard>,
        val skippedCardCount: Int
    )

    private data class PendingImportedCard(
        val id: String,
        val frontHtml: String,
        val backHtml: String,
        val frontText: String,
        val backText: String,
        val frontMediaNames: List<String>,
        val backMediaNames: List<String>
    )

    private data class ApkgNote(
        val id: Long,
        val modelId: Long,
        val fields: Map<String, String>
    )

    private data class ApkgModel(
        val id: Long,
        val type: Int,
        val fieldNames: List<String>,
        val templates: List<ApkgTemplate>
    ) {
        fun templateFor(ord: Int): ApkgTemplate? {
            return templates.firstOrNull { it.ord == ord } ?: templates.getOrNull(ord) ?: templates.firstOrNull()
        }
    }

    private data class ZipDeckModel(
        val uuid: String,
        val fieldNames: List<String>,
        val templates: List<ApkgTemplate>
    )

    private data class ApkgTemplate(
        val ord: Int,
        val name: String,
        val frontFormat: String,
        val backFormat: String
    )

    private data class ConditionalSection(
        val start: Int,
        val end: Int,
        val marker: Char,
        val expression: String,
        val content: String
    )

    companion object {
        private const val FIELD_SEPARATOR = "\u001f"
    }
}
