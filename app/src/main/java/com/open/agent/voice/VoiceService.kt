package com.open.agent.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音服务状态
 */
enum class VoiceServiceState {
    DISABLED,               // 禁用
    WAITING_WAKE_WORD,      // 等待唤醒词
    LISTENING_COMMAND,      // 正在听取命令
    PROCESSING_COMMAND,     // 处理命令中
    EXECUTING               // 执行任务中
}

/**
 * 语音事件
 */
sealed class VoiceEvent {
    object WakeWordDetected : VoiceEvent()
    data class CommandRecognized(val command: String) : VoiceEvent()
    data class Error(val message: String) : VoiceEvent()
    object CommandTimeout : VoiceEvent()
    object ServiceStarted : VoiceEvent()
    object ServiceStopped : VoiceEvent()
}

/**
 * 语音服务
 * 整合唤醒词检测和语音指令识别（共用一个语音识别器，避免麦克风冲突）
 */
class VoiceService(
    private val context: Context,
    private val onCommandReceived: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "VoiceService"
        
        // 命令监听超时时间（毫秒）
        private const val COMMAND_TIMEOUT = 10000L
        
        // 默认唤醒词列表
        val DEFAULT_WAKE_WORDS = listOf(
            "贾维斯",
            "运行指令",
        )
    }
    
    private val _state = MutableStateFlow(VoiceServiceState.DISABLED)
    val state: StateFlow<VoiceServiceState> = _state.asStateFlow()
    
    private val _events = MutableSharedFlow<VoiceEvent>(replay = 0)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()
    
    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()
    
    private val _isVoiceEnabled = MutableStateFlow(false)
    val isVoiceEnabled: StateFlow<Boolean> = _isVoiceEnabled.asStateFlow()
    
    // 唯一的语音识别器（避免麦克风冲突）
    private var voiceRecognizer: VoiceRecognitionManager? = null
    
    // 当前唤醒词
    private var wakeWords: List<String> = DEFAULT_WAKE_WORDS
    
    // Handler用于超时处理
    private val handler = Handler(Looper.getMainLooper())
    private var commandTimeoutRunnable: Runnable? = null
    
    // 音频管理器
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    // WakeLock保持CPU唤醒
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 是否保持常开模式
    private var alwaysOnMode = true
    
    // 激活冷却时间
    private var lastActivationTime = 0L
    private val activationCooldown = 2000L
    
    // AI任务执行中标志
    private var isTaskExecuting = false
    
    // 唤醒词累积缓冲（用于拼接多次识别结果）
    private var wakeWordBuffer = StringBuilder()
    private val bufferResetInterval = 3000L  // 每3秒重置缓冲区
    private var bufferResetRunnable: Runnable? = null
    
    /**
     * 启动语音服务
     */
    fun start() {
        if (_state.value != VoiceServiceState.DISABLED) {
            Log.d(TAG, "语音服务已经运行中")
            return
        }
        
        Log.d(TAG, "启动语音服务")
        
        // 获取WakeLock保持CPU唤醒
        acquireWakeLock()
        
        // 初始化唯一的语音识别器
        voiceRecognizer = VoiceRecognitionManager(context) { result ->
            handleRecognitionResult(result)
        }
        
        _state.value = VoiceServiceState.WAITING_WAKE_WORD
        _isVoiceEnabled.value = true
        
        emitEvent(VoiceEvent.ServiceStarted)
        Log.d(TAG, "语音服务已启动，等待唤醒词...")
        
        // 异步初始化并开始唤醒词检测（等待模型加载完成）
        voiceRecognizer?.initializeAndStart {
            startWakeWordDetection()
        }
    }
    
    /**
     * 启动常开模式
     */
    fun startAlwaysOn() {
        alwaysOnMode = true
        start()
        Log.d(TAG, "语音服务已启动（常开模式）")
    }
    
    /**
     * 停止语音服务
     */
    fun stop() {
        Log.d(TAG, "停止语音服务")
        
        alwaysOnMode = false
        cancelCommandTimeout()
        stopBufferResetTimer()  // 停止缓冲区重置定时器
        
        voiceRecognizer?.release()
        voiceRecognizer = null
        
        releaseWakeLock()
        
        _state.value = VoiceServiceState.DISABLED
        _isVoiceEnabled.value = false
        
        emitEvent(VoiceEvent.ServiceStopped)
    }
    
    /**
     * 手动触发语音命令（跳过唤醒词）
     */
    fun triggerVoiceCommand() {
        if (_state.value == VoiceServiceState.DISABLED) {
            Log.w(TAG, "语音服务未启用")
            return
        }
        
        if (_state.value == VoiceServiceState.LISTENING_COMMAND || 
            _state.value == VoiceServiceState.PROCESSING_COMMAND) {
            Log.d(TAG, "正在处理命令中")
            return
        }
        
        Log.d(TAG, "手动触发语音命令")
        
        // 先停止当前的监听
        voiceRecognizer?.stopListening()
        
        // 播放反馈
        playFeedback()
        
        // 开始命令监听
        _state.value = VoiceServiceState.LISTENING_COMMAND
        startCommandTimeout()
        voiceRecognizer?.startListening()
    }
    
    /**
     * 取消当前语音命令
     */
    fun cancelCommand() {
        Log.d(TAG, "取消语音命令")
        
        cancelCommandTimeout()
        voiceRecognizer?.cancel()
        
        // 恢复唤醒词检测
        startWakeWordDetection()
    }
    
    /**
     * 设置唤醒词
     */
    fun setWakeWords(words: List<String>) {
        wakeWords = words.ifEmpty { DEFAULT_WAKE_WORDS }
        Log.d(TAG, "唤醒词已设置: $wakeWords")
    }
    
    /**
     * 获取唤醒词
     */
    fun getWakeWords(): List<String> = wakeWords
    
    /**
     * 开始唤醒词检测（持续监听模式，麦克风常开）
     */
    private fun startWakeWordDetection() {
        Log.d(TAG, "开始唤醒词检测（麦克风常开模式）")
        _state.value = VoiceServiceState.WAITING_WAKE_WORD
        
        // 清空缓冲区
        wakeWordBuffer.clear()
        
        // 启动定时器，每3秒重置缓冲区
        startBufferResetTimer()
        
        // 开始持续监听
        voiceRecognizer?.startContinuousListening()
    }
    
    /**
     * 启动缓冲区重置定时器
     */
    private fun startBufferResetTimer() {
        stopBufferResetTimer()
        
        bufferResetRunnable = object : Runnable {
            override fun run() {
                if (_state.value == VoiceServiceState.WAITING_WAKE_WORD) {
                    // 每3秒重置缓冲区
                    val oldBuffer = wakeWordBuffer.toString()
                    if (oldBuffer.isNotEmpty()) {
                        Log.d(TAG, "重置缓冲区 (3秒周期), 旧内容: $oldBuffer")
                    }
                    wakeWordBuffer.clear()
                    
                    // 继续下一个周期
                    handler.postDelayed(this, bufferResetInterval)
                }
            }
        }
        
        handler.postDelayed(bufferResetRunnable!!, bufferResetInterval)
    }
    
    /**
     * 停止缓冲区重置定时器
     */
    private fun stopBufferResetTimer() {
        bufferResetRunnable?.let {
            handler.removeCallbacks(it)
            bufferResetRunnable = null
        }
    }
    
    /**
     * 处理语音识别结果
     */
    private fun handleRecognitionResult(result: VoiceRecognitionResult) {
        when (result) {
            is VoiceRecognitionResult.Success -> {
                val text = result.text.trim()
                Log.d(TAG, "识别到: $text, 当前状态: ${_state.value}")
                
                when (_state.value) {
                    VoiceServiceState.WAITING_WAKE_WORD -> {
                        // 累积识别结果到缓冲区（定时器会每3秒自动重置）
                        wakeWordBuffer.append(text)
                        
                        val bufferText = wakeWordBuffer.toString()
                        Log.d(TAG, "唤醒词缓冲: $bufferText")
                        
                        // 检查缓冲区是否包含唤醒词
                        if (checkWakeWord(bufferText)) {
                            stopBufferResetTimer()  // 停止定时器
                            wakeWordBuffer.clear()  // 清空缓冲区
                            onWakeWordDetected()
                        }
                        // 麦克风保持开启，继续监听
                    }
                    VoiceServiceState.LISTENING_COMMAND -> {
                        // 收到命令
                        handleCommand(text)
                    }
                    else -> {
                        // 其他状态忽略
                    }
                }
            }
            is VoiceRecognitionResult.Error -> {
                Log.e(TAG, "识别错误: ${result.message}")
                
                if (_state.value == VoiceServiceState.LISTENING_COMMAND) {
                    cancelCommandTimeout()
                    emitEvent(VoiceEvent.Error(result.message))
                    // 恢复唤醒词检测
                    if (!isTaskExecuting) {
                        startWakeWordDetection()
                    }
                }
                // 唤醒词模式下的错误会自动重试（持续模式）
            }
            is VoiceRecognitionResult.Partial -> {
                // 部分结果，继续等待
            }
        }
    }
    
    /**
     * 检查是否包含唤醒词
     */
    private fun checkWakeWord(text: String): Boolean {
        // 冷却检查
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActivationTime < activationCooldown) {
            return false
        }
        
        val normalizedText = text.lowercase().replace(" ", "")
        return wakeWords.any { wakeWord ->
            normalizedText.contains(wakeWord.lowercase().replace(" ", ""))
        }
    }
    
    /**
     * 唤醒词被检测到
     */
    private fun onWakeWordDetected() {
        Log.d(TAG, "唤醒词已检测到!")
        
        lastActivationTime = System.currentTimeMillis()
        
        // 停止持续监听
        voiceRecognizer?.stopListening()
        
        // 播放提示音和震动
        playFeedback()
        
        emitEvent(VoiceEvent.WakeWordDetected)
        
        // 延迟一点开始命令监听，让用户有时间准备
        handler.postDelayed({
            _state.value = VoiceServiceState.LISTENING_COMMAND
            startCommandTimeout()
            voiceRecognizer?.startListening()
            Log.d(TAG, "开始监听语音命令")
        }, 500)
    }
    
    /**
     * 处理命令
     */
    private fun handleCommand(command: String) {
        cancelCommandTimeout()
        
        if (command.isNotEmpty()) {
            Log.d(TAG, "收到命令: $command")
            _lastCommand.value = command
            _state.value = VoiceServiceState.PROCESSING_COMMAND
            emitEvent(VoiceEvent.CommandRecognized(command))
            
            // 标记任务开始执行
            isTaskExecuting = true
            _state.value = VoiceServiceState.EXECUTING
            
            // 执行命令（任务结束后会调用 onTaskCompleted 恢复唤醒词检测）
            onCommandReceived(command)
        } else {
            // 空命令，恢复唤醒词检测
            startWakeWordDetection()
        }
    }
    
    /**
     * 开始命令超时计时
     */
    private fun startCommandTimeout() {
        cancelCommandTimeout()
        
        commandTimeoutRunnable = Runnable {
            Log.d(TAG, "命令监听超时")
            
            voiceRecognizer?.stopListening()
            emitEvent(VoiceEvent.CommandTimeout)
            
            // 恢复唤醒词检测
            startWakeWordDetection()
        }
        
        handler.postDelayed(commandTimeoutRunnable!!, COMMAND_TIMEOUT)
    }
    
    /**
     * 取消命令超时计时
     */
    private fun cancelCommandTimeout() {
        commandTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            commandTimeoutRunnable = null
        }
    }
    
    /**
     * 播放反馈
     */
    private fun playFeedback() {
        // 震动反馈
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "震动失败", e)
        }
        
        // 播放提示音
        try {
            val notification = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            val ringtone = android.media.RingtoneManager.getRingtone(context, notification)
            ringtone?.play()
        } catch (e: Exception) {
            Log.w(TAG, "播放提示音失败", e)
        }
    }
    
    /**
     * 发送事件
     */
    private fun emitEvent(event: VoiceEvent) {
        handler.post {
            _events.tryEmit(event)
        }
    }
    
    /**
     * 获取WakeLock
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OpenPhoneAgent::VoiceWakeLock"
            ).apply {
                acquire()
            }
            Log.d(TAG, "VoiceWakeLock已获取")
        } catch (e: Exception) {
            Log.e(TAG, "获取WakeLock失败", e)
        }
    }
    
    /**
     * 释放WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "VoiceWakeLock已释放")
            }
        }
        wakeLock = null
    }
    
    /**
     * 是否处于常开模式
     */
    fun isAlwaysOnMode(): Boolean = alwaysOnMode
    
    /**
     * 通知任务开始执行（暂停唤醒词检测）
     */
    fun onTaskStarted() {
        Log.d(TAG, "任务开始，暂停唤醒词检测")
        isTaskExecuting = true
        stopBufferResetTimer()  // 停止缓冲区定时器
        voiceRecognizer?.stopListening()
        _state.value = VoiceServiceState.EXECUTING
    }
    
    /**
     * 通知任务结束（恢复唤醒词检测）
     */
    fun onTaskCompleted() {
        Log.d(TAG, "任务结束，恢复唤醒词检测")
        isTaskExecuting = false
        if (_isVoiceEnabled.value) {
            // 延迟一点恢复，避免任务结束时的声音被误识别
            handler.postDelayed({
                if (!isTaskExecuting && _isVoiceEnabled.value) {
                    startWakeWordDetection()
                }
            }, 1500)
        }
    }
}
