package com.open.agent.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * 语音识别状态
 */
enum class VoiceRecognitionState {
    IDLE,           // 空闲状态
    LOADING,        // 模型加载中
    LISTENING,      // 正在监听
    PROCESSING,     // 处理中
    ERROR           // 错误状态
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
 * Vosk 离线语音识别管理器
 * 完全离线运行，不需要网络连接
 * 模型位置: assets/model-cn/
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val onResult: (VoiceRecognitionResult) -> Unit
) : RecognitionListener {
    
    companion object {
        private const val TAG = "VoskVoiceManager"
        private const val SAMPLE_RATE = 16000.0f
        private const val MODEL_PATH = "model-cn"
    }
    
    private var model: Model? = null
    private var speechService: SpeechService? = null
    
    private val _state = MutableStateFlow(VoiceRecognitionState.IDLE)
    val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()
    
    private val _lastRecognizedText = MutableStateFlow<String?>(null)
    val lastRecognizedText: StateFlow<String?> = _lastRecognizedText.asStateFlow()
    
    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded.asStateFlow()
    
    // 是否持续监听模式
    private var continuousListening = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 检查语音识别是否可用
     */
    fun isAvailable(): Boolean {
        return true // Vosk 离线识别始终可用
    }
    
    /**
     * 初始化语音识别器（加载模型）
     */
    fun initialize() {
        if (model != null) {
            Log.d(TAG, "模型已加载")
            return
        }
        
        _state.value = VoiceRecognitionState.LOADING
        
        scope.launch {
            try {
                Log.d(TAG, "开始加载Vosk中文模型...")
                
                // 从assets解压并加载模型
                StorageService.unpack(context, MODEL_PATH, "model",
                    { loadedModel ->
                        model = loadedModel
                        _modelLoaded.value = true
                        _state.value = VoiceRecognitionState.IDLE
                        Log.d(TAG, "Vosk中文模型加载完成")
                    },
                    { exception ->
                        Log.e(TAG, "模型加载失败", exception)
                        _state.value = VoiceRecognitionState.ERROR
                        onResult(VoiceRecognitionResult.Error(-1, "模型加载失败: ${exception.message}"))
                    }
                )
            } catch (e: IOException) {
                Log.e(TAG, "模型解压失败", e)
                _state.value = VoiceRecognitionState.ERROR
                onResult(VoiceRecognitionResult.Error(-1, "模型解压失败: ${e.message}"))
            }
        }
    }
    
    /**
     * 开始单次语音识别
     */
    fun startListening() {
        continuousListening = false
        startRecognition()
    }
    
    /**
     * 开始持续语音识别（用于唤醒词检测）
     */
    fun startContinuousListening() {
        continuousListening = true
        startRecognition()
    }
    
    /**
     * 开始识别
     */
    private fun startRecognition() {
        if (model == null) {
            Log.d(TAG, "模型未加载，先初始化")
            initialize()
            // 等待模型加载完成后再开始
            scope.launch {
                while (!_modelLoaded.value && _state.value == VoiceRecognitionState.LOADING) {
                    delay(100)
                }
                if (_modelLoaded.value) {
                    withContext(Dispatchers.Main) {
                        doStartRecognition()
                    }
                }
            }
            return
        }
        
        doStartRecognition()
    }
    
    private fun doStartRecognition() {
        if (_state.value == VoiceRecognitionState.LISTENING) {
            Log.d(TAG, "已经在监听中")
            return
        }
        
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            
            _state.value = VoiceRecognitionState.LISTENING
            Log.d(TAG, "开始语音识别")
        } catch (e: IOException) {
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
        speechService?.stop()
        speechService = null
        _state.value = VoiceRecognitionState.IDLE
        Log.d(TAG, "停止语音识别")
    }
    
    /**
     * 取消语音识别
     */
    fun cancel() {
        continuousListening = false
        speechService?.cancel()
        speechService = null
        _state.value = VoiceRecognitionState.IDLE
        Log.d(TAG, "取消语音识别")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        continuousListening = false
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        _modelLoaded.value = false
        _state.value = VoiceRecognitionState.IDLE
        scope.cancel()
        Log.d(TAG, "语音识别器已释放")
    }
    
    // ========== RecognitionListener 回调 ==========
    
    /**
     * 收到部分识别结果
     */
    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty()) return
        
        try {
            val json = JSONObject(hypothesis)
            val partial = json.optString("partial", "")
            if (partial.isNotEmpty()) {
                Log.d(TAG, "部分识别结果: $partial")
                onResult(VoiceRecognitionResult.Partial)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析部分结果失败", e)
        }
    }
    
    /**
     * 收到最终识别结果
     */
    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty()) return
        
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").trim()
            
            if (text.isNotEmpty()) {
                Log.d(TAG, "识别结果: $text")
                _lastRecognizedText.value = text
                _state.value = VoiceRecognitionState.IDLE
                onResult(VoiceRecognitionResult.Success(text))
            }
            
            // 持续监听模式下重新开始
            if (continuousListening && text.isNotEmpty()) {
                scope.launch {
                    delay(200)
                    withContext(Dispatchers.Main) {
                        doStartRecognition()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析结果失败", e)
        }
    }
    
    /**
     * 最终结果（语音结束）
     */
    override fun onFinalResult(hypothesis: String?) {
        Log.d(TAG, "最终结果: $hypothesis")
        onResult(hypothesis)
        
        // 持续监听模式下重新开始
        if (continuousListening) {
            _state.value = VoiceRecognitionState.IDLE
            scope.launch {
                delay(300)
                withContext(Dispatchers.Main) {
                    doStartRecognition()
                }
            }
        } else {
            _state.value = VoiceRecognitionState.IDLE
        }
    }
    
    /**
     * 识别错误
     */
    override fun onError(exception: Exception?) {
        Log.e(TAG, "识别错误", exception)
        _state.value = VoiceRecognitionState.ERROR
        onResult(VoiceRecognitionResult.Error(-1, exception?.message ?: "未知错误"))
        
        // 持续监听模式下重新开始
        if (continuousListening) {
            scope.launch {
                delay(1000)
                withContext(Dispatchers.Main) {
                    _state.value = VoiceRecognitionState.IDLE
                    doStartRecognition()
                }
            }
        }
    }
    
    /**
     * 识别超时
     */
    override fun onTimeout() {
        Log.d(TAG, "识别超时")
        _state.value = VoiceRecognitionState.IDLE
        
        // 持续监听模式下重新开始
        if (continuousListening) {
            scope.launch {
                delay(200)
                withContext(Dispatchers.Main) {
                    doStartRecognition()
                }
            }
        }
    }
}
