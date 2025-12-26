package com.open.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音识别状态
 */
enum class VoiceRecognitionState {
    IDLE,           // 空闲状态
    LISTENING,      // 正在监听
    PROCESSING,     // 处理中
    ERROR          // 错误状态
}

/**
 * 语音识别结果
 */
sealed class VoiceRecognitionResult {
    data class Success(val text: String, val confidence: Float = 1.0f) : VoiceRecognitionResult()
    data class Error(val errorCode: Int, val message: String) : VoiceRecognitionResult()
    object Partial : VoiceRecognitionResult()
}

/**
 * 语音识别管理器
 * 使用Android内置的SpeechRecognizer进行语音识别
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val onResult: (VoiceRecognitionResult) -> Unit
) {
    
    companion object {
        private const val TAG = "VoiceRecognitionManager"
    }
    
    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _state = MutableStateFlow(VoiceRecognitionState.IDLE)
    val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()
    
    private val _lastRecognizedText = MutableStateFlow<String?>(null)
    val lastRecognizedText: StateFlow<String?> = _lastRecognizedText.asStateFlow()
    
    // 是否持续监听模式
    private var continuousListening = false
    
    /**
     * 检查语音识别是否可用
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
    
    /**
     * 初始化语音识别器
     */
    fun initialize() {
        if (speechRecognizer != null) {
            Log.d(TAG, "语音识别器已经初始化")
            return
        }
        
        if (!isAvailable()) {
            Log.e(TAG, "语音识别不可用")
            onResult(VoiceRecognitionResult.Error(-1, "语音识别不可用"))
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }
        
        Log.d(TAG, "语音识别器初始化完成")
    }
    
    /**
     * 开始单次语音识别
     */
    fun startListening() {
        continuousListening = false
        startRecognition()
    }
    
    /**
     * 开始持续语音识别（用于唤醒词检测后的指令识别）
     */
    fun startContinuousListening() {
        continuousListening = true
        startRecognition()
    }
    
    /**
     * 开始识别
     */
    private fun startRecognition() {
        if (speechRecognizer == null) {
            initialize()
        }
        
        if (_state.value == VoiceRecognitionState.LISTENING) {
            Log.d(TAG, "已经在监听中")
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // 语音输入完毕后的静音时长（毫秒）
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            // 语音输入可能完毕后的静音时长（毫秒）
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
            // 最短语音输入时长
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
        }
        
        try {
            _state.value = VoiceRecognitionState.LISTENING
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "开始语音识别")
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别失败", e)
            _state.value = VoiceRecognitionState.ERROR
            onResult(VoiceRecognitionResult.Error(-1, "启动语音识别失败: ${e.message}"))
        }
    }
    
    /**
     * 停止语音识别
     */
    fun stopListening() {
        continuousListening = false
        speechRecognizer?.stopListening()
        _state.value = VoiceRecognitionState.IDLE
        Log.d(TAG, "停止语音识别")
    }
    
    /**
     * 取消语音识别
     */
    fun cancel() {
        continuousListening = false
        speechRecognizer?.cancel()
        _state.value = VoiceRecognitionState.IDLE
        Log.d(TAG, "取消语音识别")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        continuousListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        _state.value = VoiceRecognitionState.IDLE
        Log.d(TAG, "语音识别器已释放")
    }
    
    /**
     * 创建识别监听器
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "准备就绪，请说话")
                _state.value = VoiceRecognitionState.LISTENING
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "检测到语音开始")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // 音量变化，可用于UI显示
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // 接收到音频数据
            }
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "语音结束")
                _state.value = VoiceRecognitionState.PROCESSING
            }
            
            override fun onError(error: Int) {
                val errorMessage = getErrorMessage(error)
                Log.e(TAG, "识别错误: $errorMessage")
                _state.value = VoiceRecognitionState.ERROR
                
                // 如果是没有检测到语音的错误，且在持续监听模式，则重新开始
                if (continuousListening && (error == SpeechRecognizer.ERROR_NO_MATCH || 
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    _state.value = VoiceRecognitionState.IDLE
                    startRecognition()
                } else {
                    onResult(VoiceRecognitionResult.Error(error, errorMessage))
                    _state.value = VoiceRecognitionState.IDLE
                }
            }
            
            override fun onResults(results: Bundle?) {
                _state.value = VoiceRecognitionState.IDLE
                
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    val confidence = confidences?.getOrNull(0) ?: 1.0f
                    Log.d(TAG, "识别结果: $text (置信度: $confidence)")
                    _lastRecognizedText.value = text
                    onResult(VoiceRecognitionResult.Success(text, confidence))
                } else {
                    Log.d(TAG, "没有识别结果")
                    onResult(VoiceRecognitionResult.Error(-1, "没有识别结果"))
                }
                
                // 持续监听模式下重新开始
                if (continuousListening) {
                    startRecognition()
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "部分识别结果: ${matches[0]}")
                    onResult(VoiceRecognitionResult.Partial)
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "事件: $eventType")
            }
        }
    }
    
    /**
     * 获取错误信息
     */
    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "没有匹配的识别结果"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
            SpeechRecognizer.ERROR_SERVER -> "服务器错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
            else -> "未知错误: $error"
        }
    }
}
