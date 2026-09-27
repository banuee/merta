package dev.merta.app.data.workspace

/**
 * Фаза 3: единственная точка доступа агента к файлам.
 * Любой инструмент (read/write/list/grep/patch/terminal-cwd) обязан идти через gateway.
 * Дефолт: разрешено только рабочее пространство, всё остальное — explicit approve.
 */
data class WorkspaceScope(
    /** Человекочитаемое имя, например "merta-ws". */
    val name: String,
    /** Разрешённые корни (app-private, SAF-дерево). */
    val allowedRoots: List<String> = emptyList(),
    /** Запрещённые подпути внутри разрешённых (например ".git/", "*.keystore"). */
    val deniedPatterns: List<String> = listOf(".git/", "*.keystore", "*.env"),
)

interface FileGateway {
    suspend fun currentScope(): WorkspaceScope
    suspend fun saveScope(scope: WorkspaceScope)

    /**
     * Проверяет путь против текущего скоупа.
     * @return null — доступ разрешён; иначе текст причины для approve-диалога.
     */
    suspend fun check(path: String, write: Boolean): String?
}

/**
 * Чистые правила (JVM-тесты): нормализация + границы корней + deny-паттерны.
 * Паттерны: `dir/` — всё внутри; `*.ext` — расширение; иначе — точное совпадение
 * относительного пути.
 */
object GatewayRules {

    fun checkAccess(scope: WorkspaceScope, rawPath: String, write: Boolean): String? {
        val path = normalize(rawPath)
        val root = scope.allowedRoots.map { normalize(it) }.firstOrNull { isUnder(path, it) }
            ?: return "вне разрешённых папок"
        val rel = if (path == root) "" else path.removePrefix(root).trimStart('/')
        for (pattern in scope.deniedPatterns) {
            if (matches(rel, pattern.trim())) {
                return if (write) "запись запрещена правилом «$pattern»" else "чтение запрещено правилом «$pattern»"
            }
        }
        return null
    }

    fun normalize(p: String): String {
        if (p.startsWith("content://")) return p.trim().trimEnd('/')
        var s = p.trim().replace('\\', '/')
        val parts = mutableListOf<String>()
        for (seg in s.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        val leading = s.startsWith('/')
        return (if (leading) "/" else "") + parts.joinToString("/")
    }

    private fun isUnder(path: String, root: String): Boolean {
        if (root.startsWith("content://")) return path == root || path.startsWith("$root/")
        if (path == root) return true
        return path.startsWith(root.trimEnd('/') + "/")
    }

    private fun matches(rel: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        if (pattern.endsWith("/")) {
            // Папка ловится на любом уровне вложенности (.git где угодно внутри скоупа).
            val dir = pattern.trimEnd('/')
            return rel.split('/').any { it == dir }
        }
        if (pattern.startsWith("*.")) {
            val ext = pattern.removePrefix("*")
            return rel.endsWith(ext) && rel.substringAfterLast('/').contains('.')
        }
        return rel == pattern || rel.startsWith("$pattern/")
    }
}
