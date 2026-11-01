/*
 * "VTLO 3O3.exe" for the portable Windows build: starts the bundled Java
 * runtime (runtime\bin\javaw.exe) on the app jars (app\*) without a console
 * window. Build: x86_64-w64-mingw32-gcc -municode -mwindows launcher.c icon.o
 */
#include <windows.h>
#include <wchar.h>

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE prev, PWSTR args, int show) {
    wchar_t dir[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return 1;
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;

    wchar_t cmd[4 * MAX_PATH];
    swprintf(cmd, 4 * MAX_PATH,
        L"\"%ls\\runtime\\bin\\javaw.exe\" -cp \"%ls\\app\\*\" com.vtlo.desktop.MainKt", dir, dir);

    STARTUPINFOW si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"Could not start the bundled Java runtime (runtime\\bin\\javaw.exe).",
            L"VTLO 3O3", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
