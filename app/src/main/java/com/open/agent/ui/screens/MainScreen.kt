package com.open.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.agent.ai.AIProvider
import com.open.agent.ui.components.*
import com.open.agent.viewmodel.MainViewModel
import com.open.agent.voice.VoiceServiceState

/**
 * 主屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val screenshotPreview by viewModel.screenshotPreview.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val isVoiceEnabled by viewModel.isVoiceEnabled.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // 初始化Agent
    LaunchedEffect(Unit) {
        viewModel.initialize()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open Phone Agent") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    // 状态指示器
                    if (uiState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("控制") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("配置") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("测试") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isInitializing -> {
                    // 初始化中
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("正在初始化...")
                    }
                }
                !uiState.isRootAvailable && uiState.isInitialized.not() && uiState.isInitializing.not() -> {
                    // ROOT不可用
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "ROOT权限不可用",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "此应用需要ROOT权限才能运行。请确保您的设备已获取ROOT权限。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.initialize() }) {
                            Text("重试")
                        }
                    }
                }
                else -> {
                    // 主内容
                    when (selectedTab) {
                        0 -> ControlTab(
                            uiState = uiState,
                            logs = logs,
                            isVoiceEnabled = isVoiceEnabled,
                            voiceState = voiceState,
                            onInstructionChange = viewModel::updateInstruction,
                            onStart = { viewModel.startTask(uiState.currentInstruction) },
                            onStop = viewModel::stopTask,
                            onClearLogs = viewModel::clearLogs,
                            onToggleVoice = viewModel::toggleVoiceControl,
                            onTriggerVoiceCommand = viewModel::triggerVoiceCommand
                        )
                        1 -> ConfigTab(
                            uiState = uiState,
                            onProviderSelect = viewModel::selectProvider,
                            onApiKeyChange = viewModel::updateApiKey,
                            onServerUrlChange = viewModel::updateServerUrl,
                            onModelChange = viewModel::updateModelName,
                            onApplyZhipu = { 
                                viewModel.configureZhipu(uiState.apiKey, uiState.modelName) 
                            },
                            onApplyOllama = { 
                                viewModel.configureOllama(uiState.aiServerUrl, uiState.modelName) 
                            },
                            onApplyOpenAI = { 
                                viewModel.configureOpenAI(uiState.apiKey, uiState.aiServerUrl, uiState.modelName) 
                            },
                            onApplyCustom = { 
                                viewModel.configureCustom(
                                    uiState.aiServerUrl, 
                                    uiState.apiKey.takeIf { it.isNotBlank() },
                                    uiState.modelName.takeIf { it.isNotBlank() }
                                ) 
                            }
                        )
                        2 -> TestTab(
                            screenshotPreview = screenshotPreview,
                            onScreenshot = viewModel::takeScreenshot,
                            onSwipe = viewModel::testSwipe,
                            onKeyPress = viewModel::testKeyPress
                        )
                    }
                }
            }
        }
    }
}

/**
 * 控制标签页
 */
@Composable
private fun ControlTab(
    uiState: com.open.agent.viewmodel.MainUiState,
    logs: List<com.open.agent.viewmodel.LogEntry>,
    isVoiceEnabled: Boolean,
    voiceState: VoiceServiceState,
    onInstructionChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearLogs: () -> Unit,
    onToggleVoice: () -> Unit,
    onTriggerVoiceCommand: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 状态卡片
        StatusCard(uiState = uiState)
        
        // 任务输入
        TaskInputCard(
            instruction = uiState.currentInstruction,
            isRunning = uiState.isRunning,
            isEnabled = uiState.isRootAvailable && uiState.isAIConfigured,
            onInstructionChange = onInstructionChange,
            onStart = onStart,
            onStop = onStop
        )
        
        // 语音控制卡片
        VoiceControlCard(
            isVoiceEnabled = isVoiceEnabled,
            voiceState = voiceState,
            onToggleVoice = onToggleVoice,
            onTriggerVoiceCommand = onTriggerVoiceCommand
        )
        
        // 提示信息
        if (!uiState.isAIConfigured) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "请先在\"配置\"页面设置AI服务器地址",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        // 日志
        LogCard(
            logs = logs,
            onClear = onClearLogs
        )
    }
}

/**
 * 配置标签页
 */
@Composable
private fun ConfigTab(
    uiState: com.open.agent.viewmodel.MainUiState,
    onProviderSelect: (AIProvider) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApplyZhipu: () -> Unit,
    onApplyOllama: () -> Unit,
    onApplyOpenAI: () -> Unit,
    onApplyCustom: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 状态卡片
        StatusCard(uiState = uiState)
        
        // AI配置
        AIConfigCard(
            uiState = uiState,
            onProviderSelect = onProviderSelect,
            onApiKeyChange = onApiKeyChange,
            onServerUrlChange = onServerUrlChange,
            onModelChange = onModelChange,
            onApplyZhipu = onApplyZhipu,
            onApplyOllama = onApplyOllama,
            onApplyOpenAI = onApplyOpenAI,
            onApplyCustom = onApplyCustom
        )
        
        // 使用说明
        Card {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = """
                        1. 确保设备已获取ROOT权限
                        2. 选择AI提供商并配置
                        3. 在控制页面输入任务指令
                        4. 点击"开始执行"让AI控制手机
                        
                        支持的AI提供商:
                        • 智谱AI - 推荐，国内访问快
                        • Ollama - 本地部署，无需网络
                        • OpenAI - GPT-4 Vision
                        • 自定义 - 其他兼容API
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 测试标签页
 */
@Composable
private fun TestTab(
    screenshotPreview: String?,
    onScreenshot: () -> Unit,
    onSwipe: (String) -> Unit,
    onKeyPress: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 测试工具
        TestToolsCard(
            onScreenshot = onScreenshot,
            onSwipe = onSwipe,
            onKeyPress = onKeyPress
        )
        
        // 截图预览
        ScreenshotPreviewCard(
            base64Image = screenshotPreview
        )
    }
}
