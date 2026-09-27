package dev.merta.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.merta.app.ui.providers.ProvidersScreen
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
                // Бары: прозрачные + светлые иконки, без системного контрастного форсинга
                // (иначе на части прошивок статус/навбар белеют, как на скриншоте с телефона).
                SideEffect {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    dev.merta.app.ui.theme.WallpaperBackground()
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
                    // Пересоздаёт параметры/провайдеры после изменений списков.
                    var settingsTick by remember { mutableStateOf(0) }
                    var providersTick by remember { mutableStateOf(0) }

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
                            onOpenModels = { route = Route.MODELS },
                        )
                        Route.SETTINGS -> key(settingsTick) {
                            SettingsScreen(
                                settings = settings,
                                agentFiles = agentFiles,
                                workspace = workspace,
                                onOpenModels = { route = Route.MODELS },
                                onOpenProviders = { route = Route.PROVIDERS },
                                onBack = { route = Route.CHAT },
                                onSaved = { vm.refreshConfig() },
                            )
                        }
                        Route.MODELS -> ModelsScreen(
                            vm = vm,
                            activeProviderId = settings.activeProviderId(),
                            currentModel = settings.selectedModel(settings.activeProviderId()),
                            onPick = { pid, mid ->
                                vm.selectModel(pid, mid)
                                route = Route.CHAT
                            },
                            onBack = { route = Route.CHAT },
                        )
                        Route.PROVIDERS -> key(providersTick) {
                            ProvidersScreen(
                            providers = settings.loadProviders(),
                            activeId = settings.activeProviderId(),
                            onSelect = {
                                settings.setActiveProvider(it)
                                vm.refreshConfig()
                                providersTick++
                            },
                            onAdd = { p ->
                                settings.saveProviders(settings.loadProviders() + p)
                                settings.setActiveProvider(p.id)
                                vm.refreshConfig()
                                providersTick++
                            },
                            onDelete = { id ->
                                settings.saveProviders(settings.loadProviders().filter { it.id != id })
                                vm.refreshConfig()
                                providersTick++
                            },
                            onBack = { route = Route.SETTINGS },
                            )
                        }
                        Route.SESSIONS -> SessionsScreen(
                            vm,
                            onOpenChat = { route = Route.CHAT },
                            onNewChat = {
                                vm.newChat()
                                route = Route.CHAT
                            },
                        )
                    }
                    } // systemBarsPadding
                } // root (обои)
            } // MetroTheme
        }
    }

    private enum class Route { CHAT, SETTINGS, MODELS, SESSIONS, PROVIDERS }
}
