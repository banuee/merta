package dev.merta.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.merta.app.data.agent.AgentFiles
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.data.workspace.WorkspaceStore
import dev.merta.app.ui.chat.ChatScreen
import dev.merta.app.ui.chat.ChatViewModel
import dev.merta.app.ui.models.ModelsScreen
import dev.merta.app.ui.sessions.SessionsScreen
import dev.merta.app.ui.settings.SettingsScreen
import dev.merta.app.ui.theme.MetroTheme

class MertaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val view = LocalView.current
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            MetroTheme {
                Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    val vm: ChatViewModel = viewModel(
                        factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                ChatViewModel(application) as T
                        },
                    )
                    val settings = remember { MertaSettings(applicationContext) }
                    val agentFiles = remember { AgentFiles(applicationContext) }
                    val workspace = remember {
                        WorkspaceStore(applicationContext, agentFiles.workspaceDir.absolutePath)
                    }
                    var route by remember {
                        mutableStateOf(
                            if (intent.getBooleanExtra("open_updates", false)) Route.SETTINGS else Route.CHAT,
                        )
                    }
                    // Пересоздаёт параметры после выбора модели (поле модели обновляется).
                    var settingsTick by remember { mutableStateOf(0) }

                    when (route) {
                        Route.CHAT -> ChatScreen(
                            vm,
                            onOpenSettings = { route = Route.SETTINGS },
                            onOpenSessions = {
                                vm.refreshSessions()
                                route = Route.SESSIONS
                            },
                            onNewChat = {
                                vm.newChat()
                            },
                        )
                        Route.SETTINGS -> key(settingsTick) {
                            SettingsScreen(
                                settings = settings,
                                agentFiles = agentFiles,
                                workspace = workspace,
                                onOpenModels = { route = Route.MODELS },
                                onBack = { route = Route.CHAT },
                                onSaved = { vm.refreshConfig() },
                            )
                        }
                        Route.MODELS -> ModelsScreen(
                            vm = vm,
                            currentModel = settings.load().model,
                            onPick = { id ->
                                val cfg = settings.load()
                                settings.save(cfg.copy(model = id))
                                vm.refreshConfig()
                                settingsTick++
                                route = Route.SETTINGS
                            },
                            onBack = { route = Route.SETTINGS },
                        )
                        Route.SESSIONS -> SessionsScreen(
                            vm,
                            onOpenChat = { route = Route.CHAT },
                            onNewChat = {
                                vm.newChat()
                                route = Route.CHAT
                            },
                        )
                    }
                }
            }
        }
    }

    private enum class Route { CHAT, SETTINGS, MODELS, SESSIONS }
}
