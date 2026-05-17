package com.prishvindt.azimut

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class UpdateManager(
    private val activity: Activity,
    private val prefs: SharedPreferences
) {
    private var downloadInProgress = false
    private var pendingInstallAfterPermission = false
    private var updateDownloadId: Long = -1L

    fun checkForUpdates(
        force: Boolean,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: () -> Unit,
        onError: () -> Unit,
        onDebugDisabled: () -> Unit
    ) {
        if (BuildConfig.DEBUG) {
            onDebugDisabled()
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
            activity.runOnUiThread {
                if (result != null && result.versionCode > BuildConfig.VERSION_CODE && result.apkUrl.isNotBlank()) {
                    onUpdateAvailable(result)
                } else if (failed) {
                    onError()
                } else {
                    onNoUpdate()
                }
            }
        }
    }

    fun startDownload(
        info: UpdateInfo,
        onAlreadyDownloading: () -> Unit,
        onStarted: () -> Unit,
        onFailed: () -> Unit,
        onDebugDisabled: () -> Unit
    ) {
        if (BuildConfig.DEBUG) {
            onDebugDisabled()
            return
        }
        if (downloadInProgress || isDownloadRunning()) {
            onAlreadyDownloading()
            return
        }
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null) File(dir, UPDATE_APK_FILE_NAME).delete()
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Азимут ${info.versionName}")
            setDescription("Скачивание обновления")
            setMimeType(APK_MIME)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_FILE_NAME)
        }
        try {
            val id = manager.enqueue(request)
            updateDownloadId = id
            downloadInProgress = true
            prefs.edit().putLong(PREF_LAST_DOWNLOAD_ID, id).apply()
            onStarted()
        } catch (_: Exception) {
            downloadInProgress = false
            onFailed()
        }
    }

    fun isExpectedDownloadComplete(id: Long): Boolean {
        val expectedId = if (updateDownloadId > 0L) updateDownloadId else prefs.getLong(PREF_LAST_DOWNLOAD_ID, -1L)
        return id > 0L && id == expectedId
    }

    fun markDownloadComplete() {
        downloadInProgress = false
    }

    fun isDownloadSuccessful(id: Long): Boolean {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    fun startInstallDownloadedUpdate(
        onFileNotFound: () -> Unit,
        onPermissionRequired: () -> Unit,
        onLaunchFailed: () -> Unit,
        onDebugDisabled: () -> Unit
    ) {
        if (BuildConfig.DEBUG) {
            onDebugDisabled()
            return
        }
        val id = prefs.getLong(PREF_LAST_DOWNLOAD_ID, -1L)
        if (id <= 0L) {
            onFileNotFound()
            return
        }
        if (!canRequestPackageInstalls()) {
            pendingInstallAfterPermission = true
            prefs.edit().putBoolean(PREF_PENDING_INSTALL_AFTER_PERMISSION, true).apply()
            onPermissionRequired()
            openInstallPermissionSettings()
            return
        }
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) {
            onFileNotFound()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            onLaunchFailed()
        }
    }

    fun installPendingAfterPermission(
        onFileNotFound: () -> Unit,
        onPermissionRequired: () -> Unit,
        onLaunchFailed: () -> Unit,
        onDebugDisabled: () -> Unit
    ) {
        if (!pendingInstallAfterPermission && !prefs.getBoolean(PREF_PENDING_INSTALL_AFTER_PERMISSION, false)) return
        if (!canRequestPackageInstalls()) return
        pendingInstallAfterPermission = false
        prefs.edit().putBoolean(PREF_PENDING_INSTALL_AFTER_PERMISSION, false).apply()
        startInstallDownloadedUpdate(onFileNotFound, onPermissionRequired, onLaunchFailed, onDebugDisabled)
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

    private fun isDownloadRunning(): Boolean {
        val id = prefs.getLong(PREF_LAST_DOWNLOAD_ID, -1L)
        if (id <= 0L) return false
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
        }
    }

    private fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.packageManager.canRequestPackageInstalls() else true
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
        }
    }

    companion object {
        private const val PREF_LAST_UPDATE_CHECK = "last_update_check_millis"
        private const val PREF_LAST_DOWNLOAD_ID = "last_update_download_id"
        private const val PREF_PENDING_INSTALL_AFTER_PERMISSION = "pending_install_after_permission"
        private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/prishvindt/Azimut/main/update.json"
        private const val UPDATE_APK_FILE_NAME = "Azimut-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
