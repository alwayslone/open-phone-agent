package com.open.agent.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.agent.ai.AIProvider
import com.open.agent.controller.AgentState
import com.open.agent.viewmodel.LogEntry
import com.open.agent.viewmodel.LogLevel
import com.open.agent.viewmodel.MainUiState
import com.open.agent.voice.VoiceServiceState
import java.text.SimpleDateFormat
import java.util.*

/**
 * 状态卡片
 */
@Composable
fun StatusCard(
    uiState: MainUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "系统状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // ROOT状态
            StatusRow(
                label = "ROOT权限",
                value = if (uiState.isRootAvailable) "已获取" else "未获取",
                isPositive = uiState.isRootAvailable
            )
            
            // AI配置状态
            StatusRow(
                label = "AI服务",
                value = if (uiState.isAIConfigured) "已配置" else "未配置",
                isPositive = uiState.isAIConfigured
            )
            
            // Agent状态
            StatusRow(
                label = "Agent状态",
                value = when (uiState.agentState) {
                    AgentState.IDLE -> "空闲"
                    AgentState.INITIALIZING -> "初始化中"
                    AgentState.RUNNING -> "运行中"
                    AgentState.PAUSED -> "已暂停"
                    AgentState.ERROR -> "错误"
                },
                isPositive = uiState.agentState != AgentState.ERROR
            )
            
            // 当前任务
            if (uiState.currentTask != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "当前任务: ${uiState.currentTask}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "步骤: ${uiState.currentStep}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    isPositive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isPositive) Color(0xFF4CAF50) else Color(0xFFFF5722))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPositive) Color(0xFF4CAF50) else Color(0xFFFF5722)
            )
        }
    }
}

/**
 * AI提供商配置卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIConfigCard(
    uiState: MainUiState,
    onProviderSelect: (AIProvider) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApplyZhipu: () -> Unit,
    onApplyOllama: () -> Unit,
    onApplyOpenAI: () -> Unit,
    onApplyCustom: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showApiKey by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "AI服务配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 提供商选择
            Text(
                text = "选择AI提供商",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AIProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = uiState.selectedProvider == provider,
                        onClick = { onProviderSelect(provider) },
                        label = { 
                            Text(
                                text = when (provider) {
                                    AIProvider.ZHIPU -> "智谱AI"
                                    AIProvider.OLLAMA -> "Ollama"
                                    AIProvider.OPENAI -> "OpenAI"
                                    AIProvider.CUSTOM -> "自定义"
                                },
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 根据选择的提供商显示不同的配置项
            when (uiState.selectedProvider) {
                AIProvider.ZHIPU -> {
                    // 智谱AI配置
                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("API Key") },
                        placeholder = { Text("从open.bigmodel.cn获取") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) 
                            VisualTransformation.None 
                        else 
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) 
                                        Icons.Default.VisibilityOff 
                                    else 
                                        Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 模型输入
                    OutlinedTextField(
                        value = uiState.modelName,
                        onValueChange = onModelChange,
                        label = { Text("模型") },
                        placeholder = { Text("glm-4v-flash, glm-4v-plus, glm-4v...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = onApplyZhipu,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("应用配置")
                    }
                }
                
                AIProvider.OLLAMA -> {
                    // Ollama配置
                    OutlinedTextField(
                        value = uiState.aiServerUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("Ollama地址") },
                        placeholder = { Text("http://192.168.1.100:11434") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = uiState.modelName,
                        onValueChange = onModelChange,
                        label = { Text("模型名称") },
                        placeholder = { Text("llava, bakllava, llava-llama3") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = onApplyOllama,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("应用配置")
                    }
                }
                
                AIProvider.OPENAI -> {
                    // OpenAI配置
                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) 
                            VisualTransformation.None 
                        else 
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) 
                                        Icons.Default.VisibilityOff 
                                    else 
                                        Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = uiState.aiServerUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("API地址") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = uiState.modelName,
                        onValueChange = onModelChange,
                        label = { Text("模型") },
                        placeholder = { Text("gpt-4o, gpt-4-vision-preview") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = onApplyOpenAI,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("应用配置")
                    }
                }
                
                AIProvider.CUSTOM -> {
                    // 自定义配置
                    OutlinedTextField(
                        value = uiState.aiServerUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("API地址") },
                        placeholder = { Text("http://your-server/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("API Key (可选)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) 
                            VisualTransformation.None 
                        else 
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) 
                                        Icons.Default.VisibilityOff 
                                    else 
                                        Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = uiState.modelName,
                        onValueChange = onModelChange,
                        label = { Text("模型 (可选)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = onApplyCustom,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("应用配置")
                    }
                }
            }
            
            // 配置状态
            if (uiState.isAIConfigured) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "已配置: ${uiState.selectedProvider.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

/**
 * 配置卡片 (旧版兼容)
 */
@Composable
fun ConfigCard(
    serverUrl: String,
    apiKey: String,
    onServerUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onApplyConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showApiKey by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "AI服务配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("服务器地址") },
                placeholder = { Text("http://192.168.1.100:8000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Cloud, contentDescription = null)
                }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key (可选)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showApiKey) 
                    VisualTransformation.None 
                else 
                    PasswordVisualTransformation(),
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) 
                                Icons.Default.VisibilityOff 
                            else 
                                Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onApplyConfig,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("应用配置")
            }
        }
    }
}

/**
 * 任务输入卡片
 */
