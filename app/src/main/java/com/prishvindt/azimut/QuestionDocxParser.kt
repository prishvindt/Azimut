package com.prishvindt.azimut

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import java.io.InputStream
import java.util.Locale

class QuestionDocxParser {
    fun parse(input: InputStream): ParseReport {
        val questions = mutableListOf<ParsedQuestion>()
        val skipped = mutableListOf<String>()
        XWPFDocument(input).use { document ->
            val tables = document.tables
            for (table in tables) {
                var current: RawQuestion? = null
                fun flush() {
                    val raw = current ?: return
                    val validation = raw.toParsedQuestion()
                    if (validation.question != null) questions += validation.question else skipped += validation.reason ?: "Некорректный вопрос"
                    current = null
                }
                for (row in table.rows) {
                    if (row.tableCells.size < 2) continue
                    val leftCell = row.getCell(0)
                    val rightCell = row.getCell(1)
                    val left = cellText(leftCell).trim()
                    val rightRaw = cellText(rightCell)
                    val right = rightRaw.trim()
                    val images = cellImages(rightCell, skipped)
                    if (left.isBlank() && right.isBlank() && images.isEmpty()) continue

                    if (isQuestionRow(left, right, leftCell, rightCell, current == null)) {
                        flush()
                        current = RawQuestion(text = right, textImages = images.toMutableList())
                        continue
                    }

                    val active = current
                    if (active == null) {
                        if (right.isNotBlank() || images.isNotEmpty()) skipped += "Строки невозможно распознать"
                        continue
                    }

                    when {
                        left == "*" -> active.choiceOptions += AnswerOption(right, true, images)
                        left == "+" -> active.freeAnswers += right
                        left == "!" -> {
                            active.comment = right
                            active.commentImages.clear()
                            active.commentImages.addAll(images)
                        }
                        left.matches(Regex("\\d+")) -> active.orderOptions += OrderAnswer(left.toIntOrNull() ?: 0, right, images)
                        left.isBlank() -> if (right.isNotBlank() || images.isNotEmpty()) active.choiceOptions += AnswerOption(right, false, images)
                        else -> active.invalidReason = "Строки невозможно распознать"
                    }
                }
                flush()
            }
        }
        return ParseReport(questions, skipped)
    }

    private fun isQuestionRow(left: String, right: String, leftCell: XWPFTableCell, rightCell: XWPFTableCell, noActiveQuestion: Boolean): Boolean {
        if (!left.matches(Regex("\\d+")) || right.isBlank()) return false
        val highlighted = cellHasBold(leftCell) || cellHasBold(rightCell) || cellHasFill(leftCell) || cellHasFill(rightCell)
        return highlighted || noActiveQuestion
    }

    private fun cellText(cell: XWPFTableCell): String {
        return cell.paragraphs.joinToString("\n") { paragraphText(it) }
    }

    private fun paragraphText(paragraph: XWPFParagraph): String {
        val byRuns = paragraph.runs.joinToString("") { it.text() ?: "" }
        return byRuns.ifBlank { paragraph.text.orEmpty() }
    }

    private fun cellImages(cell: XWPFTableCell, skipped: MutableList<String>): List<EmbeddedImage> {
        val result = mutableListOf<EmbeddedImage>()
        for (paragraph in cell.paragraphs) {
            for (run in paragraph.runs) {
                val pictures = run.embeddedPictures
                for (picture in pictures) {
                    val data = picture.pictureData ?: continue
                    val ext = data.suggestFileExtension().lowercase(Locale.ROOT).ifBlank { "img" }
                    val mime = mimeForImageExtension(ext)
                    if (mime == null) {
                        skipped += "Некоторые изображения не удалось загрузить: неподдерживаемый формат"
                        continue
                    }
                    val bytes = data.data ?: continue
                    if (bytes.isNotEmpty()) result += EmbeddedImage(bytes, ext, mime)
                }
            }
        }
        return result
    }

    private fun cellHasBold(cell: XWPFTableCell): Boolean {
        return cell.paragraphs.any { paragraph -> paragraph.runs.any { it.isBold } }
    }

    private fun cellHasFill(cell: XWPFTableCell): Boolean {
        return try {
            val fill = cell.ctTc.tcPr?.shd?.fill?.toString()?.uppercase(Locale.ROOT)
            !fill.isNullOrBlank() && fill != "AUTO" && fill != "FFFFFF" && fill != "FFFFFF00"
        } catch (_: Exception) {
            false
        }
    }
}
