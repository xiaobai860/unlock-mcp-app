package com.unlockguard.mcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.unlockguard.mcp.MainApplication
import com.unlockguard.mcp.ui.screens.AppRoot
import com.unlockguard.mcp.ui.theme.UnlockGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as MainApplication).graph
        val vm = ViewModelProvider(this, AppViewModel.Factory(graph, application))[AppViewModel::class.java]
        setContent {
            UnlockGuardTheme {
                AppRoot(vm)
            }
        }
    }
}