@Composable
fun TaskInputCard(
    instruction: String,
    isRunning: Boolean,
    isEnabled: Boolean,
    onInstructionChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "任务指令",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = instruction,
                onValueChange = onInstructionChange,
                label = { Text("输入你想让AI完成的任务") },
                placeholder = { Text("例如: 打开微信，发送消息给张三") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                enabled = !isRunning,
                leadingIcon = {
                    Icon(Icons.Default.Psychology, contentDescription = null)
                }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    enabled = isEnabled && !isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始执行")
                }
                
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    enabled = isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF44336)
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("停止")
                }
            }
        }
    }
}

/**
 * 日志显示卡片
 */
@Composable
fun LogCard(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    // 自动滚动到最新日志
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "执行日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "清除日志")
                }
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "暂无日志",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        items(logs) { entry ->
                            LogItem(entry = entry, dateFormat = dateFormat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(
    entry: LogEntry,
    dateFormat: SimpleDateFormat
) {
    val color = when (entry.level) {
        LogLevel.INFO -> Color(0xFFB0BEC5)
        LogLevel.WARNING -> Color(0xFFFFB74D)
        LogLevel.ERROR -> Color(0xFFEF5350)
        LogLevel.SUCCESS -> Color(0xFF66BB6A)
        LogLevel.THOUGHT -> Color(0xFF42A5F5)
    }
    
    val prefix = when (entry.level) {
        LogLevel.INFO -> "[INFO]"
        LogLevel.WARNING -> "[WARN]"
        LogLevel.ERROR -> "[ERROR]"
        LogLevel.SUCCESS -> "[OK]"
        LogLevel.THOUGHT -> "[THINK]"
    }
    
    Text(
        text = "${dateFormat.format(Date(entry.timestamp))} $prefix ${entry.message}",
        color = color,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/**
 * 测试工具卡片
 */
@Composable
fun TestToolsCard(
    onScreenshot: () -> Unit,
    onSwipe: (String) -> Unit,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "测试工具",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 截图按钮
            OutlinedButton(
                onClick = onScreenshot,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Screenshot, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("截图")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 滑动按钮
            Text(
                text = "滑动测试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onSwipe("up") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null)
                }
                OutlinedButton(
                    onClick = { onSwipe("down") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null)
                }
                OutlinedButton(
                    onClick = { onSwipe("left") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null)
                }
                OutlinedButton(
                    onClick = { onSwipe("right") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 按键测试
            Text(
                text = "按键测试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onKeyPress("back") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("返回")
                }
                OutlinedButton(
                    onClick = { onKeyPress("home") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Home")
                }
                OutlinedButton(
                    onClick = { onKeyPress("recent") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("最近")
                }
            }
        }
    }
}

/**
 * 截图预览卡片
 */
@Composable
fun ScreenshotPreviewCard(
    base64Image: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "屏幕预览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2D2D2D))
                    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (base64Image != null) {
                    val bitmap = remember(base64Image) {
                        try {
                            val bytes = Base64.decode(base64Image, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "截图预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = "图片解码失败",
                            color = Color.Gray
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ScreenshotMonitor,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "暂无截图",
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

/**
 * 语音控制卡片
 */
@Composable
fun VoiceControlCard(
    isVoiceEnabled: Boolean,
    voiceState: VoiceServiceState,
    onToggleVoice: () -> Unit,
    onTriggerVoiceCommand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isVoiceEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "语音控制",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = getVoiceStateDescription(voiceState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Switch(
                    checked = isVoiceEnabled,
                    onCheckedChange = { onToggleVoice() }
                )
            }
            
            if (isVoiceEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                
                // 语音状态指示
                VoiceStatusIndicator(voiceState = voiceState)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 手动触发按钮
                Button(
                    onClick = onTriggerVoiceCommand,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = voiceState == VoiceServiceState.WAITING_WAKE_WORD
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("点击说话")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 唤醒词提示
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "说\"你好助手\"或\"小助手\"唤醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * 语音状态指示器
 */
@Composable
private fun VoiceStatusIndicator(
    voiceState: VoiceServiceState
) {
    val color = when (voiceState) {
        VoiceServiceState.DISABLED -> Color.Gray
        VoiceServiceState.WAITING_WAKE_WORD -> Color(0xFF4CAF50)
        VoiceServiceState.LISTENING_COMMAND -> Color(0xFFFF9800)
        VoiceServiceState.PROCESSING_COMMAND -> Color(0xFF2196F3)
        VoiceServiceState.EXECUTING -> Color(0xFF9C27B0)
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 动画指示器
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = when (voiceState) {
                VoiceServiceState.DISABLED -> "语音服务已禁用"
                VoiceServiceState.WAITING_WAKE_WORD -> "🎙️ 正在监听唤醒词..."
                VoiceServiceState.LISTENING_COMMAND -> "🗣️ 请说出您的指令..."
                VoiceServiceState.PROCESSING_COMMAND -> "⚙️ 正在处理指令..."
                VoiceServiceState.EXECUTING -> "🚀 正在执行任务..."
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/**
 * 获取语音状态描述
 */
private fun getVoiceStateDescription(state: VoiceServiceState): String {
    return when (state) {
        VoiceServiceState.DISABLED -> "未启用"
        VoiceServiceState.WAITING_WAKE_WORD -> "等待唤醒词"
        VoiceServiceState.LISTENING_COMMAND -> "正在听取指令"
        VoiceServiceState.PROCESSING_COMMAND -> "处理中"
        VoiceServiceState.EXECUTING -> "执行中"
    }
}
