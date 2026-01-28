package com.tools.il2fusion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AutoFlowScreen(
    state: HookConfigState,
    onDownloadUrlChanged: (String) -> Unit,
    onUploadUrlChanged: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestLsp: () -> Unit,
    onLspAutoEnableChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeaderCard(state.dumpModeEnabled, state.savedCount)
            AutoConfigCard(
                downloadUrl = state.downloadUrl,
                uploadUrl = state.uploadUrl,
                enabled = !state.autoRunning,
                lspAutoEnable = state.lspAutoEnable,
                onDownloadUrlChanged = onDownloadUrlChanged,
                onUploadUrlChanged = onUploadUrlChanged,
                onLspAutoEnableChanged = onLspAutoEnableChanged,
                onTestLsp = onTestLsp
            )
            AutoControlCard(
                running = state.autoRunning,
                status = state.autoStatus,
                progress = state.autoProgress,
                total = state.autoTotal,
                currentItem = state.autoCurrentItem,
                onStart = onStart,
                onStop = onStop
            )
            AutoLogCard(logs = state.autoLogs)
            FooterNote()
        }
    }
}

@Composable
private fun AutoConfigCard(
    downloadUrl: String,
    uploadUrl: String,
    enabled: Boolean,
    lspAutoEnable: Boolean,
    onDownloadUrlChanged: (String) -> Unit,
    onUploadUrlChanged: (String) -> Unit,
    onLspAutoEnableChanged: (Boolean) -> Unit,
    onTestLsp: () -> Unit
) {
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "自动流程配置",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            OutlinedTextField(
                value = downloadUrl,
                onValueChange = onDownloadUrlChanged,
                label = { Text("APK 下载目录 URL") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uploadUrl,
                onValueChange = onUploadUrlChanged,
                label = { Text("Dump 回传地址") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动启用 LSPosed 作用域",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = "需要 root 权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = lspAutoEnable,
                    onCheckedChange = onLspAutoEnableChanged,
                    enabled = enabled
                )
            }
            
            Button(
                onClick = onTestLsp,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("测试 LSP 数据库（添加 bin.mt.plus）")
            }
            
            Text(
                text = if (lspAutoEnable) {
                    "提示：将自动修改 LSPosed 数据库启用目标包。"
                } else {
                    "提示：请手动在 LSPosed 勾选目标包，否则不会触发 dump。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AutoControlCard(
    running: Boolean,
    status: String,
    progress: Int,
    total: Int,
    currentItem: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "执行控制",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStart,
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("启动流程")
                }
                Button(
                    onClick = onStop,
                    enabled = running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止流程")
                }
            }
            Text(
                text = "状态：$status",
                style = MaterialTheme.typography.bodyMedium
            )
            if (total > 0) {
                Text(
                    text = "进度：$progress / $total",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!currentItem.isNullOrBlank()) {
                Text(
                    text = "当前：$currentItem",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AutoLogCard(logs: List<String>) {
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "运行日志",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (logs.isEmpty()) {
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                logs.asReversed().take(12).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
