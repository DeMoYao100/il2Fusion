package com.tools.il2fusion.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tools.il2fusion.config.AutoFlowConfigStore
import com.tools.il2fusion.config.HookConfigRepository
import com.tools.il2fusion.config.HookConfigStore
import com.tools.il2fusion.utils.DumpFileParser
import com.tools.il2fusion.utils.HookTargetUtils
import com.tools.il2fusion.utils.LspConfigHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Holds UI state for the hook configuration screen and coordinates data operations.
 */
class HookConfigViewModel(
    private val repository: HookConfigRepository = HookConfigRepository(),
    private val dumpFileParser: DumpFileParser = DumpFileParser()
) : ViewModel() {

    private val _state = MutableStateFlow(HookConfigState())
    val state: StateFlow<HookConfigState> = _state.asStateFlow()

    private val _events = Channel<HookConfigEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var autoJob: Job? = null

    /**
     * Loads initial dump mode and method list; empty state shows read-only placeholder.
     */
    fun loadInitial(context: Context) {
        viewModelScope.launch {
            val payload = repository.loadConfig(context)
            val autoConfig = AutoFlowConfigStore.load(context)
            if (payload.targets.isEmpty()) {
                _state.value = HookConfigState(
                    methodInputs = emptyList(),
                    savedCount = 0,
                    dumpModeEnabled = payload.dumpModeEnabled,
                    downloadUrl = autoConfig.downloadUrl,
                    uploadUrl = autoConfig.uploadUrl
                )
            } else {
                _state.value = HookConfigState(
                    methodInputs = HookTargetUtils.formatInputs(payload.targets),
                    savedCount = payload.targets.size,
                    dumpModeEnabled = payload.dumpModeEnabled,
                    downloadUrl = autoConfig.downloadUrl,
                    uploadUrl = autoConfig.uploadUrl
                )
            }
        }
    }

    /**
     * Handles toggling dump mode and pushes the update to storage.
     */
    fun onDumpModeChanged(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            repository.saveDumpMode(context, enabled)
            _state.value = _state.value.copy(dumpModeEnabled = enabled)
            _events.send(HookConfigEvent.ShowMessage(if (enabled) "已切换到 Dump 模式" else "已切换到 文本拦截 模式"))
        }
    }

    /**
     * Triggers a file parse flow to import methods from a dump file.
     */
    fun onFilePicked(context: Context, uri: Uri?) {
        if (uri == null) {
            viewModelScope.launch { _events.send(HookConfigEvent.ShowMessage("未选择文件")) }
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val result = dumpFileParser.extractTargets(context, uri, Int.MAX_VALUE)
                val methods = HookTargetUtils.normalizeInputs(result.entries.map { it.functionName })
                if (methods.isNotEmpty()) {
                    repository.saveTargets(context, methods)
                    val formatted = HookTargetUtils.formatInputs(methods)
                    _state.value = _state.value.copy(
                        methodInputs = formatted,
                        savedCount = methods.size
                    )
                    _events.send(HookConfigEvent.ShowMessage("解析并保存 ${methods.size} 个 set_text 方法"))
                } else {
                    _events.send(HookConfigEvent.ShowMessage("未在文件中找到 set_text 方法"))
                }
                if (result.savedJsonPath != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context.applicationContext,
                            "已保存 JSON 到 ${result.savedJsonPath}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else if (methods.isNotEmpty()) {
                    _events.send(HookConfigEvent.ShowMessage("JSON 保存失败，已解析方法"))
                }
            } finally {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Manually persists the current method list to storage (for already解析的数据).
     */
    fun onSave(context: Context) {
        viewModelScope.launch {
            val cleaned = HookTargetUtils.normalizeInputs(_state.value.methodInputs)
            if (cleaned.isEmpty()) {
                _events.send(HookConfigEvent.ShowMessage("请先解析 dump.cs 获取方法列表"))
                return@launch
            }
            repository.saveTargets(context, cleaned)
            val formatted = HookTargetUtils.formatInputs(cleaned)
            _state.value = _state.value.copy(
                methodInputs = formatted,
                savedCount = cleaned.size
            )
            _events.send(HookConfigEvent.ShowMessage("已保存 ${cleaned.size} 个方法"))
        }
    }

    fun onDownloadUrlChanged(context: Context, url: String) {
        _state.value = _state.value.copy(downloadUrl = url)
        viewModelScope.launch(Dispatchers.IO) {
            AutoFlowConfigStore.save(context, url, _state.value.uploadUrl)
        }
    }

    fun onUploadUrlChanged(context: Context, url: String) {
        _state.value = _state.value.copy(uploadUrl = url)
        viewModelScope.launch(Dispatchers.IO) {
            AutoFlowConfigStore.save(context, _state.value.downloadUrl, url)
        }
    }

    fun startAutoFlow(context: Context) {
        if (autoJob?.isActive == true) return
        val downloadUrl = _state.value.downloadUrl.trim()
        val uploadUrl = _state.value.uploadUrl.trim()
        if (downloadUrl.isBlank()) {
            viewModelScope.launch { _events.send(HookConfigEvent.ShowMessage("请先填写 APK 下载目录 URL")) }
            return
        }
        if (uploadUrl.isBlank()) {
            viewModelScope.launch { _events.send(HookConfigEvent.ShowMessage("请先填写 Dump 回传地址")) }
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            viewModelScope.launch { _events.send(HookConfigEvent.ShowMessage("未授予安装未知来源应用权限")) }
            return
        }
        autoJob = viewModelScope.launch(Dispatchers.IO) {
            updateAutoState(isRunning = true, status = "准备流程中…", currentItem = null)
            repository.saveDumpMode(context, true)
            updateState { it.copy(dumpModeEnabled = true) }
            val apkUrls = fetchApkUrls(downloadUrl)
            if (apkUrls.isEmpty()) {
                appendAutoLog("未找到可下载的 APK")
                updateAutoState(isRunning = false, status = "结束：未找到 APK", currentItem = null)
                return@launch
            }
            updateAutoState(
                isRunning = true,
                status = "开始处理（0/${apkUrls.size}）",
                currentItem = null,
                progress = 0,
                total = apkUrls.size
            )
            appendAutoLog("发现 ${apkUrls.size} 个 APK，开始处理")
            apkUrls.forEachIndexed { index, url ->
                if (!isActive) return@forEachIndexed
                updateAutoState(
                    isRunning = true,
                    status = "下载中（${index + 1}/${apkUrls.size}）",
                    currentItem = url
                )
                val apkFile = downloadApk(context, url)
                if (apkFile == null) {
                    appendAutoLog("下载失败：$url")
                    updateAutoState(
                        isRunning = true,
                        status = "跳过（${index + 1}/${apkUrls.size}）",
                        currentItem = null,
                        progress = index + 1,
                        total = apkUrls.size
                    )
                    return@forEachIndexed
                }
                val pkgName = resolvePackageName(context, apkFile)
                if (pkgName.isNullOrBlank()) {
                    appendAutoLog("解析包名失败：${apkFile.name}")
                    apkFile.delete()
                    updateAutoState(
                        isRunning = true,
                        status = "跳过（${index + 1}/${apkUrls.size}）",
                        currentItem = null,
                        progress = index + 1,
                        total = apkUrls.size
                    )
                    return@forEachIndexed
                }
                appendAutoLog("安装 $pkgName")
                val installed = installApk(context, apkFile)
                if (!installed) {
                    appendAutoLog("安装失败：$pkgName")
                    apkFile.delete()
                    updateAutoState(
                        isRunning = true,
                        status = "跳过（${index + 1}/${apkUrls.size}）",
                        currentItem = null,
                        progress = index + 1,
                        total = apkUrls.size
                    )
                    return@forEachIndexed
                }
                HookConfigStore.saveTargetPackage(context, pkgName)
                if (_state.value.lspAutoEnable) {
                    appendAutoLog("启用 LSP 作用域：$pkgName")
                    val lspOk = LspConfigHelper.addPackageToScope(pkgName)
                    if (!lspOk) {
                        appendAutoLog("LSP 作用域启用失败：$pkgName")
                    }
                }
                appendAutoLog("启动 $pkgName")
                launchApp(context, pkgName)
                updateAutoState(
                    isRunning = true,
                    status = "等待 dump.cs（${index + 1}/${apkUrls.size}）",
                    currentItem = pkgName
                )
                val dumpFile = waitForDump(pkgName)
                if (dumpFile == null) {
                    appendAutoLog("未检测到 dump.cs：$pkgName")
                    apkFile.delete()
                    updateAutoState(
                        isRunning = true,
                        status = "跳过（${index + 1}/${apkUrls.size}）",
                        currentItem = pkgName,
                        progress = index + 1,
                        total = apkUrls.size
                    )
                    return@forEachIndexed
                }
                appendAutoLog("上传 dump.cs：${dumpFile.name}")
                val uploaded = uploadDump(uploadUrl, dumpFile, pkgName)
                if (uploaded) {
                    appendAutoLog("上传成功：${dumpFile.name}")
                } else {
                    appendAutoLog("上传失败：${dumpFile.name}")
                }
                appendAutoLog("完成：$pkgName")
                apkFile.delete()
                updateAutoState(
                    isRunning = true,
                    status = "完成（${index + 1}/${apkUrls.size}）",
                    currentItem = pkgName,
                    progress = index + 1,
                    total = apkUrls.size
                )
            }
            updateAutoState(isRunning = false, status = "流程结束", currentItem = null)
        }
        autoJob?.invokeOnCompletion { autoJob = null }
    }

    fun stopAutoFlow() {
        autoJob?.cancel()
        autoJob = null
        updateAutoState(isRunning = false, status = "已停止", currentItem = null)
    }

    fun testLspDatabase(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!LspConfigHelper.hasRootAccess()) {
                _events.send(HookConfigEvent.ShowMessage("无 root 权限，无法操作 LSPosed 数据库"))
                return@launch
            }
            val testPkg = "bin.mt.plus"
            val success = LspConfigHelper.addPackageToScope(testPkg)
            if (success) {
                _events.send(HookConfigEvent.ShowMessage("测试成功：已将 $testPkg 添加到作用域"))
            } else {
                _events.send(HookConfigEvent.ShowMessage("测试失败：无法添加 $testPkg"))
            }
        }
    }

    fun onLspAutoEnableChanged(enabled: Boolean) {
        _state.value = _state.value.copy(lspAutoEnable = enabled)
    }

    private fun updateState(block: (HookConfigState) -> HookConfigState) {
        _state.value = block(_state.value)
    }

    private fun updateAutoState(
        isRunning: Boolean,
        status: String,
        currentItem: String?,
        progress: Int = _state.value.autoProgress,
        total: Int = _state.value.autoTotal
    ) {
        updateState {
            it.copy(
                autoRunning = isRunning,
                autoStatus = status,
                autoCurrentItem = currentItem,
                autoProgress = progress,
                autoTotal = total
            )
        }
    }

    private fun appendAutoLog(message: String) {
        val updated = (_state.value.autoLogs + "${nowTime()} $message").takeLast(60)
        updateState { it.copy(autoLogs = updated) }
    }

    private fun nowTime(): String {
        val now = java.time.LocalTime.now()
        return now.withNano(0).toString()
    }

    private suspend fun fetchApkUrls(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        if (baseUrl.lowercase().endsWith(".apk")) {
            return@withContext listOf(baseUrl)
        }
        val text = readUrlText(baseUrl) ?: return@withContext emptyList()
        val result = linkedSetOf<String>()
        val hrefRegex = Regex("href=[\"']([^\"']+\\.apk)[\"']", RegexOption.IGNORE_CASE)
        hrefRegex.findAll(text).forEach { match ->
            result.add(match.groupValues[1])
        }
        val urlRegex = Regex("https?://\\S+?\\.apk", RegexOption.IGNORE_CASE)
        urlRegex.findAll(text).forEach { match ->
            result.add(match.value)
        }
        text.split("\\s+".toRegex())
            .filter { it.lowercase().endsWith(".apk") }
            .forEach { result.add(it.trim('"', '\'', '>', '<')) }
        val base = URL(baseUrl)
        return@withContext result.mapNotNull { link ->
            try {
                if (link.startsWith("http", ignoreCase = true)) {
                    link
                } else {
                    URL(base, link).toString()
                }
            } catch (_: Throwable) {
                null
            }
        }.distinct()
    }

    private fun readUrlText(url: String): String? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadApk(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        val fileName = url.substringAfterLast('/').ifBlank { "download.apk" }
        val outDir = File(context.cacheDir, "auto_apks")
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, fileName)
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return@withContext null
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        return@withContext try {
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outFile
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePackageName(context: Context, apkFile: File): String? {
        val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        return info?.packageName
    }

    private suspend fun installApk(context: Context, apkFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            apkFile.inputStream().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val action = "${context.packageName}.INSTALL_COMMIT.$sessionId"
            val intent = Intent(action)
            val pending = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            val result = awaitInstallResult(context, action) { session.commit(pending.intentSender) }
            session.close()
            result
        }
    }

    private suspend fun awaitInstallResult(
        context: Context,
        action: String,
        commit: () -> Unit
    ): Boolean {
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    )
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (confirm != null) {
                            ctx.startActivity(confirm)
                        }
                        return
                    }
                    val success = status == PackageInstaller.STATUS_SUCCESS
                    if (!cont.isCompleted) {
                        cont.resume(success)
                    }
                    ctx.unregisterReceiver(this)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            try {
                commit()
            } catch (_: Throwable) {
                if (!cont.isCompleted) {
                    cont.resume(false)
                }
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Throwable) {
                    // ignore
                }
            }
            cont.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            appendAutoLog("无法启动应用：$packageName")
        }
    }

    private suspend fun waitForDump(packageName: String): File? = withContext(Dispatchers.IO) {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val target = File(dir, "${packageName}_dump.cs")
        val deadline = SystemClock.elapsedRealtime() + 180_000L
        while (SystemClock.elapsedRealtime() < deadline && isActive) {
            if (target.exists() && target.length() > 0) {
                return@withContext target
            }
            delay(2000)
        }
        null
    }

    private fun uploadDump(uploadUrl: String, dumpFile: File, packageName: String): Boolean {
        val boundary = "----il2fusion${System.currentTimeMillis()}"
        val conn = (URL(uploadUrl).openConnection() as? HttpURLConnection) ?: return false
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        return try {
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"package\"\r\n\r\n")
                out.writeBytes(packageName)
                out.writeBytes("\r\n")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${dumpFile.name}\"\r\n"
                )
                out.writeBytes("Content-Type: text/plain\r\n\r\n")
                dumpFile.inputStream().use { input -> input.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Immutable UI state container for the hook configuration screen.
 */
data class HookConfigState(
    val methodInputs: List<String> = emptyList(),
    val dumpModeEnabled: Boolean = false,
    val savedCount: Int = 0,
    val isLoading: Boolean = false,
    val downloadUrl: String = "",
    val uploadUrl: String = "",
    val autoRunning: Boolean = false,
    val autoStatus: String = "未启动",
    val autoCurrentItem: String? = null,
    val autoProgress: Int = 0,
    val autoTotal: Int = 0,
    val autoLogs: List<String> = emptyList(),
    val lspAutoEnable: Boolean = true
)

/**
 * UI events dispatched to the screen for user feedback.
 */
sealed class HookConfigEvent {
    data class ShowMessage(val text: String) : HookConfigEvent()
}
