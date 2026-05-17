package com.prishvindt.azimut

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.DragEvent
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var saf: SafStore
    private lateinit var cardStore: CardStore
    private lateinit var apkgImporter: ApkgImporter
    private lateinit var updateManager: UpdateManager
    private lateinit var contentFrame: FrameLayout
    private lateinit var updateArea: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var bottomActionRow: LinearLayout
    private lateinit var cardsTab: TextView
    private lateinit var testsTab: TextView
    private lateinit var menuButton: LinearLayout
    private lateinit var bottomSettingsButton: LinearLayout

    private var activeScreen = Screen.CARDS
    private var lastTabScreen = Screen.CARDS
    private var runningTestFile: TestFile? = null
    private var reviewingDeck: AssembledDeck? = null
    private var reviewingCardIndex = 0
    private var reviewingAnswerShown = false
    private var reviewingFlipAnimating = false
    private var availableUpdate: UpdateInfo? = null
    private var updateDismissedThisRun = false
    private var updateExpanded = false
    private var downloadFailureCount = 0
    private var lostFolderAccessDialogVisible = false

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (!updateManager.isExpectedDownloadComplete(id)) return
            updateManager.markDownloadComplete()
            if (updateManager.isDownloadSuccessful(id)) {
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
        private const val PREF_CHANGELOG_1_2_0_SHOWN = "updates_1_2_0_shown"
        private const val LOST_FOLDER_ACCESS_MESSAGE = "Папка с материалами утеряна. Проверьте правильность пути к папке."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        saf = SafStore(this)
        cardStore = CardStore(this)
        apkgImporter = ApkgImporter(this, cardStore)
        updateManager = UpdateManager(this, prefs)
        contentFrame = findViewById(R.id.contentFrame)
        updateArea = findViewById(R.id.updateArea)
        tabRow = findViewById(R.id.tabRow)
        bottomActionRow = findViewById(R.id.bottomActionRow)
        cardsTab = findViewById(R.id.cardsTab)
        testsTab = findViewById(R.id.testsTab)
        menuButton = findViewById(R.id.menuButton)
        bottomSettingsButton = findViewById(R.id.bottomSettingsButton)

        configureBottomActionButton(menuButton, R.drawable.ic_menu_24, "Меню")
        configureBottomActionButton(bottomSettingsButton, R.drawable.ic_settings_24, "Настройки")
        cardsTab.setOnClickListener { showCardsTab() }
        testsTab.setOnClickListener { showTestsTab() }
        menuButton.setOnClickListener { showTabMenu() }
        bottomSettingsButton.setOnClickListener { showSettingsScreen() }
        registerUpdateDownloadReceiver()
        validateSavedFolderAccess()
        showCardsTab()
        contentFrame.post {
            showUpdatesDialogIfNeeded()
            checkForUpdates(force = false)
        }
    }

    override fun onResume() {
        super.onResume()
        updateManager.installPendingAfterPermission(
            onFileNotFound = { Toast.makeText(this, "Файл обновления не найден", Toast.LENGTH_SHORT).show() },
            onPermissionRequired = {
                Toast.makeText(this, "Разрешите установку обновлений для приложения «Азимут», затем нажмите обновление ещё раз.", Toast.LENGTH_LONG).show()
            },
            onLaunchFailed = { Toast.makeText(this, "Не удалось открыть установку обновления", Toast.LENGTH_LONG).show() },
            onDebugDisabled = { Toast.makeText(this, "Проверка обновлений отключена в debug-версии.", Toast.LENGTH_SHORT).show() }
        )
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
            showTabChrome()
            showTestsTab()
        } else if (activeScreen == Screen.CARD_REVIEW) {
            reviewingDeck = null
            reviewingAnswerShown = false
            reviewingFlipAnimating = false
            showCardsTab()
        } else if (activeScreen == Screen.SETTINGS || activeScreen == Screen.STATISTICS) {
            showLastTabScreen()
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
            } catch (e: Exception) {
                if (isLostFolderError(e)) {
                    showMessage("Доступ не сохранен", LOST_FOLDER_ACCESS_MESSAGE)
                    return
                }
            }
            prefs.edit().putString(PREF_FOLDER_URI, uri.toString()).apply()
            if (!hasPersistedFolderPermission(uri)) {
                handleLostFolderAccess()
                showSettingsScreen()
                return
            }
            showTestsTab()
        }
    }

    private fun showCardsTab() {
        runningTestFile = null
        reviewingDeck = null
        reviewingAnswerShown = false
        reviewingFlipAnimating = false
        activeScreen = Screen.CARDS
        lastTabScreen = Screen.CARDS
        showTabChrome()
        renderUpdateArea()
        setNavigationSelection(Screen.CARDS)
        contentFrame.removeAllViews()

        val rootUri = savedFolderUri()
        if (rootUri == null) {
            val empty = verticalContainer().apply { gravity = Gravity.CENTER }
            empty.addView(centeredText("Сначала выберите папку с материалами.", 18, true))
            empty.addView(spacer(12))
            empty.addView(button("Открыть настройки") { showSettingsScreen() })
            contentFrame.addView(empty)
            return
        }

        val decks = try {
            withFolderAccess(rootUri) { cardStore.loadDeckSummaries(rootUri) } ?: run {
                showNoFolderSelectedState()
                return
            }
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showNoFolderSelectedState()
                return
            }
            showMessage("Ошибка", "Не удалось прочитать колоды карточек: ${e.safeMessage()}")
            emptyList()
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val box = verticalContainer()
        scroll.addView(box)

        if (decks.isEmpty()) {
            box.gravity = Gravity.CENTER
            box.addView(centeredText("Колоды карточек", 20, true))
            box.addView(spacer(8))
            box.addView(centeredText("Колоды пока не созданы.", 16, false))
            box.addView(spacer(8))
            box.addView(centeredText("Нажмите «Меню» → «Собрать колоду».", 16, false))
        } else {
            box.addView(sectionTitle("Колоды карточек"))
            decks.forEach { deck -> box.addView(deckCard(deck)) }
        }
        contentFrame.addView(scroll)
    }

    private fun showTestsTab() {
        runningTestFile = null
        reviewingDeck = null
        reviewingAnswerShown = false
        reviewingFlipAnimating = false
        activeScreen = Screen.TESTS
        lastTabScreen = Screen.TESTS
        showTabChrome()
        renderUpdateArea()
        setNavigationSelection(Screen.TESTS)
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
            if (handleLostFolderError(e)) {
                showNoFolderSelectedState()
                return
            }
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
        empty.addView(centeredText("Сначала выберите папку с материалами.", 18, true))
        empty.addView(spacer(12))
        empty.addView(button("Открыть настройки") { showSettingsScreen() })
        contentFrame.addView(empty)
    }

    private fun showSettingsScreen() {
        runningTestFile = null
        reviewingDeck = null
        reviewingAnswerShown = false
        reviewingFlipAnimating = false
        activeScreen = Screen.SETTINGS
        showStandaloneChrome()
        contentFrame.removeAllViews()

        val root = standaloneRoot("Настройки")
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val box = verticalContainer()
        scroll.addView(box)

        val uri = savedFolderUri()
        val folderName = uri?.let { folderUri ->
            withFolderAccess(folderUri) { saf.displayNameForTree(folderUri) }
        }
        val folderButtonText = if (folderName != null) "Папка: $folderName" else "Выбрать папку с материалами"

        val folderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val folderButton = rowButton(folderButtonText) { openFolderPicker() }
        val refreshButton = squareIconButton(R.drawable.ic_refresh_24, "Обновить список файлов") { refreshDocxListMessage() }
        folderRow.addView(folderButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(8), 0) })
        folderRow.addView(refreshButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        box.addView(folderRow)
        box.addView(spacer(16))

        box.addView(rowButton("Проверить обновления") { checkForUpdates(force = true) })
        box.addView(spacer(8))
        box.addView(text("Текущая версия: ${BuildConfig.VERSION_NAME}", 15, false).apply { setPadding(0, dp(4), 0, dp(4)) })
        box.addView(spacer(8))
        box.addView(rowButton("О приложении") { showAboutDialog() })
        box.addView(spacer(24))

        // Настройка темы скрыта. Логика оставлена в коде, приложение использует системную тему.

        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        contentFrame.addView(root)
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
            Toast.makeText(this, "Выберите папку с материалами.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val files = withFolderAccess(uri) { saf.listDocxFiles(uri) } ?: run {
                showSettingsScreen()
                return
            }
            val message = if (files.isEmpty()) "Новых файлов нет" else "Файлов найдено: ${files.size}"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showSettingsScreen()
                return
            }
            showMessage("Ошибка", "Не удалось обновить список файлов: ${e.safeMessage()}")
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("О приложении")
            .setMessage(
                "Название: Азимут\n" +
                    "Версия: ${BuildConfig.VERSION_NAME}\n\n" +
                    "Азимут — приложение для создания и прохождения тестов и для запоминания информации с помощью карточек."
            )
            .setNegativeButton("ОК", null)
            .setPositiveButton("Открыть GitHub") { _, _ -> openGitHubPage() }
            .showRounded()
    }

    private fun openGitHubPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/prishvindt/Azimut")))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTabMenu() {
        when (activeScreen) {
            Screen.CARDS -> showCardsMenu()
            Screen.TESTS -> showTestsMenu()
            else -> Unit
        }
    }

    private fun showTestsMenu() {
        val layout = menuDialogLayout()
        var dialog: AlertDialog? = null
        layout.addView(menuDialogRow("Создать тест") {
            dialog?.dismiss()
            chooseDocxForNewTest()
        })
        layout.addView(spacer(8))
        layout.addView(menuDialogRow("Статистика") {
            dialog?.dismiss()
            showStatisticsScreen()
        })
        dialog = AlertDialog.Builder(this)
            .setView(layout)
            .create()
        dialog.showRounded()
    }

    private fun showCardsMenu() {
        val layout = menuDialogLayout()
        var dialog: AlertDialog? = null
        layout.addView(menuDialogRow("Собрать колоду") {
            dialog?.dismiss()
            chooseApkgForNewDeck()
        })
        dialog = AlertDialog.Builder(this)
            .setView(layout)
            .create()
        dialog.showRounded()
    }

    private fun deckCard(deck: AssembledDeckSummary): View {
        val card = verticalContainer().apply {
            background = rounded(cardColor(), dp(1), borderColor(), dp(8))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(10))
            layoutParams = lp
            isClickable = true
        }
        card.addView(text(deck.name, 18, true))
        card.addView(spacer(4))
        card.addView(text("Карточек: ${deck.cardCount}", 14, false))
        card.setOnClickListener { openAssembledDeck(deck) }
        card.setOnLongClickListener {
            showDeckActions(deck)
            true
        }
        return card
    }

    private fun showDeckActions(deck: AssembledDeckSummary) {
        val actions = arrayOf("Изменить", "Удалить")
        AlertDialog.Builder(this)
            .setTitle(deck.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "Редактирование будет добавлено позже.", Toast.LENGTH_SHORT).show()
                    1 -> confirmDeleteDeck(deck)
                }
            }
            .showRounded()
    }

    private fun confirmDeleteDeck(deck: AssembledDeckSummary) {
        AlertDialog.Builder(this)
            .setTitle("Удалить собранную колоду?")
            .setMessage("Набор карточек останется в папке с материалами.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> deleteDeck(deck) }
            .showRounded()
    }

    private fun deleteDeck(deck: AssembledDeckSummary) {
        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showNoFolderSelectedState()
            return
        }
        try {
            withFolderAccess(rootUri) { cardStore.deleteDeck(rootUri, deck.id) } ?: run {
                showNoFolderSelectedState()
                return
            }
            Toast.makeText(this, "Колода удалена", Toast.LENGTH_SHORT).show()
            showCardsTab()
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showNoFolderSelectedState()
                return
            }
            showMessage("Ошибка", "Не удалось удалить колоду: ${e.safeMessage()}")
        }
    }

    private fun chooseApkgForNewDeck() {
        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showMessage("Папка не выбрана", "Сначала выберите папку с материалами.")
            return
        }
        showBusy("Поиск наборов карточек…", "Поиск наборов...")
        thread {
            val result: Any = try {
                if (!hasPersistedFolderPermission(rootUri)) throw SecurityException()
                apkgImporter.findPackages(rootUri, maxDepth = 4)
            } catch (e: Exception) {
                if (isLostFolderError(e)) LostFolderAccess else ErrorResult(e.safeMessage())
            }
            runOnUiThread {
                hideBusy()
                when (result) {
                    LostFolderAccess -> {
                        handleLostFolderAccess()
                        showNoFolderSelectedState()
                    }
                    is ErrorResult -> showMessage("Ошибка", "Не удалось найти наборы карточек: ${result.message}")
                    is List<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val packages = result as List<FoundApkgPackage>
                        if (packages.isEmpty()) {
                            showMessage(
                                "Наборы карточек не найдены.",
                                "Скачайте или поместите наборы карточек в папку с материалами, затем повторите поиск."
                            )
                        } else {
                            showApkgSelectionDialog(rootUri, packages)
                        }
                    }
                }
            }
        }
    }

    private fun showApkgSelectionDialog(rootUri: Uri, packages: List<FoundApkgPackage>) {
        val checked = BooleanArray(packages.size)
        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        packages.forEachIndexed { index, item ->
            val cb = CheckBox(this).apply {
                text = if (item.relativePath == item.name) item.name else "${item.name}\n${item.relativePath}"
                textSize = 16f
                setTextColor(textColor())
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnCheckedChangeListener { _, isChecked -> checked[index] = isChecked }
            }
            listBox.addView(cb)
            if (index != packages.lastIndex) listBox.addView(optionDivider())
        }
        val scroll = ScrollView(this).apply { addView(listBox) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Выберите наборы .apkg и .zip")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Далее", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = packages.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Выберите хотя бы один набор карточек", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    showCreateDeckDialog(rootUri, selected)
                }
            }
        }
        dialog.showRounded()
    }

    private fun showCreateDeckDialog(rootUri: Uri, packages: List<FoundApkgPackage>) {
        val layout = dialogLayout()
        val defaultName = if (packages.size == 1) packages.first().name.substringBeforeLast('.') else "Колода из ${packages.size} наборов"
        val nameInput = EditText(this).apply {
            hint = "Название колоды"
            setText(defaultName)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        layout.addView(label("Выбрано наборов"))
        layout.addView(text(packages.joinToString("\n") { "• ${it.relativePath}" }, 14, false))
        layout.addView(spacer(10))
        layout.addView(label("Название колоды"))
        layout.addView(nameInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Собрать колоду")
            .setView(layout)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = nameInput.text.toString().trim()
                if (title.isBlank()) {
                    nameInput.error = "Введите название колоды."
                    Toast.makeText(this, "Введите название колоды.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                assembleDeck(rootUri, packages, title)
            }
        }
        dialog.showRounded()
    }

    private fun assembleDeck(rootUri: Uri, packages: List<FoundApkgPackage>, title: String) {
        val cancelled = AtomicBoolean(false)
        showBusy(
            title = "Сборка колоды…",
            message = "Подготовка...",
            cancelText = "Отмена"
        ) {
            cancelled.set(true)
            updateBusy("Сборка колоды не завершена.")
        }
        thread {
            var deckId: String? = null
            val result: Any = try {
                if (!hasPersistedFolderPermission(rootUri)) throw SecurityException()
                val newDeckId = UUID.randomUUID().toString()
                deckId = newDeckId
                val progress = { value: CardImportProgress -> updateBusy(value.message) }
                val importResult = apkgImporter.importPackages(
                    rootTreeUri = rootUri,
                    assembledDeckId = newDeckId,
                    deckName = title,
                    packages = packages,
                    progress = progress,
                    isCancelled = { cancelled.get() }
                )
                if (cancelled.get()) throw CardImportCancelledException()
                if (importResult.importedCardCount == 0) {
                    cardStore.discardDeckMedia(rootUri, newDeckId)
                    ErrorResult("Не удалось прочитать набор карточек.")
                } else {
                    progress(CardImportProgress("Сохранение колоды..."))
                    cardStore.saveDeck(rootUri, importResult.deck)
                    CardImportUiResult(importResult)
                }
            } catch (e: CardImportCancelledException) {
                deckId?.let { cardStore.discardDeckMedia(rootUri, it) }
                ErrorResult("Сборка колоды не завершена.")
            } catch (e: Exception) {
                if (isLostFolderError(e)) LostFolderAccess else ErrorResult(e.safeMessage())
            }
            runOnUiThread {
                hideBusy()
                when (result) {
                    LostFolderAccess -> {
                        handleLostFolderAccess()
                        showNoFolderSelectedState()
                    }
                    is ErrorResult -> showMessage("Ошибка", result.message)
                    is CardImportUiResult -> {
                        val messages = mutableListOf<String>()
                        if (result.result.failedPackageCount > 0) messages += "Некоторые наборы карточек не удалось прочитать."
                        if (result.result.skippedCardCount > 0) messages += "Колода собрана. Некоторые карточки не удалось импортировать."
                        if (result.result.skippedMediaCount > 0) messages += "Колода собрана. Некоторые медиафайлы не удалось импортировать."
                        if (messages.isEmpty()) {
                            Toast.makeText(this, "Колода собрана.", Toast.LENGTH_SHORT).show()
                            showCardsTab()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Колода собрана")
                                .setMessage(messages.joinToString("\n\n"))
                                .setPositiveButton("ОК") { _, _ -> showCardsTab() }
                                .showRounded()
                        }
                    }
                }
            }
        }
    }

    private fun showStatisticsScreen() {
        runningTestFile = null
        activeScreen = Screen.STATISTICS
        showStandaloneChrome()
        contentFrame.removeAllViews()

        val root = standaloneRoot("Статистика")
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val box = verticalContainer()
        scroll.addView(box)

        val tests = loadTestsForStatistics().tests
        val completedAttempts = tests.flatMap { it.template.attempts }
        val activeAttemptCount = tests.count { it.template.activeAttempt != null }
        val totalAttempts = completedAttempts.size + activeAttemptCount
        val completedCount = completedAttempts.size
        val average = averagePercent(completedAttempts)
        val best = completedAttempts.maxOfOrNull { it.percent } ?: 0

        box.addView(statLine("Всего тестов", tests.size.toString()))
        box.addView(statLine("Всего попыток", totalAttempts.toString()))
        box.addView(statLine("Завершённых попыток", completedCount.toString()))
        box.addView(statLine("Средний результат", "$average%"))
        box.addView(statLine("Лучший результат", "$best%"))

        if (completedAttempts.isEmpty()) {
            box.addView(spacer(8))
            box.addView(text("Попыток пока нет.", 15, false))
        }

        box.addView(spacer(16))
        if (tests.isEmpty()) {
            box.addView(centeredText("Тесты пока не созданы.", 16, false))
        } else {
            tests.forEach { file -> box.addView(testStatsCard(file.template)) }
        }

        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        contentFrame.addView(root)
    }

    private fun loadTestsForStatistics(): TestLoadResult {
        val rootUri = savedFolderUri() ?: return TestLoadResult(emptyList(), emptyList())
        return try {
            withFolderAccess(rootUri) { saf.loadExistingTests(rootUri) } ?: TestLoadResult(emptyList(), emptyList())
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                TestLoadResult(emptyList(), emptyList())
            } else {
                showMessage("Ошибка", "Не удалось загрузить статистику: ${e.safeMessage()}")
                TestLoadResult(emptyList(), emptyList())
            }
        }
    }

    private fun chooseDocxForNewTest() {
        val uri = savedFolderUri()
        if (uri == null) {
            showMessage("Папка не выбрана", "Сначала выберите папку с материалами.")
            return
        }
        val files = try {
            withFolderAccess(uri) { saf.listDocxFiles(uri) } ?: run {
                showSettingsScreen()
                return
            }
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showSettingsScreen()
                return
            }
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
        dialog.showRounded()
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
        dialog.showRounded()
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
            } catch (e: Exception) {
                if (isLostFolderError(e)) LostFolderAccess else ErrorResult(e.safeMessage())
            }
            runOnUiThread {
                hideBusy()
                when (result) {
                    LostFolderAccess -> {
                        handleLostFolderAccess()
                        showSettingsScreen()
                    }
                    is SuccessCreate -> {
                        if (result.warnings.isNotEmpty()) {
                            AlertDialog.Builder(this)
                                .setTitle("Тест создан")
                                .setMessage(result.warnings.joinToString("\n\n"))
                                .setPositiveButton("ОК") { _, _ -> showTestsTab() }
                                .showRounded()
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
            .showRounded()
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
                } catch (e: Exception) {
                    if (handleLostFolderError(e)) {
                        showTestsTab()
                    } else {
                        showMessage("Ошибка", "Не удалось сохранить тест: ${e.safeMessage()}")
                    }
                }
            }
            .showRounded()
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
                } catch (e: Exception) {
                    if (handleLostFolderError(e)) {
                        showTestsTab()
                    } else {
                        showMessage("Ошибка", "Не удалось удалить тест: ${e.safeMessage()}")
                    }
                }
            }
            .showRounded()
    }

    private fun showStatsDialog(test: TestTemplate) {
        val message = if (test.attempts.isEmpty()) {
            "Попыток пока нет."
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
        val scroll = ScrollView(this)
        val txt = text(message, 15, false)
        txt.setPadding(dp(8), dp(8), dp(8), dp(8))
        scroll.addView(txt)
        AlertDialog.Builder(this)
            .setTitle("Статистика")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .showRounded()
    }

    private fun openTest(file: TestFile) {
        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showMessage("Папка не выбрана", "Сначала выберите папку с материалами.")
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
            } catch (e: Exception) {
                if (isLostFolderError(e)) LostFolderAccess else ErrorResult(e.safeMessage())
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
        bottomActionRow.visibility = View.GONE
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
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showTestsTab()
                return
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Тест завершен")
            .setMessage("Всего вопросов: $total\nПравильно: $correct\nНеправильно: $wrong\nРезультат: $percent%")
            .setPositiveButton("Закрыть") { _, _ ->
                runningTestFile = null
                showTabChrome()
                showTestsTab()
            }
            .setOnCancelListener {
                runningTestFile = null
                showTabChrome()
                showTestsTab()
            }
            .showRounded()
    }

    private fun persistRunning(file: TestFile): Boolean {
        try {
            saf.writeText(file.uri, file.template.toJson().toString(2))
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showTestsTab()
                return false
            } else {
                Toast.makeText(this, "Не удалось сохранить состояние попытки", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun openAssembledDeck(summary: AssembledDeckSummary) {
        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showNoFolderSelectedState()
            return
        }
        val deck = try {
            withFolderAccess(rootUri) { cardStore.loadDeck(rootUri, summary.id) } ?: run {
                showNoFolderSelectedState()
                return
            }
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showNoFolderSelectedState()
                return
            }
            showMessage("Ошибка", "Не удалось открыть колоду: ${e.safeMessage()}")
            return
        }
        if (deck.cards.isEmpty()) {
            showMessage("Колода не открыта", "В колоде нет карточек.")
            return
        }
        reviewingDeck = deck
        reviewingCardIndex = 0
        reviewingAnswerShown = false
        reviewingFlipAnimating = false
        showCardReviewScreen()
    }

    private fun showCardReviewScreen() {
        val deck = reviewingDeck ?: run {
            showCardsTab()
            return
        }
        if (reviewingCardIndex !in deck.cards.indices) {
            Toast.makeText(this, "Карточки закончились.", Toast.LENGTH_SHORT).show()
            showCardsTab()
            return
        }
        runningTestFile = null
        activeScreen = Screen.CARD_REVIEW
        lastTabScreen = Screen.CARDS
        showStandaloneChrome()
        contentFrame.removeAllViews()

        val root = standaloneRoot(deck.name)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
            setBackgroundColor(backgroundColor())
        }
        val card = deck.cards[reviewingCardIndex]

        val counter = text("Карточка ${reviewingCardIndex + 1} из ${deck.cards.size}", 15, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        body.addView(counter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val flashcard = flashcardSurface()
        populateFlashcard(flashcard, card)
        body.addView(flashcard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, dp(8), 0, dp(12))
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        buttons.addView(button("Повторить") { answerCurrentCard(known = false) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            setMargins(0, 0, dp(6), 0)
        })
        buttons.addView(button("Знаю") { answerCurrentCard(known = true) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            setMargins(dp(6), 0, 0, 0)
        })
        body.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        contentFrame.addView(root)
    }

    private fun flashcardSurface(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(cardColor(), dp(1), borderColor(), dp(18))
        elevation = dp(4).toFloat()
        setPadding(dp(18), dp(18), dp(18), dp(18))
        isClickable = true
        isFocusable = true
        cameraDistance = resources.displayMetrics.density * 8000f
    }

    private fun populateFlashcard(container: LinearLayout, card: ImportedCard) {
        container.removeAllViews()
        container.rotationY = 0f
        installFlashcardTapTarget(container, container, card)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        installFlashcardTapTarget(scroll, container, card)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        installFlashcardTapTarget(content, container, card)
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val html = if (reviewingAnswerShown) card.backHtml else card.frontHtml
        val media = if (reviewingAnswerShown) card.backMedia else card.frontMedia
        if (html.isNotBlank()) {
            val textView = htmlText(html, if (reviewingAnswerShown) 18 else 19, !reviewingAnswerShown).apply {
                movementMethod = null
                linksClickable = false
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            installFlashcardTapTarget(textView, container, card)
            content.addView(textView)
        } else {
            val empty = centeredText("Пустая сторона карточки", 16, false)
            installFlashcardTapTarget(empty, container, card)
            content.addView(empty)
        }
        addCardMediaViews(content, media, container, card)
        container.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun installFlashcardTapTarget(view: View, container: LinearLayout, card: ImportedCard) {
        view.isClickable = true
        view.setOnClickListener { flipFlashcard(container, card) }
    }

    private fun flipFlashcard(container: LinearLayout, card: ImportedCard) {
        if (reviewingFlipAnimating) return
        reviewingFlipAnimating = true
        container.isClickable = false
        container.animate()
            .rotationY(90f)
            .setDuration(140L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                reviewingAnswerShown = !reviewingAnswerShown
                populateFlashcard(container, card)
                container.rotationY = -90f
                container.animate()
                    .rotationY(0f)
                    .setDuration(160L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        reviewingFlipAnimating = false
                        container.isClickable = true
                    }
                    .start()
            }
            .start()
    }

    private fun answerCurrentCard(known: Boolean) {
        val deck = reviewingDeck ?: return
        val card = deck.cards.getOrNull(reviewingCardIndex) ?: return
        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showNoFolderSelectedState()
            return
        }
        try {
            withFolderAccess(rootUri) { cardStore.markCardAnswer(rootUri, deck.id, card.id, known) } ?: run {
                showNoFolderSelectedState()
                return
            }
        } catch (e: Exception) {
            if (handleLostFolderError(e)) {
                showNoFolderSelectedState()
                return
            }
            Toast.makeText(this, "Не удалось сохранить прогресс", Toast.LENGTH_SHORT).show()
        }
        reviewingCardIndex += 1
        reviewingAnswerShown = false
        reviewingFlipAnimating = false
        if (reviewingCardIndex >= deck.cards.size) {
            Toast.makeText(this, "Карточки закончились.", Toast.LENGTH_SHORT).show()
            showCardsTab()
        } else {
            showCardReviewScreen()
        }
    }

    private fun addCardMediaViews(parent: LinearLayout, media: List<CardMediaRef>, flipContainer: LinearLayout? = null, card: ImportedCard? = null) {
        if (media.isEmpty()) return
        parent.addView(spacer(10))
        val rootUri = savedFolderUri()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        if (flipContainer != null && card != null) installFlashcardTapTarget(row, flipContainer, card)
        for (ref in media) {
            val exists = rootUri != null && runCatching { cardStore.mediaExists(rootUri, ref) }.getOrDefault(false)
            if (exists) {
                val button = squareIconButton(mediaIconFor(ref.type), mediaDescription(ref.type)) { openCardMedia(ref) }
                row.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(8), 0) })
            } else {
                val missing = text("Файл не найден.", 13, false).apply {
                    setPadding(0, 0, dp(10), 0)
                }
                if (flipContainer != null && card != null) installFlashcardTapTarget(missing, flipContainer, card)
                row.addView(missing)
            }
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        if (flipContainer != null && card != null) installFlashcardTapTarget(scroll, flipContainer, card)
        parent.addView(scroll)
    }

    private fun openCardMedia(ref: CardMediaRef) {
        val rootUri = savedFolderUri()
        if (rootUri == null) {
            showNoFolderSelectedState()
            return
        }
        val uri = runCatching { cardStore.resolveMediaUri(rootUri, ref) }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "Не удалось открыть файл.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ref.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, ref.fileName, uri)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Не удалось открыть файл.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть файл.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mediaIconFor(type: CardMediaType): Int = when (type) {
        CardMediaType.IMAGE -> R.drawable.ic_image_24
        CardMediaType.AUDIO -> R.drawable.ic_audio_24
        CardMediaType.VIDEO -> R.drawable.ic_video_24
    }

    private fun mediaDescription(type: CardMediaType): String = when (type) {
        CardMediaType.IMAGE -> "Изображение"
        CardMediaType.AUDIO -> "Аудио"
        CardMediaType.VIDEO -> "Видео"
    }

    private fun setNavigationSelection(screen: Screen) {
        val cardsSelected = screen == Screen.CARDS
        val testsSelected = screen == Screen.TESTS

        cardsTab.background = tabBackground(cardsSelected)
        testsTab.background = tabBackground(testsSelected)

        cardsTab.setTextColor(if (cardsSelected) activeTabTextColor() else quietControlTextColor())
        testsTab.setTextColor(if (testsSelected) activeTabTextColor() else quietControlTextColor())
    }

    private fun tabBackground(selected: Boolean): GradientDrawable = if (selected) {
        rounded(activeTabFillColor(), dp(1), outlineColor(), dp(12))
    } else {
        outlineBackground(dp(12))
    }

    private var busyDialog: AlertDialog? = null
    private var busyMessageText: TextView? = null

    private fun openBusyDialog(
        title: String,
        message: String = "Подождите…",
        cancelText: String? = null,
        onCancel: (() -> Unit)? = null
    ): AlertDialog {
        val messageView = text(message, 16, false)
        busyMessageText = messageView
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(LinearLayout(this).apply {
                setPadding(dp(24), dp(18), dp(24), dp(18))
                addView(messageView)
            })
            .setCancelable(cancelText != null)
        if (cancelText != null) builder.setNegativeButton(cancelText, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            if (cancelText != null) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    onCancel?.invoke()
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            if (busyDialog == dialog) {
                busyDialog = null
                busyMessageText = null
            }
        }
        return dialog
    }

    private fun showBusy(
        title: String,
        message: String = "Подождите…",
        cancelText: String? = null,
        onCancel: (() -> Unit)? = null
    ) {
        busyDialog?.dismiss()
        busyMessageText = null
        busyDialog = openBusyDialog(title, message, cancelText, onCancel).showRounded()
    }

    private fun updateBusy(message: String) {
        runOnUiThread {
            busyMessageText?.text = message
        }
    }

    private fun hideBusy() {
        busyDialog?.dismiss()
        busyDialog = null
        busyMessageText = null
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
        if (hasPersistedFolderPermission(uri)) {
            runCatching { saf.ensureNoMediaInServiceMipmapDirs(uri) }
            return true
        }
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
        } catch (e: Exception) {
            if (handleLostFolderError(e)) null else throw e
        }
    }

    private fun handleLostFolderError(error: Throwable): Boolean {
        if (!isLostFolderError(error)) return false
        handleLostFolderAccess()
        return true
    }

    private fun handleLostFolderAccess() {
        prefs.edit().remove(PREF_FOLDER_URI).apply()
        runOnUiThread {
            runningTestFile = null
            applyChromeForCurrentScreen()
            if (lostFolderAccessDialogVisible) return@runOnUiThread
            lostFolderAccessDialogVisible = true
            AlertDialog.Builder(this)
                .setTitle("Папка не найдена")
                .setMessage(LOST_FOLDER_ACCESS_MESSAGE)
                .setPositiveButton("Выбрать папку") { _, _ -> openFolderPicker() }
                .setNegativeButton("Позже", null)
                .create()
                .apply {
                    setOnDismissListener { lostFolderAccessDialogVisible = false }
                    showRounded()
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
        } catch (e: Exception) {
            handleLostFolderError(e)
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
            } catch (e: Exception) {
                handleLostFolderError(e)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll.addView(img)
        AlertDialog.Builder(this)
            .setTitle("Изображение")
            .setView(scroll)
            .setPositiveButton("ОК", null)
            .showRounded()
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
            .showRounded()
    }

    private fun showUpdatesDialogIfNeeded() {
        if (prefs.getBoolean(PREF_CHANGELOG_1_2_0_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_CHANGELOG_1_2_0_SHOWN, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Что нового в версии 1.2.0")
            .setMessage(
                """
                Добавлено:
                - Вкладка «Карточки» с временной заглушкой.
                - Меню и настройки перенесены в нижнюю панель.
                """.trimIndent()
            )
            .setPositiveButton("ОК", null)
            .showRounded()
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
        updateManager.checkForUpdates(
            force = force,
            onUpdateAvailable = { info ->
                availableUpdate = info
                updateDismissedThisRun = false
                updateExpanded = false
                renderUpdateArea()
                if (force) Toast.makeText(this, "Доступна новая версия", Toast.LENGTH_SHORT).show()
            },
            onNoUpdate = {
                if (force) Toast.makeText(this, "Установлена актуальная версия", Toast.LENGTH_SHORT).show()
            },
            onError = {
                if (force) Toast.makeText(this, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show()
            },
            onDebugDisabled = {
                if (force) Toast.makeText(this, "Проверка обновлений отключена в debug-версии.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun renderUpdateArea() {
        updateArea.removeAllViews()
        if (runningTestFile != null || (activeScreen != Screen.CARDS && activeScreen != Screen.TESTS)) {
            updateArea.visibility = View.GONE
            return
        }
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
        updateManager.startDownload(
            info = info,
            onAlreadyDownloading = { Toast.makeText(this, "Обновление уже скачивается", Toast.LENGTH_SHORT).show() },
            onStarted = { Toast.makeText(this, "Скачивание обновления началось", Toast.LENGTH_SHORT).show() },
            onFailed = { showDownloadFailedToast() },
            onDebugDisabled = { Toast.makeText(this, "Проверка обновлений отключена в debug-версии.", Toast.LENGTH_SHORT).show() }
        )
    }

    private fun startInstallDownloadedUpdate() {
        updateManager.startInstallDownloadedUpdate(
            onFileNotFound = { Toast.makeText(this, "Файл обновления не найден", Toast.LENGTH_SHORT).show() },
            onPermissionRequired = {
                Toast.makeText(this, "Разрешите установку обновлений для приложения «Азимут», затем нажмите обновление ещё раз.", Toast.LENGTH_LONG).show()
            },
            onLaunchFailed = { Toast.makeText(this, "Не удалось открыть установку обновления", Toast.LENGTH_LONG).show() },
            onDebugDisabled = { Toast.makeText(this, "Проверка обновлений отключена в debug-версии.", Toast.LENGTH_SHORT).show() }
        )
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

    private fun averagePercent(attempts: List<AttemptResult>): Int {
        return if (attempts.isEmpty()) 0 else attempts.map { it.percent }.average().roundToInt()
    }

    private fun statLine(label: String, value: String): TextView {
        return text("$label: $value", 16, false).apply { setPadding(0, dp(3), 0, dp(3)) }
    }

    private fun testStatsCard(test: TestTemplate): View {
        val card = verticalContainer().apply {
            background = rounded(cardColor(), dp(1), borderColor(), dp(8))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(10)) }
        }
        val attempts = test.attempts
        card.addView(text(test.title, 17, true))
        card.addView(spacer(6))
        if (attempts.isEmpty()) {
            card.addView(text("Попыток пока нет.", 14, false))
        } else {
            card.addView(text("Попыток: ${attempts.size}", 14, false))
            card.addView(text("Лучший результат: ${attempts.maxOf { it.percent }}%", 14, false))
            card.addView(text("Средний результат: ${averagePercent(attempts)}%", 14, false))
            card.addView(text("Последний результат: ${attempts.last().percent}%", 14, false))
        }
        if (test.activeAttempt != null) {
            card.addView(spacer(4))
            card.addView(text("Есть незавершённая попытка", 14, true))
        }
        return card
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ОК", null).showRounded()
    }

    private fun AlertDialog.Builder.showRounded(): AlertDialog = create().showRounded()

    private fun AlertDialog.showRounded(): AlertDialog {
        show()
        window?.setBackgroundDrawable(rounded(dialogBackgroundColor(), 0, Color.TRANSPARENT, dp(16)))
        return this
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
    private fun htmlText(value: String, sp: Int, bold: Boolean): TextView = text("", sp, bold).apply {
        text = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        movementMethod = LinkMovementMethod.getInstance()
    }
    private fun centeredText(value: String, sp: Int, bold: Boolean): TextView = text(value, sp, bold).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
    private fun configureBottomActionButton(button: LinearLayout, drawableRes: Int, label: String) {
        button.apply {
            removeAllViews()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = outlineBackground(dp(12))
            isClickable = true
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)

            val icon = ImageView(this@MainActivity).apply {
                setImageResource(drawableRes)
                setColorFilter(quietControlTextColor())
            }
            val text = text(label, 16, true).apply {
                setTextColor(quietControlTextColor())
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), 0, 0, 0)
            })
        }
    }
    private fun showTabChrome() {
        tabRow.visibility = View.VISIBLE
        bottomActionRow.visibility = View.VISIBLE
    }
    private fun showStandaloneChrome() {
        tabRow.visibility = View.GONE
        bottomActionRow.visibility = View.GONE
        updateArea.visibility = View.GONE
    }
    private fun applyChromeForCurrentScreen() {
        if (activeScreen == Screen.CARDS || activeScreen == Screen.TESTS) {
            showTabChrome()
            renderUpdateArea()
        } else {
            showStandaloneChrome()
        }
    }
    private fun showLastTabScreen() {
        when (lastTabScreen) {
            Screen.TESTS -> showTestsTab()
            else -> showCardsTab()
        }
    }
    private fun standaloneRoot(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(backgroundColor())
        addView(screenHeader(title), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
    }
    private fun screenHeader(title: String): TextView = TextView(this).apply {
        text = "← $title"
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(textColor())
        setPadding(dp(16), 0, dp(16), 0)
        setBackgroundColor(backgroundColor())
        isClickable = true
        isFocusable = true
        setOnClickListener { showLastTabScreen() }
    }
    private fun menuDialogLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }
    private fun menuDialogRow(value: String, click: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = outlineBackground(dp(12))
        minimumHeight = dp(52)
        setPadding(dp(14), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }

        val label = text(value, 16, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(quietControlTextColor())
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        val arrow = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_chevron_right_24)
            setColorFilter(quietControlTextColor())
        }
        addView(label, LinearLayout.LayoutParams(0, dp(52), 1f))
        addView(arrow, LinearLayout.LayoutParams(dp(24), dp(24)))
    }
    private fun rowButton(value: String, click: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = outlineBackground(dp(12))
        minimumHeight = dp(48)
        setPadding(dp(14), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }

        val label = text(value, 15, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(quietControlTextColor())
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        val arrow = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_chevron_right_24)
            setColorFilter(quietControlTextColor())
        }
        addView(label, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(arrow, LinearLayout.LayoutParams(dp(24), dp(24)))
    }
    private fun warningBox(value: String): TextView = text(value, 14, false).apply {
        background = rounded(if (isDark()) Color.rgb(66, 55, 20) else Color.rgb(255, 248, 225), dp(1), Color.rgb(180, 130, 20), dp(10))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun button(value: String, click: () -> Unit): Button = Button(this).apply {
        text = value
        setAllCaps(false)
        setTextColor(Color.WHITE)
        background = rounded(purple(), 0, Color.TRANSPARENT, dp(10))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { click() }
    }
    private fun iconButton(drawableRes: Int, description: String, click: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(drawableRes)
        contentDescription = description
        background = rounded(purple(), 0, Color.TRANSPARENT, dp(10))
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { click() }
    }
    private fun squareIconButton(drawableRes: Int, description: String, click: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(drawableRes)
        contentDescription = description
        background = outlineBackground(dp(12))
        setColorFilter(quietControlTextColor())
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { click() }
    }
    private fun spacer(heightDp: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }
    private fun outlineBackground(radius: Int): GradientDrawable = rounded(Color.TRANSPARENT, dp(1), outlineColor(), radius)
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
    private fun activeTabFillColor(): Int = if (isDark()) Color.rgb(51, 46, 61) else Color.rgb(232, 226, 243)
    private fun activeTabTextColor(): Int = if (isDark()) Color.rgb(244, 241, 250) else Color.rgb(47, 41, 56)
    private fun quietControlTextColor(): Int = if (isDark()) Color.rgb(232, 226, 243) else Color.rgb(64, 55, 78)
    private fun outlineColor(): Int = if (isDark()) purple() else Color.rgb(185, 174, 207)
    private fun backgroundColor(): Int = if (isDark()) Color.rgb(29, 26, 36) else Color.rgb(248, 247, 250)
    private fun dialogBackgroundColor(): Int = if (isDark()) Color.rgb(42, 38, 51) else Color.WHITE
    private fun cardColor(): Int = if (isDark()) Color.rgb(42, 38, 51) else Color.WHITE
    private fun inputColor(): Int = if (isDark()) Color.rgb(37, 34, 45) else Color.WHITE
    private fun textColor(): Int = if (isDark()) Color.rgb(238, 238, 238) else Color.rgb(32, 33, 36)
    private fun hintColor(): Int = if (isDark()) Color.rgb(170, 170, 170) else Color.rgb(100, 100, 100)
    private fun borderColor(): Int = if (isDark()) Color.rgb(72, 65, 88) else Color.rgb(218, 213, 226)
    private fun isDark(): Boolean = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun formatDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")).format(Date(ms))

    private data class CardImportUiResult(val result: ApkgImportResult)

    private enum class Screen { CARDS, TESTS, SETTINGS, STATISTICS, CARD_REVIEW }
}
