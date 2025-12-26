package com.open.agent.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 唤醒词检测状态
 */
enum class WakeWordState {
    IDLE,           // 空闲
    DETECTING,      // 检测中
    ACTIVATED       // 已激活
}

/**
 * 唤醒词检测器
 * 使用语音识别检测预设的唤醒词
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    
    companion object {
        private const val TAG = "WakeWordDetector"
        
        // 默认唤醒词列表
        val DEFAULT_WAKE_WORDS = listOf(
            "贾维斯",
            "运行 指令", 
        )
    }
    
    private val _state = MutableStateFlow(WakeWordState.IDLE)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()
    
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    
    // 当前使用的唤醒词
    private var wakeWords: List<String> = DEFAULT_WAKE_WORDS
    
    // 语音识别管理器
    private var voiceRecognitionManager: VoiceRecognitionManager? = null
    
    // 激活后的冷却时间（毫秒）
    private var lastActivationTime = 0L
    private val activationCooldown = 3000L
    
    /**
     * 设置唤醒词
     */
    fun setWakeWords(words: List<String>) {
        wakeWords = words.ifEmpty { DEFAULT_WAKE_WORDS }
        Log.d(TAG, "唤醒词已设置: $wakeWords")
    }
    
    /**
     * 添加唤醒词
     */
    fun addWakeWord(word: String) {
        if (word.isNotBlank() && !wakeWords.contains(word)) {
            wakeWords = wakeWords + word
            Log.d(TAG, "添加唤醒词: $word")
        }
    }
    
    /**
     * 获取当前唤醒词列表
     */
    fun getWakeWords(): List<String> = wakeWords
    
    /**
     * 开始检测
     */
    fun startDetecting() {
        if (_state.value == WakeWordState.DETECTING) {
            Log.d(TAG, "已经在检测中")
            return
        }
        
        if (voiceRecognitionManager == null) {
            voiceRecognitionManager = VoiceRecognitionManager(context) { result ->
                handleRecognitionResult(result)
            }
        }
        
        voiceRecognitionManager?.initialize()
        voiceRecognitionManager?.startContinuousListening()
        
        _state.value = WakeWordState.DETECTING
        _isEnabled.value = true
        Log.d(TAG, "开始唤醒词检测")
    }
    
    /**
     * 停止检测
     */
    fun stopDetecting() {
        voiceRecognitionManager?.cancel()
        _state.value = WakeWordState.IDLE
        _isEnabled.value = false
        Log.d(TAG, "停止唤醒词检测")
    }
    
    /**
     * 暂停检测（用于命令识别期间）
     */
    fun pauseDetecting() {
        voiceRecognitionManager?.cancel()
        _state.value = WakeWordState.ACTIVATED
        Log.d(TAG, "暂停唤醒词检测")
    }
    
    /**
     * 恢复检测
     */
    fun resumeDetecting() {
        if (_isEnabled.value && _state.value != WakeWordState.DETECTING) {
            voiceRecognitionManager?.startContinuousListening()
            _state.value = WakeWordState.DETECTING
            Log.d(TAG, "恢复唤醒词检测")
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopDetecting()
        voiceRecognitionManager?.release()
        voiceRecognitionManager = null
        Log.d(TAG, "唤醒词检测器已释放")
    }
    
    /**
     * 处理语音识别结果
     */
    private fun handleRecognitionResult(result: VoiceRecognitionResult) {
        when (result) {
            is VoiceRecognitionResult.Success -> {
                val text = result.text.lowercase().replace(" ", "")
                Log.d(TAG, "检测到语音: $text")
                
                // 检查冷却时间
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastActivationTime < activationCooldown) {
                    Log.d(TAG, "冷却中，忽略唤醒词")
                    return
                }
                
                // 检查是否包含唤醒词
                val detected = wakeWords.any { wakeWord ->
                    text.contains(wakeWord.lowercase().replace(" ", ""))
                }
                
                if (detected) {
                    Log.d(TAG, "检测到唤醒词!")
                    lastActivationTime = currentTime
                    _state.value = WakeWordState.ACTIVATED
                    onWakeWordDetected()
                }
            }
            is VoiceRecognitionResult.Error -> {
                // 错误时继续检测（VoiceRecognitionManager会自动重试）
                Log.d(TAG, "检测错误: ${result.message}")
            }
            is VoiceRecognitionResult.Partial -> {
                // 部分结果，可以进行预检测
            }
        }
    }
}
