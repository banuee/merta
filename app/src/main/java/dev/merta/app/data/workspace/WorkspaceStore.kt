package dev.merta.app.data.workspace

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.workspaceDataStore by preferencesDataStore("workspace")

/**
 * Персистентность скоупов (DataStore). Секретов тут нет — только пути.
 * Дефолтной папки нет: пользователь добавляет свои (иначе инструментам
 * негде работать — gateway честно отвечает подсказкой).
 */
class WorkspaceStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val ds get() = appContext.workspaceDataStore

    val scopeFlow: Flow<WorkspaceScope> = ds.data.map { p ->
        WorkspaceScope(
            name = "merta-ws",
            allowedRoots = (p[KEY_ROOTS] ?: emptySet()).toList().sorted(),
            deniedPatterns = (p[KEY_DENIED] ?: DEFAULT_DENIED).toList().sorted(),
        )
    }

    suspend fun scope(): WorkspaceScope = scopeFlow.first()

    suspend fun addRoot(root: String) {
        // Валидация: только абсолютные файловые пути и SAF-деревья;
        // относительные, пустые и голый "/" (весь девайс) не принимаем.
        val clean = root.trim().trimEnd('/')
        if (clean.isEmpty() || clean == "/") return
        if (!clean.startsWith("/") && !clean.startsWith("content://")) return
        ds.edit { p ->
            val cur = (p[KEY_ROOTS] ?: emptySet()).map { it.trimEnd('/') }.toSet()
            p[KEY_ROOTS] = (cur + clean).toSet()
        }
    }

    suspend fun removeRoot(root: String) {
        ds.edit { p ->
            val next = (p[KEY_ROOTS] ?: emptySet()) - root - (root.trimEnd('/'))
            p[KEY_ROOTS] = next
        }
    }

    companion object {
        val DEFAULT_DENIED = setOf(".git/", "*.keystore", "*.jks", "*.env")
        private val KEY_ROOTS = stringSetPreferencesKey("roots")
        private val KEY_DENIED = stringSetPreferencesKey("denied")
    }
}

/** Живая реализация поверх [WorkspaceStore] (инструменты будут звать её). */
class FileGatewayImpl(private val store: WorkspaceStore) : FileGateway {
    override suspend fun currentScope(): WorkspaceScope = store.scope()

    override suspend fun saveScope(scope: WorkspaceScope) {
        for (root in scope.allowedRoots) store.addRoot(root)
    }

    override suspend fun check(path: String, write: Boolean): String? {
        if (store.scope().allowedRoots.isEmpty()) {
            return "нет разрешённых папок — добавь папку в Параметрах (Рабочая папка)"
        }
        return GatewayRules.checkAccess(store.scope(), path, write)
    }
}
