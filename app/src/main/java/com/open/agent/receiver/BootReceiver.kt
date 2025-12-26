package com.open.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.open.agent.service.AgentService

/**
 * 开机自启动广播接收器
 * 在设备启动完成后自动启动语音助手服务
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "收到开机广播: ${intent.action}")
                startAgentService(context)
            }
        }
    }
    
    /**
     * 启动Agent服务
     */
    private fun startAgentService(context: Context) {
        try {
            val serviceIntent = Intent(context, AgentService::class.java).apply {
                action = AgentService.ACTION_START
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "Agent服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动Agent服务失败", e)
        }
    }
}
