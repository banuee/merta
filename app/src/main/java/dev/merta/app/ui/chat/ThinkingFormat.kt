package dev.merta.app.ui.chat

/** «12с», «1м 05с» — для плашки «Думала …». Чистая, JVM-тесты. */
fun formatThinkMs(ms: Long): String {
    val sec = (ms / 1000).toInt().coerceAtLeast(0)
    return if (sec < 60) "${sec}с" else "${sec / 60}м %02dс".format(sec % 60)
}
