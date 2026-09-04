@echo off
REM PRD K6 / 4.3 gate: verify manifest contains zero INTERNET permission
REM Usage: scripts\check-no-internet.bat [apk_path]
setlocal
set APK=%~1
if "%APK%"=="" set APK=app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK%" (
  echo FAIL: APK not found at %APK%
  exit /b 1
)
REM Prefer aapt from SDK; fallback to apkanalyzer
set AAPT=%ANDROID_HOME%\build-tools\34.0.0\aapt.exe
if not exist "%AAPT%" set AAPT=%ANDROID_SDK_ROOT%\build-tools\34.0.0\aapt.exe
if not exist "%AAPT%" set AAPT=aapt

echo Checking %APK% for INTERNET permission...
"%AAPT%" dump permissions "%APK%" 2>&1 | findstr /I "INTERNET" >nul
if %ERRORLEVEL%==0 (
  echo FAIL: INTERNET permission found in %APK%
  "%AAPT%" dump permissions "%APK%" 2>&1 | findstr /I "INTERNET"
  exit /b 1
) else (
  echo PASS: no INTERNET permission in %APK%
  exit /b 0
)
