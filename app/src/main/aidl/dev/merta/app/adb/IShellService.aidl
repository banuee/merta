// IShellService.aidl — привилегированный исполнитель для Shizuku UserService.
// Работает под shell (UID 2000): выполняет ТОЛЬКО команды из белого списка,
// который проверяется в приложении ДО вызова (см. ShizukuOpsImpl.buildArgv).
package dev.merta.app.adb;

// Возвращает "<exitCode>\n<вывод stdout+stderr, до 20К>" одной строкой.
interface IShellService {
    String runShell(in String[] argv);
    void destroy();
}
