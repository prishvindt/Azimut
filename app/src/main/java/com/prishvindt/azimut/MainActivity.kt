package com.prishvindt.azimut

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var saf: SafStore
    private lateinit var contentFrame: FrameLayout
    private lateinit var updateArea: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var testsTab: TextView
    private lateinit var settingsTab: TextView

    private var activeScreen = Screen.TESTS
    private var runningTestFile: TestFile? = null
    private var availableUpdate: UpdateInfo? = null
    private var updateDismissedThisRun = false
    private var updateExpanded = false
    private var downloadInProgress = false
    private var downloadFailureCount = 0
    private var pendingInstallAfterPermission = false
    private var updateDownloadId: Long = -1L
    private var lostFolderAccessDialogVisible = false

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val expectedId = if (updateDownloadId > 0L) updateDownloadId else prefs.getLong(PREF_LAST_DOWNLOAD_ID, -1L)
            if (id <= 0L || id != expectedId) return
            downloadInProgress = false
            if (isDownloadSuccessful(id)) {
                downloadFailureCount = 0
                Toast.makeText(this@MainActivity, "Обновление скачано", Toast.LENGTH_SHORT).show()
                startInstallDownloadedUpdate()
            } else {
                showDownloadFailedToast()
            }
        }
    }

    companion object {
        private const val REQ_OPEN_TREE = 4100
        private const val PREFS_NAME = "azimut_prefs"
        private const val PREF_FOLDER_URI = "question_folder_uri"
        private const val PREF_THEME = "theme_mode"
        private const val PREF_CHANGELOG_1_1_1_SHOWN = "updates_1_1_1_shown"
        private const val PREF_LAST_UPDATE_CHECK = "last_update_check_millis"
        private const val PREF_LAST_DOWNLOAD_ID = "last_update_download_id"
        private const val PREF_PENDING_INSTALL_AFTER_PERMISSION = "pending_install_after_permission"
        private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/prishvindt/Azimut/main/update.json"
        private const val UPDATE_APK_FILE_NAME = "Azimut-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val LOST_FOLDER_ACCESS_MESSAGE = "Доступ к папке потерян. Выберите папку с вопросами заново."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        saf = SafStore(this)
        contentFrame = findViewById(R.id.contentFrame)
        updateArea = findViewById(R.id.updateArea)
        tabRow = findViewById(R.id.tabRow)
        testsTab = findViewById(R.id.testsTab)
        settingsTab = findViewById(R.id.settingsTab)

        testsTab.setOnClickListener { showTestsTab() }
        settingsTab.setOnClickListener { showSettingsTab() }
        registerUpdateDownloadReceiver()
        validateSavedFolderAccess()
        showTestsTab()
        contentFrame.post {
            showUpdatesDialogIfNeeded()
            checkForUpdates(force = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingInstallAfterPermission || prefs.getBoolean(PREF_PENDING_INSTALL_AFTER_PERMISSION, false)) {
            if (canRequestPackageInstalls()) {
                pendingInstallAfterPermission = false
                prefs.edit().putBoolean(PREF_PENDING_INSTALL_AFTER_PERMISSION, false).apply()
                startInstallDownloadedUpdate()
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(updateDownloadReceiver) }
        super.onDestroy()
    }

    private fun applySavedTheme() {
        // Настройка темы намеренно скрыта. Приложение всегда следует системной теме.
        setTheme(R.style.AppTheme)
    }

    override fun onBackPressed() {
        if (runningTestFile != null) {
            runningTestFile = null
            tabRow.visibility = View.VISIBLE
            showTestsTab()
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                showMessage("Доступ не сохранен", LOST_FOLDER_ACCESS_MESSAGE)
                return
            } catch (_: Exception) {
            }
            prefs.edit().putString(PREF_FOLDER_URI, uri.toString()).apply()
            if (!hasPersistedFolderPermission(uri)) {
                handleLostFolderAccess()
                showSettingsTab()
                return
            }
            showTestsTab()
        }
    }

    private fun showTestsTab() {
        runningTestFile = null
        activeScreen = Screen.TESTS
        tabRow.visibility = View.VISIBLE
        renderUpdateArea()
        setTabSelection(true)
        contentFrame.removeAllViews()

        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showNoFolderSelectedState()
            return
        }

        val list = try {
            withFolderAccess(rootUri) { saf.loadTests(rootUri) } ?: run {
                showNoFolderSelectedState()
                return
            }
        } catch (e: Exception) {
            showMessage("Ошибка", "Не удалось прочитать папку test: ${e.safeMessage()}")
            TestLoadResult(emptyList(), listOf("Не удалось прочитать папку test"))
        }

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val box = verticalContainer()
        scroll.addView(box)

        if (list.warnings.isNotEmpty()) {
            box.addView(warningBox(list.warnings.distinct().joinToString("\n")))
            box.addView(spacer(10))
        }

        if (list.tests.isEmpty()) {
            val msg = text("Созданных тестов нет", 18, true)
            msg.gravity = Gravity.CENTER
            box.gravity = Gravity.CENTER
            box.addView(msg)
        } else {
            for (file in list.tests) box.addView(testCard(file))
        }
        contentFrame.addView(scroll)
    }

    private fun showNoFolderSelectedState() {
        contentFrame.removeAllViews()
        val empty = verticalContainer()
        empty.gravity = Gravity.CENTER
        empty.addView(text("Папка с вопросами не выбрана", 18, true))
        empty.addView(spacer(12))
        empty.addView(button("Открыть параметры") { showSettingsTab() })
        contentFrame.addView(empty)
    }

    private fun showSettingsTab() {
        runningTestFile = null
        activeScreen = Screen.SETTINGS
        tabRow.visibility = View.VISIBLE
        renderUpdateArea()
        setTabSelection(false)
        contentFrame.removeAllViews()

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val box = verticalContainer()
        scroll.addView(box)

        box.addView(sectionTitle("Папка с вопросами"))
        box.addView(button("Выбрать папку с вопросами") { openFolderPicker() })
        box.addView(spacer(8))

        val uri = savedFolderUri()
        val folderName = uri?.let { folderUri ->
            withFolderAccess(folderUri) { saf.displayNameForTree(folderUri) }
        }
        box.addView(text(if (folderName != null) "Выбрана папка: $folderName" else "Папка не выбрана", 16, false))
        box.addView(spacer(16))

        box.addView(button("Обновить список файлов") { refreshDocxListMessage() })
        box.addView(spacer(8))
        box.addView(button("Создать тест") { chooseDocxForNewTest() })
        box.addView(spacer(24))

        box.addView(sectionTitle("Обновления"))
        box.addView(text("Текущая версия: ${BuildConfig.VERSION_NAME}", 15, false))
        box.addView(spacer(8))
        box.addView(button("Проверить обновления") { checkForUpdates(force = true) })
        box.addView(spacer(24))

        // Настройка темы скрыта. Логика оставлена в коде, приложение использует системную тему.

        contentFrame.addView(scroll)
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_OPEN_TREE)
    }

    private fun refreshDocxListMessage() {
        val uri = savedFolderUri()
        if (uri == null) {
            showMessage("Папка не выбрана", "Сначала выберите папку с вопросами.")
            return
        }
        try {
            val files = withFolderAccess(uri) { saf.listDocxFiles(uri) } ?: run {
                showSettingsTab()
                return
            }
            val message = if (files.isEmpty()) {
                "В выбранной папке нет файлов .docx."
            } else {
                "Найдено файлов .docx: ${files.size}\n\n" + files.joinToString("\n") { it.name }
            }
            showMessage("Список файлов", message)
        } catch (e: Exception) {
            showMessage("Ошибка", "Не удалось обновить список файлов: ${e.safeMessage()}")
        }
    }

    private fun chooseDocxForNewTest() {
        val uri = savedFolderUri()
        if (uri == null) {
            showMessage("Папка не выбрана", "Сначала выберите папку с вопросами.")
            return
        }
        val files = try {
            withFolderAccess(uri) { saf.listDocxFiles(uri) } ?: run {
                showSettingsTab()
                return
            }
        } catch (e: Exception) {
            showMessage("Ошибка", "Не удалось прочитать папку: ${e.safeMessage()}")
            return
        }
        if (files.isEmpty()) {
            showMessage("Файлы не найдены", "В выбранной папке нет файлов .docx.")
            return
        }
        val checked = BooleanArray(files.size)
        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        files.forEachIndexed { index, file ->
            val cb = CheckBox(this).apply {
                text = file.name
                textSize = 16f
                setTextColor(textColor())
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnCheckedChangeListener { _, isChecked -> checked[index] = isChecked }
            }
            listBox.addView(cb)
            if (index != files.lastIndex) listBox.addView(optionDivider())
        }
        val scroll = ScrollView(this).apply { addView(listBox) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Выберите .docx-файлы")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Далее", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = files.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Выберите хотя бы один .docx-файл", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    showCreateTestDialog(uri, selected)
                }
            }
        }
        dialog.show()
    }

    private fun showCreateTestDialog(rootUri: Uri, sources: List<DocumentInfo>) {
        val layout = dialogLayout()
        val defaultName = if (sources.size == 1) sources.first().name.substringBeforeLast('.') else "Тест из ${sources.size} файлов"
        val nameInput = EditText(this).apply {
            hint = "Название теста"
            setText(defaultName)
            setSingleLine(true)
        }
        val countInput = EditText(this).apply {
            hint = "Количество вопросов"
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText("30")
            setSingleLine(true)
        }
        val strictSwitch = Switch(this).apply {
            text = "Строгий режим"
            textSize = 15f
            setTextColor(textColor())
            setPadding(0, dp(8), 0, dp(4))
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                if (checked) showStrictModeExplanation()
            }
        }
        layout.addView(label("Выбрано файлов"))
        layout.addView(text(sources.joinToString("\n") { "• ${it.name}" }, 14, false))
        layout.addView(spacer(10))
        layout.addView(label("Название теста"))
        layout.addView(nameInput)
        layout.addView(spacer(8))
        layout.addView(label("Количество вопросов"))
        layout.addView(countInput)
        layout.addView(spacer(8))
        layout.addView(strictSwitch)
        layout.addView(text("Строгий режим включен по умолчанию. Отключите его, если нужен свободный режим сравнения ответа.", 13, false))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Параметры теста")
            .setView(layout)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = nameInput.text.toString().trim().ifEmpty { defaultName }
                val requested = countInput.text.toString().toIntOrNull()
                if (requested == null || requested <= 0) {
                    countInput.error = "Введите число больше нуля"
                    return@setOnClickListener
                }
                dialog.dismiss()
                createTestFromDocx(rootUri, sources, title, requested, strictSwitch.isChecked)
            }
        }
        dialog.show()
    }

    private fun createTestFromDocx(rootUri: Uri, sources: List<DocumentInfo>, title: String, requestedCount: Int, strictFreeText: Boolean) {
        showBusy("Создание теста…")
        thread {
            val result = try {
                if (!hasPersistedFolderPermission(rootUri)) throw SecurityException()
                val parsed = mutableListOf<SourceQuestions>()
                val warnings = mutableListOf<String>()
                for (source in sources) {
                    val report = contentResolver.openInputStream(source.uri).useRequired { stream -> QuestionDocxParser().parse(stream) }
                    parsed += SourceQuestions(source.name, report.questions)
                    if (report.skippedReasons.isNotEmpty()) warnings += "${source.name}:\n${skippedSummary(report.skippedReasons)}"
                }
                val totalAvailable = parsed.sumOf { it.questions.size }
                if (totalAvailable == 0) throw UserVisibleException("В выбранных файлах нет корректных вопросов.")
                val finalCount = minOf(requestedCount, totalAvailable)
                val template = TestTemplate(
                    title = title,
                    sourceFiles = sources.map { SourceFile(it.name, it.documentId) }.toMutableList(),
                    questionCount = finalCount,
                    strictFreeText = strictFreeText,
                    attempts = mutableListOf(),
                    activeAttempt = null
                )
                val fileName = "test_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
                saf.createTestFile(rootUri, fileName, template.toJson().toString(2))
                if (requestedCount > totalAvailable) warnings.add(0, "Запрошено вопросов: $requestedCount, доступно корректных: $totalAvailable. Тест создан на $finalCount вопросов.")
                SuccessCreate(warnings)
            } catch (e: SecurityException) {
                LostFolderAccess
            } catch (e: Exception) {
                ErrorResult(e.safeMessage())
            }
            runOnUiThread {
                hideBusy()
                when (result) {
                    LostFolderAccess -> {
                        handleLostFolderAccess()
                        showSettingsTab()
                    }
                    is SuccessCreate -> {
                        if (result.warnings.isNotEmpty()) {
                            AlertDialog.Builder(this)
                                .setTitle("Тест создан")
                                .setMessage(result.warnings.joinToString("\n\n"))
                                .setPositiveButton("ОК") { _, _ -> showTestsTab() }
                                .show()
                        } else {
                            Toast.makeText(this, "Тест создан", Toast.LENGTH_SHORT).show()
                            showTestsTab()
                        }
                    }
                    is ErrorResult -> showMessage("Ошибка создания теста", result.message)
                }
            }
        }
    }

    private fun testCard(file: TestFile): View {
        val test = file.template
        val card = verticalContainer().apply {
            background = rounded(cardColor(), dp(1), borderColor(), dp(14))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(10))
            layoutParams = lp
            isClickable = true
        }
        card.addView(text(test.title, 18, true))
        card.addView(spacer(4))
        val sourceText = if (test.sourceFiles.size == 1) "Файл: ${test.sourceFiles.first().name}" else "Файлы: ${test.sourceFiles.size}"
        card.addView(text(sourceText, 14, false))
        card.addView(text("Вопросов: ${test.questionCount}", 14, false))
        card.addView(text(if (test.strictFreeText) "Строгий режим" else "Свободный режим", 14, false))
        card.addView(text(shortStats(test), 14, false))
        if (test.activeAttempt != null) {
            val active = text("Есть незавершенная попытка", 14, true)
            active.setTextColor(if (isDark()) Color.WHITE else purple())
            card.addView(spacer(4))
            card.addView(active)
        }
        card.setOnClickListener { openTest(file) }
        card.setOnLongClickListener {
            showTestActions(file)
            true
        }
        return card
    }

    private fun showTestActions(file: TestFile) {
        val actions = arrayOf("Изменить", "Удалить", "Статистика")
        AlertDialog.Builder(this)
            .setTitle(file.template.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showEditTestDialog(file)
                    1 -> confirmDeleteTest(file)
                    2 -> showStatsDialog(file.template)
                }
            }
            .show()
    }

    private fun showEditTestDialog(file: TestFile) {
        val layout = dialogLayout()
        val nameInput = EditText(this).apply {
            setText(file.template.title)
            setSingleLine(true)
        }
        val countInput = EditText(this).apply {
            setText(file.template.questionCount.toString())
            isEnabled = false
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val strictSwitch = Switch(this).apply {
            text = "Строгий режим"
            textSize = 15f
            setTextColor(textColor())
            isChecked = file.template.strictFreeText
            setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> if (checked) showStrictModeExplanation() }
        }
        layout.addView(label("Название теста"))
        layout.addView(nameInput)
        layout.addView(spacer(8))
        layout.addView(label("Количество вопросов"))
        layout.addView(countInput)
        layout.addView(spacer(8))
        layout.addView(label("Исходные файлы"))
        layout.addView(text(file.template.sourceFiles.joinToString("\n") { "• ${it.name}" }, 14, false))
        layout.addView(spacer(8))
        layout.addView(strictSwitch)
        AlertDialog.Builder(this)
            .setTitle("Изменить тест")
            .setView(layout)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val newTitle = nameInput.text.toString().trim().ifEmpty { file.template.title }
                file.template.title = newTitle
                file.template.strictFreeText = strictSwitch.isChecked
                try {
                    saf.writeText(file.uri, file.template.toJson().toString(2))
                    showTestsTab()
                } catch (e: SecurityException) {
                    handleLostFolderAccess()
                    showTestsTab()
                } catch (e: Exception) {
                    showMessage("Ошибка", "Не удалось сохранить тест: ${e.safeMessage()}")
                }
            }
            .show()
    }

    private fun confirmDeleteTest(file: TestFile) {
        AlertDialog.Builder(this)
            .setTitle("Удалить тест?")
            .setMessage("Будет удален только файл созданного теста. Исходный .docx-файл не изменится.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                try {
                    savedFolderUri()?.let { saf.deleteAssetsForTest(it, file.fileName) }
                    DocumentsContract.deleteDocument(contentResolver, file.uri)
                    showTestsTab()
                } catch (e: SecurityException) {
                    handleLostFolderAccess()
                    showTestsTab()
                } catch (e: Exception) {
                    showMessage("Ошибка", "Не удалось удалить тест: ${e.safeMessage()}")
                }
            }
            .show()
    }

    private fun showStatsDialog(test: TestTemplate) {
        val filesBlock = "Исходные файлы:\n" + test.sourceFiles.mapIndexed { idx, src -> "${idx + 1}. ${src.name}" }.joinToString("\n")
        val attemptsBlock = if (test.attempts.isEmpty()) {
            "Попыток: 0"
        } else {
            test.attempts.mapIndexed { idx, attempt ->
                "Попытка ${idx + 1}\n" +
                    "Дата: ${formatDate(attempt.timestampMillis)}\n" +
                    "Всего: ${attempt.total}\n" +
                    "Правильно: ${attempt.correct}\n" +
                    "Неправильно: ${attempt.wrong}\n" +
                    "Результат: ${attempt.percent}%"
            }.joinToString("\n\n")
        }
        val modeBlock = if (test.strictFreeText) "Строгий режим" else "Свободный режим"
        val message = "$filesBlock\n\n$modeBlock\n\n$attemptsBlock"
        val scroll = ScrollView(this)
        val txt = text(message, 15, false)
        txt.setPadding(dp(8), dp(8), dp(8), dp(8))
        scroll.addView(txt)
        AlertDialog.Builder(this)
            .setTitle("Статистика")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun openTest(file: TestFile) {
        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showMessage("Папка не выбрана", "Сначала выберите папку с вопросами.")
            return
        }
        val template = file.template
        if (template.activeAttempt != null) {
            runningTestFile = file
            showRunScreen(file)
            return
        }

        showBusy("Подготовка попытки…")
        thread {
            val result = try {
                if (!hasPersistedFolderPermission(rootUri)) throw SecurityException()
                val missing = mutableListOf<String>()
                val parsedSources = mutableListOf<SourceQuestions>()
                for (source in template.sourceFiles) {
                    val sourceUri = saf.findSourceFile(rootUri, source)
                    if (sourceUri == null) {
                        missing += source.name
                    } else {
                        val report = contentResolver.openInputStream(sourceUri).useRequired { stream -> QuestionDocxParser().parse(stream) }
                        parsedSources += SourceQuestions(source.name, report.questions)
                    }
                }
                if (missing.isNotEmpty()) throw UserVisibleException("Не найдены исходные файлы:\n" + missing.joinToString("\n"))
                if (parsedSources.sumOf { it.questions.size } == 0) throw UserVisibleException("В исходных файлах нет корректных вопросов.")
                val selected = selectQuestionsProportionally(parsedSources, template.questionCount)
                val attemptDir = saf.resetAttemptAssets(rootUri, file.fileName)
                var imageIndex = 0
                val attemptQuestions = selected.mapIndexed { index, q ->
                    q.toAttemptQuestion(index + 1) { image -> saf.saveEmbeddedImage(attemptDir, image, ++imageIndex) }
                }.toMutableList()
                template.activeAttempt = AttemptState(
                    id = UUID.randomUUID().toString(),
                    startedAtMillis = System.currentTimeMillis(),
                    currentIndex = 0,
                    feedbackPending = false,
                    questions = attemptQuestions
                )
                saf.writeText(file.uri, template.toJson().toString(2))
                SuccessOpen
            } catch (e: SecurityException) {
                LostFolderAccess
            } catch (e: Exception) {
                ErrorResult(e.safeMessage())
            }
            runOnUiThread {
                hideBusy()
                when (result) {
                    LostFolderAccess -> {
                        handleLostFolderAccess()
                        showTestsTab()
                    }
                    SuccessOpen -> {
                        runningTestFile = file
                        showRunScreen(file)
                    }
                    is ErrorResult -> showMessage("Тест не запущен", result.message)
                    else -> Unit
                }
            }
        }
    }

    private fun showRunScreen(file: TestFile) {
        val attempt = file.template.activeAttempt ?: return
        runningTestFile = file
        tabRow.visibility = View.GONE
        updateArea.visibility = View.GONE
        contentFrame.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(backgroundColor())
        }

        val circlesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val circlesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        circlesScroll.addView(circlesRow)
        root.addView(circlesScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        val current = attempt.questions.getOrNull(attempt.currentIndex) ?: return
        for ((index, question) in attempt.questions.withIndex()) {
            val circle = TextView(this).apply {
                text = (index + 1).toString()
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = 14f
                setTextColor(Color.WHITE)
                background = circleBackground(question.status, index == attempt.currentIndex)
                val lp = LinearLayout.LayoutParams(dp(40), dp(40))
                lp.setMargins(dp(4), 0, dp(4), 0)
                layoutParams = lp
                isClickable = question.status == AnswerStatus.UNANSWERED.value && question.skipped
                alpha = if (isClickable || index == attempt.currentIndex) 1.0f else 0.82f
                setOnClickListener {
                    if (question.status == AnswerStatus.UNANSWERED.value && question.skipped) {
                        attempt.currentIndex = index
                        attempt.feedbackPending = false
                        if (!persistRunning(file)) return@setOnClickListener
                        showRunScreen(file)
                    }
                }
            }
            circlesRow.addView(circle)
        }
        circlesScroll.post {
            val child = circlesRow.getChildAt(attempt.currentIndex)
            if (child != null) circlesScroll.smoothScrollTo(child.left - (circlesScroll.width - child.width) / 2, 0)
        }

        val questionScroll = ScrollView(this).apply { isFillViewport = true }
        val qBox = verticalContainer().apply { setPadding(0, dp(8), 0, dp(8)) }
        questionScroll.addView(qBox)
        root.addView(questionScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        qBox.addView(text("Вопрос ${attempt.currentIndex + 1} из ${attempt.questions.size}", 15, true))
        qBox.addView(spacer(8))
        val questionText = if (current.comment.isNotBlank()) current.text + "\n\n" + current.comment else current.text
        qBox.addView(text(questionText, 19, true))
        addImages(qBox, current.questionImages)
        addImages(qBox, current.commentImages)
        qBox.addView(spacer(16))

        val answerButton = button("Ответить") {}
        val skipButton = button("Пропустить") {}
        val nextButton = button("Далее") {}
        val feedbackBox = verticalContainer().apply {
            visibility = View.GONE
            background = rounded(if (isDark()) Color.rgb(60, 32, 32) else Color.rgb(255, 235, 238), dp(1), Color.rgb(180, 40, 40), dp(10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val wrongText = text("Не верно", 17, true).apply { setTextColor(Color.rgb(190, 30, 30)) }
        val correctAnswersContainer = verticalContainer().apply { setPadding(0, 0, 0, 0) }
        feedbackBox.addView(wrongText)
        feedbackBox.addView(spacer(6))
        feedbackBox.addView(correctAnswersContainer)
        feedbackBox.addView(spacer(10))
        feedbackBox.addView(nextButton)

        var hasAnswer = false
        val selectedIndices = mutableSetOf<Int>()
        var freeInput: EditText? = null
        val orderOptions = current.options.toMutableList()
        val orderOptionImages = current.optionImages.map { it.toMutableList() }.toMutableList()

        fun updateAnswerButton() {
            answerButton.isEnabled = hasAnswer && !attempt.feedbackPending
            answerButton.alpha = if (answerButton.isEnabled) 1f else 0.45f
        }

        when (current.type) {
            QuestionType.SINGLE.value -> {
                val radios = mutableListOf<RadioButton>()
                current.options.forEachIndexed { idx, option ->
                    val rb = RadioButton(this).apply {
                        text = option
                        textSize = 16f
                        setTextColor(textColor())
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        isChecked = current.selectedIndices.contains(idx)
                    }
                    radios += rb
                    rb.setOnClickListener {
                        radios.forEach { it.isChecked = false }
                        rb.isChecked = true
                        selectedIndices.clear()
                        selectedIndices += idx
                        hasAnswer = true
                        updateAnswerButton()
                    }
                    if (rb.isChecked) selectedIndices += idx
                    qBox.addView(rb)
                    addImages(qBox, current.optionImages.getOrNull(idx).orEmpty())
                    if (idx != current.options.lastIndex) qBox.addView(optionDivider())
                }
                hasAnswer = selectedIndices.isNotEmpty()
            }
            QuestionType.MULTIPLE.value -> {
                current.options.forEachIndexed { idx, option ->
                    val cb = CheckBox(this).apply {
                        text = option
                        textSize = 16f
                        setTextColor(textColor())
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        isChecked = current.selectedIndices.contains(idx)
                    }
                    if (cb.isChecked) selectedIndices += idx
                    cb.setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedIndices += idx else selectedIndices -= idx
                        hasAnswer = selectedIndices.isNotEmpty()
                        updateAnswerButton()
                    }
                    qBox.addView(cb)
                    addImages(qBox, current.optionImages.getOrNull(idx).orEmpty())
                    if (idx != current.options.lastIndex) qBox.addView(optionDivider())
                }
                hasAnswer = selectedIndices.isNotEmpty()
            }
            QuestionType.FREE_TEXT.value -> {
                freeInput = EditText(this).apply {
                    hint = "Введите ответ"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    setSingleLine(false)
                    minLines = 2
                    setText(current.userInput)
                    setTextColor(textColor())
                    setHintTextColor(hintColor())
                    background = rounded(inputColor(), dp(1), borderColor(), dp(8))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            hasAnswer = !s.isNullOrEmpty()
                            updateAnswerButton()
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
                hasAnswer = current.userInput.isNotEmpty()
                qBox.addView(freeInput)
            }
            QuestionType.ORDER.value -> {
                qBox.addView(text("Расположите ответы в правильном порядке. Зажмите строку и перетащите её выше или ниже.", 15, false))
                qBox.addView(spacer(8))
                val orderContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(inputColor(), dp(1), borderColor(), dp(8))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                fun renderOrderRows() {
                    orderContainer.removeAllViews()
                    orderOptions.forEachIndexed { idx, option ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            background = rounded(cardColor(), 0, Color.TRANSPARENT, dp(6))
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            val rowText = text("≡  ${idx + 1}. $option", 16, false)
                            addView(rowText)
                            addImages(this, orderOptionImages.getOrNull(idx).orEmpty())
                            setOnLongClickListener {
                                val clip = ClipData.newPlainText("orderIndex", idx.toString())
                                startDragAndDrop(clip, View.DragShadowBuilder(this), idx, 0)
                                true
                            }
                        }
                        orderContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        if (idx != orderOptions.lastIndex) orderContainer.addView(optionDivider())
                    }
                }
                orderContainer.setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DROP -> {
                            val from = event.localState as? Int ?: return@setOnDragListener true
                            val rowCount = orderOptions.size
                            if (from !in 0 until rowCount) return@setOnDragListener true
                            var to = rowCount - 1
                            for (i in 0 until rowCount) {
                                val child = orderContainer.getChildAt(i * 2) ?: continue
                                if (event.y < child.top + child.height / 2f) {
                                    to = i
                                    break
                                }
                            }
                            if (to != from) {
                                val item = orderOptions.removeAt(from)
                                val itemImages = orderOptionImages.removeAt(from)
                                val target = to.coerceIn(0, orderOptions.size)
                                orderOptions.add(target, item)
                                orderOptionImages.add(target, itemImages)
                                current.options.clear()
                                current.options.addAll(orderOptions)
                                current.optionImages.clear()
                                current.optionImages.addAll(orderOptionImages)
                                renderOrderRows()
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_STARTED, DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> true
                        else -> true
                    }
                }
                renderOrderRows()
                qBox.addView(orderContainer)
                hasAnswer = orderOptions.isNotEmpty()
            }
        }
        qBox.addView(spacer(14))
        qBox.addView(feedbackBox)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        bottom.addView(skipButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        bottom.addView(answerButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        root.addView(bottom)
        contentFrame.addView(root)

        fun showWrongFeedback() {
            answerButton.visibility = View.GONE
            skipButton.visibility = View.GONE
            correctAnswersContainer.removeAllViews()
            addCorrectAnswersViews(correctAnswersContainer, current)
            feedbackBox.visibility = View.VISIBLE
        }

        if (attempt.feedbackPending && current.status == AnswerStatus.INCORRECT.value) {
            showWrongFeedback()
        }

        answerButton.setOnClickListener {
            if (current.type == QuestionType.ORDER.value) {
                current.options.clear()
                current.options.addAll(orderOptions)
                current.optionImages.clear()
                current.optionImages.addAll(orderOptionImages)
            }
            val correct = when (current.type) {
                QuestionType.SINGLE.value, QuestionType.MULTIPLE.value -> {
                    val selectedTexts = selectedIndices.mapNotNull { current.options.getOrNull(it) }.toSet()
                    selectedTexts == current.correctAnswers.toSet()
                }
                QuestionType.FREE_TEXT.value -> isFreeTextCorrect(freeInput?.text?.toString().orEmpty(), current.correctAnswers, file.template.strictFreeText)
                QuestionType.ORDER.value -> current.options == current.correctAnswers
                else -> false
            }
            current.selectedIndices.clear()
            current.selectedIndices.addAll(selectedIndices.sorted())
            current.userInput = if (current.type == QuestionType.ORDER.value) current.options.joinToString("\n") else freeInput?.text?.toString().orEmpty()
            if (correct) {
                current.status = AnswerStatus.CORRECT.value
                attempt.feedbackPending = false
                if (!persistRunning(file)) return@setOnClickListener
                moveAfterAnswerOrFinish(file)
            } else {
                current.status = AnswerStatus.INCORRECT.value
                attempt.feedbackPending = true
                if (!persistRunning(file)) return@setOnClickListener
                showRunScreen(file)
            }
        }
        skipButton.setOnClickListener {
            current.skipped = true
            attempt.feedbackPending = false
            val unanswered = attempt.questions.filter { it.status == AnswerStatus.UNANSWERED.value }
            if (unanswered.all { it.skipped }) Toast.makeText(this, "Чтобы завершить тест, нужно ответить на все вопросы", Toast.LENGTH_SHORT).show()
            moveToNextUnanswered(file)
        }
        nextButton.setOnClickListener {
            attempt.feedbackPending = false
            if (!persistRunning(file)) return@setOnClickListener
            moveAfterAnswerOrFinish(file)
        }
        updateAnswerButton()
    }

    private fun moveAfterAnswerOrFinish(file: TestFile) {
        val attempt = file.template.activeAttempt ?: return
        if (attempt.questions.all { it.status != AnswerStatus.UNANSWERED.value } && !attempt.feedbackPending) {
            finishAttempt(file)
        } else {
            moveToNextUnanswered(file)
        }
    }

    private fun moveToNextUnanswered(file: TestFile) {
        val attempt = file.template.activeAttempt ?: return
        val questions = attempt.questions
        val nextForward = ((attempt.currentIndex + 1) until questions.size).firstOrNull { questions[it].status == AnswerStatus.UNANSWERED.value }
        val nextAny = questions.indices.firstOrNull { questions[it].status == AnswerStatus.UNANSWERED.value }
        val next = nextForward ?: nextAny
        if (next == null) {
            finishAttempt(file)
            return
        }
        attempt.currentIndex = next
        if (!persistRunning(file)) return
        showRunScreen(file)
    }

    private fun finishAttempt(file: TestFile) {
        val test = file.template
        val attempt = test.activeAttempt ?: return
        val total = attempt.questions.size
        val correct = attempt.questions.count { it.status == AnswerStatus.CORRECT.value }
        val wrong = total - correct
        val percent = if (total == 0) 0 else ((correct.toDouble() / total) * 100).roundToInt()
        val result = AttemptResult(System.currentTimeMillis(), total, correct, wrong, percent)
        test.attempts += result
        test.activeAttempt = null
        savedFolderUri()?.let { saf.deleteAttemptAssets(it, file.fileName) }
        try {
            saf.writeText(file.uri, test.toJson().toString(2))
        } catch (e: SecurityException) {
            handleLostFolderAccess()
            showTestsTab()
            return
        } catch (_: Exception) {
        }
        AlertDialog.Builder(this)
            .setTitle("Тест завершен")
            .setMessage("Всего вопросов: $total\nПравильно: $correct\nНеправильно: $wrong\nРезультат: $percent%")
            .setPositiveButton("Закрыть") { _, _ ->
                runningTestFile = null
                tabRow.visibility = View.VISIBLE
                showTestsTab()
            }
            .setOnCancelListener {
                runningTestFile = null
                tabRow.visibility = View.VISIBLE
                showTestsTab()
            }
            .show()
    }

    private fun persistRunning(file: TestFile): Boolean {
        try {
            saf.writeText(file.uri, file.template.toJson().toString(2))
        } catch (e: SecurityException) {
            handleLostFolderAccess()
            showTestsTab()
            return false
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось сохранить состояние попытки", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun setTabSelection(testsSelected: Boolean) {
        testsTab.background = tabBackground(testsSelected)
        settingsTab.background = tabBackground(!testsSelected)
        testsTab.setTextColor(if (testsSelected) Color.WHITE else textColor())
        settingsTab.setTextColor(if (!testsSelected) Color.WHITE else textColor())
    }

    private fun tabBackground(selected: Boolean): GradientDrawable = if (selected) {
        rounded(purple(), 0, Color.TRANSPARENT, dp(12))
    } else {
        rounded(Color.TRANSPARENT, dp(1), borderColor(), dp(12))
    }

    private fun openBusyDialog(message: String): AlertDialog = AlertDialog.Builder(this)
        .setTitle(message)
        .setView(LinearLayout(this).apply { setPadding(dp(24), dp(18), dp(24), dp(18)); addView(text("Подождите…", 16, false)) })
        .setCancelable(false)
        .create()

    private var busyDialog: AlertDialog? = null
    private fun showBusy(message: String) {
        busyDialog?.dismiss()
        busyDialog = openBusyDialog(message).also { it.show() }
    }
    private fun hideBusy() {
        busyDialog?.dismiss()
        busyDialog = null
    }

    private fun savedFolderUri(): Uri? {
        val uri = rawSavedFolderUri() ?: return null
        if (!hasPersistedFolderPermission(uri)) {
            handleLostFolderAccess()
            return null
        }
        return uri
    }

    private fun rawSavedFolderUri(): Uri? {
        return prefs.getString(PREF_FOLDER_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun validateSavedFolderAccess(): Boolean {
        val uri = rawSavedFolderUri() ?: return true
        if (hasPersistedFolderPermission(uri)) return true
        handleLostFolderAccess()
        return false
    }

    private fun hasPersistedFolderPermission(uri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }

    private fun <T> withFolderAccess(rootUri: Uri, action: () -> T): T? {
        if (!hasPersistedFolderPermission(rootUri)) {
            handleLostFolderAccess()
            return null
        }
        return try {
            action()
        } catch (e: SecurityException) {
            handleLostFolderAccess()
            null
        }
    }

    private fun handleLostFolderAccess() {
        prefs.edit().remove(PREF_FOLDER_URI).apply()
        runOnUiThread {
            runningTestFile = null
            tabRow.visibility = View.VISIBLE
            if (lostFolderAccessDialogVisible) return@runOnUiThread
            lostFolderAccessDialogVisible = true
            AlertDialog.Builder(this)
                .setTitle("Доступ к папке потерян")
                .setMessage(LOST_FOLDER_ACCESS_MESSAGE)
                .setPositiveButton("Выбрать папку") { _, _ -> openFolderPicker() }
                .setNegativeButton("Позже", null)
                .create()
                .apply {
                    setOnDismissListener { lostFolderAccessDialogVisible = false }
                    show()
                }
        }
    }

    private fun isFreeTextCorrect(userAnswer: String, correctAnswers: List<String>, strict: Boolean): Boolean {
        return if (strict) {
            correctAnswers.any { it == userAnswer }
        } else {
            val typed = normalizeFreeAnswerLoose(userAnswer)
            correctAnswers.any { normalizeFreeAnswerLoose(it) == typed }
        }
    }

    private fun normalizeFreeAnswerLoose(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}\\p{S}]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString(" ")
    }

    private fun formatCorrectAnswers(question: AttemptQuestion): String {
        return if (question.type == QuestionType.ORDER.value) {
            "Правильный порядок:\n" + question.correctAnswers.mapIndexed { idx, answer -> "${idx + 1}. $answer" }.joinToString("\n")
        } else {
            "Правильные ответы:\n" + question.correctAnswers.joinToString("\n")
        }
    }

    private fun addImages(parent: LinearLayout, images: List<ImageRef>) {
        images.filter { it.uri.isNotBlank() }.forEach { ref ->
            parent.addView(spacer(8))
            parent.addView(imageView(ref))
        }
    }

    private fun imageView(ref: ImageRef): ImageView = ImageView(this).apply {
        adjustViewBounds = true
        maxHeight = dp(360)
        scaleType = ImageView.ScaleType.FIT_CENTER
        try {
            setImageURI(Uri.parse(ref.uri))
        } catch (e: SecurityException) {
            handleLostFolderAccess()
        }
        background = rounded(inputColor(), dp(1), borderColor(), dp(8))
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener { showImageDialog(ref) }
    }

    private fun showImageDialog(ref: ImageRef) {
        val scroll = ScrollView(this)
        val img = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            try {
                setImageURI(Uri.parse(ref.uri))
            } catch (e: SecurityException) {
                handleLostFolderAccess()
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll.addView(img)
        AlertDialog.Builder(this)
            .setTitle("Изображение")
            .setView(scroll)
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun addCorrectAnswersViews(parent: LinearLayout, question: AttemptQuestion) {
        if (question.type == QuestionType.ORDER.value) {
            parent.addView(text("Правильный порядок:", 15, true))
            question.correctAnswers.forEachIndexed { idx, answer ->
                parent.addView(text("${idx + 1}. $answer", 15, false))
                addImages(parent, question.correctAnswerImages.getOrNull(idx).orEmpty())
            }
        } else {
            parent.addView(text("Правильные ответы:", 15, true))
            question.correctAnswers.forEachIndexed { idx, answer ->
                parent.addView(text(answer, 15, false))
                val optionIndex = question.options.indexOf(answer)
                val images = if (optionIndex >= 0) question.optionImages.getOrNull(optionIndex).orEmpty() else question.correctAnswerImages.getOrNull(idx).orEmpty()
                addImages(parent, images)
            }
        }
    }

    private fun optionDivider(): View = View(this).apply {
        setBackgroundColor(if (isDark()) Color.argb(90, 255, 255, 255) else Color.argb(90, 0, 0, 0))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(3), 0, dp(3)) }
    }

    private fun showStrictModeExplanation() {
        AlertDialog.Builder(this)
            .setTitle("Строгий режим свободного ввода")
            .setMessage(
                """
                Строгий режим включен. Ответ будет засчитан только если он полностью совпадает с одним из вариантов, указанных в исходном .docx-файле под знаком “+”.

                Свободный режим

                Если строгий режим выключен, приложение сравнивает ответ по набору слов: регистр, знаки препинания и порядок слов не учитываются.
                """.trimIndent()
            )
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun showUpdatesDialogIfNeeded() {
        if (prefs.getBoolean(PREF_CHANGELOG_1_1_1_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_CHANGELOG_1_1_1_SHOWN, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Что нового в версии 1.1.1")
            .setMessage(
                """
                Исправлено:
                - Улучшена обработка доступа к выбранной папке с вопросами.
                - Если Android отозвал доступ к папке, приложение теперь показывает понятное сообщение и предлагает выбрать папку заново.
                """.trimIndent()
            )
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun registerUpdateDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateDownloadReceiver, filter)
        }
    }

    private fun checkForUpdates(force: Boolean) {
        if (BuildConfig.DEBUG) {
            if (force) {
                Toast.makeText(this, "Проверка обновлений отключена в debug-версии.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!force) {
            val lastCheck = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0L)
            if (System.currentTimeMillis() - lastCheck < ONE_DAY_MILLIS) return
        }
        thread {
            var failed = false
            val result = try {
                val info = fetchUpdateInfo()
                if (!force) prefs.edit().putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                info
            } catch (_: Exception) {
                failed = true
                if (!force) prefs.edit().putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                null
            }
            runOnUiThread {
                if (result != null && result.versionCode > BuildConfig.VERSION_CODE && result.apkUrl.isNotBlank()) {
                    availableUpdate = result
                    updateDismissedThisRun = false
                    updateExpanded = false
                    renderUpdateArea()
                    if (force) Toast.makeText(this, "Доступна новая версия", Toast.LENGTH_SHORT).show()
                } else if (force && failed) {
                    Toast.makeText(this, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show()
                } else if (force) {
                    Toast.makeText(this, "Установлена актуальная версия", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchUpdateInfo(): UpdateInfo {
        val connection = (URL(UPDATE_JSON_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw UserVisibleException("Не удалось проверить обновления")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            UpdateInfo.fromJson(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    private fun renderUpdateArea() {
        updateArea.removeAllViews()
        val info = availableUpdate
        if (info == null || updateDismissedThisRun) {
            updateArea.visibility = View.GONE
            return
        }
        updateArea.visibility = View.VISIBLE

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(223, 245, 225), 0, Color.TRANSPARENT, 0)
        }
        var downX = 0f
        banner.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val delta = event.rawX - downX
                    if (kotlin.math.abs(delta) > dp(90)) {
                        updateDismissedThisRun = true
                        updateExpanded = false
                        renderUpdateArea()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        val title = TextView(this).apply {
            text = "Доступна версия ${info.versionName}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(31, 61, 42))
        }
        val download = TextView(this).apply {
            text = "⬇"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(31, 61, 42))
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { startUpdateDownload(info) }
        }
        val arrow = TextView(this).apply {
            text = if (updateExpanded) "▲" else "▼"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(31, 61, 42))
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener {
                updateExpanded = !updateExpanded
                renderUpdateArea()
            }
        }
        banner.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        banner.addView(download, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        banner.addView(arrow, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
        updateArea.addView(banner)

        if (updateExpanded) updateArea.addView(updateDetailsView(info))
    }

    private fun updateDetailsView(info: UpdateInfo): View {
        val box = verticalContainer().apply {
            background = rounded(if (isDark()) Color.rgb(38, 48, 40) else Color.rgb(241, 251, 242), dp(1), Color.rgb(160, 215, 170), 0)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val title = text("Что нового в ${info.versionName}", 18, true)
        title.setTextColor(if (isDark()) Color.WHITE else Color.rgb(31, 61, 42))
        box.addView(title)
        box.addView(spacer(8))
        val notes = text(info.releaseNotes.ifBlank { "Описание изменений не указано." }, 15, false)
        notes.setTextColor(if (isDark()) Color.WHITE else Color.rgb(31, 61, 42))
        box.addView(notes)
        box.addView(spacer(14))
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val ok = button("ОК") {
            updateExpanded = false
            renderUpdateArea()
        }
        val install = button("Установить") { startUpdateDownload(info) }
        buttons.addView(ok, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        buttons.addView(install, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        box.addView(buttons)
        return box
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        if (downloadInProgress || isDownloadRunning()) {
            Toast.makeText(this, "Обновление уже скачивается", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null) File(dir, UPDATE_APK_FILE_NAME).delete()
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Азимут ${info.versionName}")
            setDescription("Скачивание обновления")
            setMimeType(APK_MIME)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_FILE_NAME)
        }
        try {
            val id = manager.enqueue(request)
            updateDownloadId = id
            downloadInProgress = true
            prefs.edit().putLong(PREF_LAST_DOWNLOAD_ID, id).apply()
            Toast.makeText(this, "Скачивание обновления началось", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            downloadInProgress = false
            showDownloadFailedToast()
        }
    }

    private fun isDownloadRunning(): Boolean {
        val id = prefs.getLong(PREF_LAST_DOWNLOAD_ID, -1L)
        if (id <= 0L) return false
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
        }
    }

    private fun isDownloadSuccessful(id: Long): Boolean {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    private fun startInstallDownloadedUpdate() {
        val id = prefs.getLong(PREF_LAST_DOWNLOAD_ID, -1L)
        if (id <= 0L) {
            Toast.makeText(this, "Файл обновления не найден", Toast.LENGTH_SHORT).show()
            return
        }
        if (!canRequestPackageInstalls()) {
            pendingInstallAfterPermission = true
            prefs.edit().putBoolean(PREF_PENDING_INSTALL_AFTER_PERMISSION, true).apply()
            Toast.makeText(this, "Разрешите установку обновлений для приложения «Азимут», затем нажмите обновление ещё раз.", Toast.LENGTH_LONG).show()
            openInstallPermissionSettings()
            return
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) {
            Toast.makeText(this, "Файл обновления не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть установку обновления", Toast.LENGTH_LONG).show()
        }
    }

    private fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.canRequestPackageInstalls() else true
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun showDownloadFailedToast() {
        downloadFailureCount += 1
        val message = if (downloadFailureCount == 1) {
            "Не удалось скачать обновление, попробуйте снова."
        } else {
            "Не удалось скачать обновление, попробуйте позднее."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun selectQuestionsProportionally(sources: List<SourceQuestions>, requested: Int): List<ParsedQuestion> {
        val available = sources.filter { it.questions.isNotEmpty() }
        val totalAvailable = available.sumOf { it.questions.size }
        val target = minOf(requested, totalAvailable)
        if (target <= 0) return emptyList()
        val allocations = mutableMapOf<SourceQuestions, Int>()
        val fractions = mutableListOf<Pair<SourceQuestions, Double>>()
        var allocated = 0
        for (source in available) {
            val exact = target.toDouble() * source.questions.size.toDouble() / totalAvailable.toDouble()
            val base = exact.toInt().coerceAtMost(source.questions.size)
            allocations[source] = base
            allocated += base
            fractions += source to (exact - base)
        }
        var remaining = target - allocated
        while (remaining > 0) {
            val candidate = fractions
                .map { it.first }
                .filter { (allocations[it] ?: 0) < it.questions.size }
                .maxByOrNull { fractions.first { pair -> pair.first == it }.second }
                ?: break
            allocations[candidate] = (allocations[candidate] ?: 0) + 1
            remaining--
        }
        val selected = mutableListOf<ParsedQuestion>()
        for ((source, count) in allocations) {
            selected += source.questions.shuffled(Random(System.nanoTime())).take(count)
        }
        if (selected.size < target) {
            val already = selected.toSet()
            val extra = available.flatMap { it.questions }.filter { it !in already }.shuffled(Random(System.nanoTime())).take(target - selected.size)
            selected += extra
        }
        return selected.shuffled(Random(System.nanoTime()))
    }

    private fun skippedSummary(reasons: List<String>): String {
        val counts = reasons.groupingBy { it }.eachCount()
        return "Пропущено вопросов: ${reasons.size}\n" + counts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }

    private fun shortStats(test: TestTemplate): String {
        if (test.attempts.isEmpty()) return "Попыток: 0"
        val best = test.attempts.maxOf { it.percent }
        val last = test.attempts.last().percent
        return "Попыток: ${test.attempts.size}, лучший: $best%, последний: $last%"
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ОК", null).show()
    }

    private fun verticalContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setBackgroundColor(backgroundColor())
    }

    private fun dialogLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), 0)
    }

    private fun sectionTitle(value: String): TextView = text(value, 18, true).apply { setPadding(0, dp(8), 0, dp(8)) }
    private fun label(value: String): TextView = text(value, 14, true)
    private fun text(value: String, sp: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = sp.toFloat()
        setTextColor(textColor())
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
    }
    private fun warningBox(value: String): TextView = text(value, 14, false).apply {
        background = rounded(if (isDark()) Color.rgb(66, 55, 20) else Color.rgb(255, 248, 225), dp(1), Color.rgb(180, 130, 20), dp(10))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun button(value: String, click: () -> Unit): Button = Button(this).apply {
        text = value
        setTextColor(Color.WHITE)
        background = rounded(purple(), 0, Color.TRANSPARENT, dp(10))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { click() }
    }
    private fun spacer(heightDp: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }
    private fun rounded(fill: Int, strokeWidth: Int, strokeColor: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
    private fun circleBackground(status: String, current: Boolean): GradientDrawable {
        val color = when (status) {
            AnswerStatus.CORRECT.value -> Color.rgb(46, 125, 50)
            AnswerStatus.INCORRECT.value -> Color.rgb(198, 40, 40)
            else -> Color.rgb(117, 117, 117)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (current) setStroke(dp(3), purple())
        }
    }
    private fun purple(): Int = getColor(R.color.graphite_accent)
    private fun backgroundColor(): Int = if (isDark()) Color.rgb(29, 26, 36) else Color.rgb(248, 247, 250)
    private fun cardColor(): Int = if (isDark()) Color.rgb(42, 38, 51) else Color.WHITE
    private fun inputColor(): Int = if (isDark()) Color.rgb(37, 34, 45) else Color.WHITE
    private fun textColor(): Int = if (isDark()) Color.rgb(238, 238, 238) else Color.rgb(32, 33, 36)
    private fun hintColor(): Int = if (isDark()) Color.rgb(170, 170, 170) else Color.rgb(100, 100, 100)
    private fun borderColor(): Int = if (isDark()) Color.rgb(72, 65, 88) else Color.rgb(218, 213, 226)
    private fun isDark(): Boolean = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun formatDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")).format(Date(ms))

    private enum class Screen { TESTS, SETTINGS }
}

class SafStore(private val activity: Activity) {
    private val resolver: ContentResolver = activity.contentResolver

    fun displayNameForTree(treeUri: Uri): String {
        return queryName(rootDocumentUri(treeUri)) ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':')
    }

    fun listDocxFiles(rootTreeUri: Uri): List<DocumentInfo> {
        return listChildren(rootDocumentUri(rootTreeUri))
            .filter { !it.isDirectory && it.name.endsWith(".docx", ignoreCase = true) && !it.name.startsWith("~$") }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun loadTests(rootTreeUri: Uri): TestLoadResult {
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
            } catch (_: Exception) {
                warnings += "Некоторые тесты не удалось загрузить: ${file.name}"
            }
        }
        return TestLoadResult(tests.sortedBy { it.template.title.lowercase(Locale.ROOT) }, warnings)
    }

    fun createTestFile(rootTreeUri: Uri, name: String, content: String): Uri {
        val testDir = ensureTestDir(rootTreeUri)
        val uri = DocumentsContract.createDocument(resolver, testDir, "application/json", name)
            ?: throw UserVisibleException("Не удалось создать JSON-файл теста.")
        writeText(uri, content)
        return uri
    }


    fun resetAttemptAssets(rootTreeUri: Uri, testFileName: String): Uri {
        val assets = ensureAssetsDir(rootTreeUri, testFileName)
        ensureNoMedia(assets)
        val existing = listChildren(assets).firstOrNull { it.isDirectory && it.name == "attempt" }
        if (existing != null) deleteDocumentTree(existing.uri)
        val attempt = DocumentsContract.createDocument(resolver, assets, DocumentsContract.Document.MIME_TYPE_DIR, "attempt")
            ?: throw UserVisibleException("Не удалось создать папку изображений попытки.")
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
            ?: throw UserVisibleException("Не удалось сохранить изображение вопроса.")
        resolver.openOutputStream(uri, "wt")?.use { it.write(normalized.bytes) }
            ?: throw UserVisibleException("Нет доступа на запись изображения.")
        return ImageRef(uri.toString(), normalized.mime, name)
    }

    fun writeText(uri: Uri, content: String) {
        resolver.openOutputStream(uri, "wt")?.use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw UserVisibleException("Нет доступа на запись.")
    }

    fun readText(uri: Uri): String {
        return resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw UserVisibleException("Нет доступа на чтение.")
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
            ?: throw UserVisibleException("Не удалось создать папку изображений теста.")
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
        } catch (_: Exception) {
        }
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
        } catch (_: Exception) {
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
        val root = rootDocumentUri(rootTreeUri)
        val existing = listChildren(root).firstOrNull { it.isDirectory && it.name == "test" }
        if (existing != null) return existing.uri
        return DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "test")
            ?: throw UserVisibleException("Не удалось создать подпапку test.")
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
    val stream = this ?: throw UserVisibleException("Нет доступа на чтение файла.")
    return stream.use(block)
}
