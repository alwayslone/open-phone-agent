package com.open.agent.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * ROOT命令执行器
 * 用于在ROOT权限下执行shell命令
 */
class RootExecutor {
    
    private var suProcess: Process? = null
    private var outputStream: DataOutputStream? = null
    
    /**
     * 检查设备是否已获取ROOT权限
     */
    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            
            output?.contains("uid=0") == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 初始化ROOT shell会话
     */
    suspend fun initRootShell(): Boolean = withContext(Dispatchers.IO) {
        try {
            suProcess = Runtime.getRuntime().exec("su")
            outputStream = DataOutputStream(suProcess!!.outputStream)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 执行ROOT命令并返回结果
     */
    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            
            val stdOutput = StringBuilder()
            val stdError = StringBuilder()
            
            // 读取标准输出
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stdOutput.appendLine(line)
                }
            }
            
            // 读取错误输出
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stdError.appendLine(line)
                }
            }
            
            val exitCode = process.waitFor()
            
            CommandResult(
                success = exitCode == 0,
                output = stdOutput.toString().trim(),
                error = stdError.toString().trim(),
                exitCode = exitCode
            )
        } catch (e: Exception) {
            e.printStackTrace()
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "Unknown error",
                exitCode = -1
            )
        }
    }
    
    /**
     * 执行命令（无需等待输出）
     */
    suspend fun executeCommandNoOutput(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 关闭ROOT会话
     */
    fun close() {
        try {
            outputStream?.apply {
                writeBytes("exit\n")
                flush()
                close()
            }
            suProcess?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            outputStream = null
            suProcess = null
        }
    }
}

/**
 * 命令执行结果
 */
data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String,
    val exitCode: Int
)
