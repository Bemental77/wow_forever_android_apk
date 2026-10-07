// Replaces WoW's BlizzardError.exe crash reporter: exits at once so no error dialog is shown.
// Build (llvm-mingw): aarch64-w64-mingw32-clang -O2 -mwindows -s blizzarderror_stub.c -o blizzarderror-stub.exe
#include <windows.h>

int WINAPI WinMain(HINSTANCE h, HINSTANCE p, LPSTR cmd, int show) {
    return 0;
}
