@echo off
REM The build\image-windows\bin\drydock.bat launcher -- the Windows
REM counterpart of app\packaging\launcher.sh on macOS. Copied
REM verbatim into the runtime image by WindowsRuntimeImageTask (see
REM buildSrc\src\main\kotlin\drydock\tasks\WindowsRuntimeImageTask.kt);
REM no build-time substitution happens: this file is the launcher,
REM byte for byte.
REM
REM Why a sibling .bat rather than a single cross-shell script: cmd.exe
REM cannot use bash's symlink-resolution loop, $BASH_SOURCE, or `exec`
REM (replacing the parent shell) -- Windows cmd semantics are different
REM enough that maintaining a single script would be more error-prone
REM than two sibling launchers. The launcher itself is a thin wrapper:
REM the real JVM args, classpath layout, and entry point match the
REM macOS launcher modulo the flags listed below.
REM
REM Deviations from the macOS launcher, justified:
REM
REM - No -Xdock:* flags (macOS-only; the HotSpot JVM rejects them at
REM   startup with "Unrecognized option: -Xdock:name=Drydock" -- this is
REM   exactly the Windows-boot-spike regression that the gradle :app:run
REM   CI smoke now covers on the dev side; the launcher carries the same
REM   exclusion so the bundled .bat behaves identically).
REM - No -Dapp.drydock.ghostty.nativeDir / .terminalhost.nativeDir:
REM   the Windows runtime image does not bundle libghostty.dylib or
REM   libdrydockterminalhost.dylib because JediTermFX + pty4j is the
REM   only terminal backend that runs on Windows (TerminalFactory's
REM   defaultBackend returns "jediterm" on Windows). Pointing at a
REM   non-existent directory would be benign -- NativeLibraryLocator
REM   only reads these properties when the Ghostty / native-host
REM   backend is actually used -- but the launcher is clearer without
REM   them.
REM - DRYDOCK_MAIN_CLASS / DRYDOCK_EXTRA_JVM_ARGS env vars keep the
REM   same meaning as the macOS launcher; both default to the real
REM   app (app.drydock.Main), and either can be overridden for
REM   spike-only verification if ever needed (the spikes live in the
REM   spike source set and are NOT bundled in the image, so
REM   DRYDOCK_EXTRA_JVM_ARGS would have to point at their classes
REM   explicitly).
setlocal

REM %~dp0 is the directory containing this .bat, with a trailing
REM backslash. Strip it and add .. so APP_HOME points at the image
REM root; resolve to an absolute path so callers can move the
REM directory anywhere.
set "APP_HOME=%~dp0.."
if "%APP_HOME:~-1%"=="\" set "APP_HOME=%APP_HOME:~0,-1%"
for %%I in ("%APP_HOME%") do set "APP_HOME=%%~fI"

set "MAIN_CLASS=%DRYDOCK_MAIN_CLASS%"
if "%MAIN_CLASS%"=="" set "MAIN_CLASS=app.drydock.Main"

"%APP_HOME%\runtime\bin\java.exe" ^
  --enable-native-access=ALL-UNNAMED ^
  --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED ^
  -Dfile.encoding=UTF-8 ^
  -Djava.awt.headless=false ^
  %DRYDOCK_EXTRA_JVM_ARGS% ^
  -cp "%APP_HOME%\app\*" ^
  %MAIN_CLASS% %*
