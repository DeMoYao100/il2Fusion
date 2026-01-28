package com.tools.il2fusion.utils

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object LspConfigHelper {
    private const val TAG = "[il2Fusion]"
    private const val LSP_DB = "/data/adb/lspd/config/modules_config.db"
    private const val MODULE_PKG = "com.tools.il2fusion"

    /**
     * 获取当前模块在 modules 表中的 mid
     */
    private fun getModuleId(): Int? {
        val sql = "SELECT mid FROM modules WHERE module_pkg_name='$MODULE_PKG';"
        val result = executeSql(sql)
        return result?.trim()?.toIntOrNull()
    }

    /**
     * 添加包到模块的 scope（启用该包）
     */
    fun addPackageToScope(packageName: String, userId: Int = 0): Boolean {
        val mid = getModuleId()
        if (mid == null) {
            Log.e(TAG, "LspConfigHelper: 无法获取模块 mid")
            return false
        }

        // 先检查是否已存在
        val checkSql = "SELECT COUNT(*) FROM scope WHERE mid=$mid AND app_pkg_name='$packageName' AND user_id=$userId;"
        val count = executeSql(checkSql)?.trim()?.toIntOrNull() ?: 0
        if (count > 0) {
            Log.i(TAG, "LspConfigHelper: $packageName 已在 scope 中")
            return true
        }

        // 插入新记录
        val insertSql = "INSERT INTO scope (mid, app_pkg_name, user_id) VALUES ($mid, '$packageName', $userId);"
        val result = executeSql(insertSql)
        val success = result != null
        Log.i(TAG, "LspConfigHelper: addPackageToScope($packageName) = $success")
        return success
    }

    /**
     * 从 scope 移除包
     */
    fun removePackageFromScope(packageName: String, userId: Int = 0): Boolean {
        val mid = getModuleId() ?: return false
        val deleteSql = "DELETE FROM scope WHERE mid=$mid AND app_pkg_name='$packageName' AND user_id=$userId;"
        val result = executeSql(deleteSql)
        val success = result != null
        Log.i(TAG, "LspConfigHelper: removePackageFromScope($packageName) = $success")
        return success
    }

    /**
     * 查询当前模块的所有 scope
     */
    fun listScope(): List<String> {
        val mid = getModuleId() ?: return emptyList()
        val sql = "SELECT app_pkg_name FROM scope WHERE mid=$mid;"
        val result = executeSql(sql) ?: return emptyList()
        return result.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * 执行 SQL 命令（通过 su + sqlite3）
     */
    private fun executeSql(sql: String): String? {
        var process: Process? = null
        var writer: DataOutputStream? = null
        var reader: BufferedReader? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            writer = DataOutputStream(process.outputStream)
            reader = BufferedReader(InputStreamReader(process.inputStream))

            // 发送 sqlite3 命令
            val command = "sqlite3 $LSP_DB \"$sql\""
            writer.writeBytes("$command\n")
            writer.writeBytes("exit\n")
            writer.flush()

            // 读取输出
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            process.waitFor()
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.e(TAG, "LspConfigHelper: SQL 执行失败 exitCode=$exitCode sql=$sql")
                null
            } else {
                output.toString()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "LspConfigHelper: executeSql 异常", e)
            null
        } finally {
            try {
                writer?.close()
                reader?.close()
                process?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 检查是否有 root 权限
     */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            output?.contains("uid=0") == true
        } catch (_: Throwable) {
            false
        }
    }
}
