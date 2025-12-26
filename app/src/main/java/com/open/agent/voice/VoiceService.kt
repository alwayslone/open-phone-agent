package com.open.agent.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
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
 * 整合唤醒词检测和语音指令识别
 */
class VoiceService(
    private val context: Context,
    private val onCommandReceived: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "VoiceService"
        
        // 命令监听超时时间（毫秒）
        private const val COMMAND_TIMEOUT = 10000L
    }
    
    private val _state = MutableStateFlow(VoiceServiceState.DISABLED)
    val state: StateFlow<VoiceServiceState> = _state.asStateFlow()
    
    private val _events = MutableSharedFlow<VoiceEvent>(replay = 0)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()
    
    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()
    
    private val _isVoiceEnabled = MutableStateFlow(false)
    val isVoiceEnabled: StateFlow<Boolean> = _isVoiceEnabled.asStateFlow()
    
    // 唤醒词检测器
    private var wakeWordDetector: WakeWordDetector? = null
    
    // 命令语音识别器
    private var commandRecognizer: VoiceRecognitionManager? = null
    
    // Handler用于超时处理
    private val handler = Handler(Looper.getMainLooper())
    private var commandTimeoutRunnable: Runnable? = null
    
    // 音频管理器
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    // WakeLock保持CPU唤醒，确保屏幕关闭时也能监听
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 是否保持常开模式
    private var alwaysOnMode = true
    
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
        
        // 初始化唤醒词检测器
        wakeWordDetector = WakeWordDetector(context) {
            onWakeWordDetected()
        }
        
        // 初始化命令识别器
        commandRecognizer = VoiceRecognitionManager(context) { result ->
            handleCommandResult(result)
        }
        commandRecognizer?.initialize()
        
        // 开始唤醒词检测
        wakeWordDetector?.startDetecting()
        
        _state.value = VoiceServiceState.WAITING_WAKE_WORD
        _isVoiceEnabled.value = true
        
        emitEvent(VoiceEvent.ServiceStarted)
        Log.d(TAG, "语音服务已启动，等待唤醒词...")
    }
    
    /**
     * 启动常开模式（保持持续监听）
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
        
        wakeWordDetector?.release()
        wakeWordDetector = null
        
        commandRecognizer?.release()
        commandRecognizer = null
        
        // 释放WakeLock
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
        startCommandListening()
    }
    
    /**
     * 取消当前语音命令
     */
    fun cancelCommand() {
        Log.d(TAG, "取消语音命令")
        
        cancelCommandTimeout()
        commandRecognizer?.cancel()
        
        // 恢复唤醒词检测
        _state.value = VoiceServiceState.WAITING_WAKE_WORD
        wakeWordDetector?.resumeDetecting()
    }
    
    /**
     * 设置唤醒词
     */
    fun setWakeWords(words: List<String>) {
        wakeWordDetector?.setWakeWords(words)
    }
    
    /**
     * 获取唤醒词
     */
    fun getWakeWords(): List<String> {
        return wakeWordDetector?.getWakeWords() ?: WakeWordDetector.DEFAULT_WAKE_WORDS
    }
    
    /**
     * 唤醒词被检测到
     */
    private fun onWakeWordDetected() {
        Log.d(TAG, "唤醒词已检测到!")
        
        // 暂停唤醒词检测
        wakeWordDetector?.pauseDetecting()
        
        // 播放提示音和震动
        playFeedback()
        
        emitEvent(VoiceEvent.WakeWordDetected)
        
        // 开始监听命令
        startCommandListening()
    }
    
    /**
     * 开始监听命令
     */
    private fun startCommandListening() {
        Log.d(TAG, "开始监听语音命令")
        
        _state.value = VoiceServiceState.LISTENING_COMMAND
        
        // 设置超时
        startCommandTimeout()
        
        // 开始语音识别
        commandRecognizer?.startListening()
    }
    
    /**
     * 处理命令识别结果
     */
    private fun handleCommandResult(result: VoiceRecognitionResult) {
        when (result) {
            is VoiceRecognitionResult.Success -> {
                Log.d(TAG, "识别到命令: ${result.text}")
                
                cancelCommandTimeout()
                _state.value = VoiceServiceState.PROCESSING_COMMAND
                
                val command = result.text.trim()
                if (command.isNotEmpty()) {
                    _lastCommand.value = command
                    emitEvent(VoiceEvent.CommandRecognized(command))
                    
                    // 执行命令
                    _state.value = VoiceServiceState.EXECUTING
                    onCommandReceived(command)
                    
                    // 命令执行后恢复唤醒词检测
                    handler.postDelayed({
                        if (_isVoiceEnabled.value) {
                            _state.value = VoiceServiceState.WAITING_WAKE_WORD
                            wakeWordDetector?.resumeDetecting()
                        }
                    }, 1000)
                } else {
                    // 空命令，恢复等待
                    _state.value = VoiceServiceState.WAITING_WAKE_WORD
                    wakeWordDetector?.resumeDetecting()
                }
            }
            is VoiceRecognitionResult.Error -> {
                Log.e(TAG, "命令识别错误: ${result.message}")
                
                cancelCommandTimeout()
                emitEvent(VoiceEvent.Error(result.message))
                
                // 恢复唤醒词检测
                _state.value = VoiceServiceState.WAITING_WAKE_WORD
                wakeWordDetector?.resumeDetecting()
            }
            is VoiceRecognitionResult.Partial -> {
                // 部分结果，继续等待
            }
        }
    }
    
    /**
     * 开始命令超时计时
     */
    private fun startCommandTimeout() {
        cancelCommandTimeout()
        
        commandTimeoutRunnable = Runnable {
            Log.d(TAG, "命令监听超时")
            
            commandRecognizer?.cancel()
            emitEvent(VoiceEvent.CommandTimeout)
            
            // 恢复唤醒词检测
            _state.value = VoiceServiceState.WAITING_WAKE_WORD
            wakeWordDetector?.resumeDetecting()
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
     * 播放反馈（提示音和震动）
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
     * 获取WakeLock保持CPU唤醒
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OpenPhoneAgent::VoiceWakeLock"
            ).apply {
                // 无限期保持唤醒
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
}
