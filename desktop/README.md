# MH Player for Windows

Independent Windows desktop edition. It is **not** an Android APK converted to an EXE and does not yet have feature parity with the Android app.

Uses .NET 8 WinForms and LibVLC for local video/audio playback, multiple-file queue, drag-and-drop, seeking, volume, fullscreen, and keyboard shortcuts (Space, F11, arrows, Ctrl+O). It bundles a Windows VLC playback engine through NuGet; VLC installation should not be required.

The Windows GitHub Actions workflow runs **manually only** and builds a self-contained portable ZIP and an Inno Setup installer in one run. The first build must be tested on Windows before publishing broadly.
