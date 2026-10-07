// Reports whether the focused window has a text caret: writes "1"/"0" to the file given on the command line.
// Build (llvm-mingw): aarch64-w64-mingw32-clang -O2 -mwindows -s textfocus.c -o textfocus.exe
#include <windows.h>
#include <stdio.h>

static int caret_state(void) {
    GUITHREADINFO gi = { sizeof(gi) };
    HWND fg = GetForegroundWindow();
    if (!fg) return 0;
    DWORD tid = GetWindowThreadProcessId(fg, NULL);
    if (!GetGUIThreadInfo(tid, &gi)) return 0;
    if (gi.hwndCaret) return 1;
    if (gi.hwndFocus) {
        char cls[64];
        if (GetClassNameA(gi.hwndFocus, cls, sizeof(cls)) && !lstrcmpiA(cls, "Edit")) return 1;
    }
    return 0;
}

int WINAPI WinMain(HINSTANCE h, HINSTANCE p, LPSTR cmd, int show) {
    // One instance per session; relaunching the .bat must not start a second writer.
    CreateMutexA(NULL, TRUE, "WowForeverTextFocus");
    if (GetLastError() == ERROR_ALREADY_EXISTS) return 0;

    static char path[MAX_PATH] = "C:/wowforever/textfocus";
    if (cmd) {
        while (*cmd == ' ' || *cmd == '"') cmd++;
        if (*cmd) {
            lstrcpynA(path, cmd, MAX_PATH);
            for (int i = lstrlenA(path) - 1; i >= 0 && (path[i] == ' ' || path[i] == '"'); i--) path[i] = 0;
        }
    }
    int last = -1;
    for (;;) {
        int s = caret_state();
        if (s != last) {
            FILE* f = fopen(path, "wb");
            if (f) { fputc(s ? '1' : '0', f); fclose(f); last = s; }
        }
        Sleep(100);
    }
}
