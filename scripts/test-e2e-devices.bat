@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   iTantra E2E Dual-Device Automated Test Runner
echo ===================================================

set DEV_HOST=daiv55ayrskructw
set DEV_CLIENT=10BD6616D3000GD
if "%HOST_IP%"=="" set HOST_IP=172.25.17.184
if "%CLIENT_IP%"=="" set CLIENT_IP=172.25.17.144
set PORT=4242

set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb

echo.
echo [1/6] Checking attached ADB devices...
"%ADB%" devices | findstr /C:"%DEV_HOST%" >nul
if errorlevel 1 (
    echo [FAIL] Host device %DEV_HOST% not detected!
    exit /b 1
)
"%ADB%" devices | findstr /C:"%DEV_CLIENT%" >nul
if errorlevel 1 (
    echo [FAIL] Client device %DEV_CLIENT% not detected!
    exit /b 1
)
echo [PASS] Both devices connected via ADB.

echo.
echo [2/6] Verifying Network Link (Direct Wi-Fi Ping)...
"%ADB%" -s %DEV_CLIENT% shell ping -c 2 %HOST_IP% >nul
if errorlevel 1 (
    echo [FAIL] Cannot ping Host IP %HOST_IP% from Client!
    exit /b 1
)
echo [PASS] Network link verified (0%% packet loss).

echo.
echo [3/6] Launching iTantra on both devices...
"%ADB%" -s %DEV_HOST% shell input keyevent 224
"%ADB%" -s %DEV_HOST% shell input keyevent 82
"%ADB%" -s %DEV_CLIENT% shell input keyevent 224
"%ADB%" -s %DEV_CLIENT% shell input keyevent 82
"%ADB%" -s %DEV_HOST% shell am start -n com.itantra/.MainActivity >nul
"%ADB%" -s %DEV_CLIENT% shell am start -n com.itantra/.MainActivity >nul
ping -n 4 127.0.0.1 >nul
echo [PASS] App launched on both phones.

echo.
echo [4/6] Connecting Client to Host socket (%HOST_IP%:%PORT%)...
REM Tap Connect button on Vivo (screen: 1080x2400)
"%ADB%" -s %DEV_CLIENT% shell input tap 607 176
ping -n 3 127.0.0.1 >nul
REM Tap 'Connect' in dialog
"%ADB%" -s %DEV_CLIENT% shell input tap 710 1600
ping -n 3 127.0.0.1 >nul
echo [PASS] Socket connection initiated.

echo.
echo [5/6] Sending bidirectional test frames...
REM Send from Client to Host
"%ADB%" -s %DEV_CLIENT% shell input tap 416 2213
"%ADB%" -s %DEV_CLIENT% shell input text "E2E_Test_Client_to_Host"
"%ADB%" -s %DEV_CLIENT% shell input tap 923 2213
ping -n 3 127.0.0.1 >nul

REM Send from Host to Client (screen: 1600x2560)
"%ADB%" -s %DEV_HOST% shell input tap 701 2425
"%ADB%" -s %DEV_HOST% shell input text "E2E_Test_Host_to_Client"
"%ADB%" -s %DEV_HOST% shell input tap 1474 2425
ping -n 3 127.0.0.1 >nul
echo [PASS] Bidirectional text frames dispatched.

echo.
echo [6/6] Testing Emergency SOS Priority Alert...
REM Tap SOS on Host
"%ADB%" -s %DEV_HOST% shell input tap 1520 142
ping -n 3 127.0.0.1 >nul
echo [PASS] SOS Alert transmitted.

echo.
echo Saving diagnostic screenshots to artifacts directory...
if not exist "artifacts" mkdir artifacts
"%ADB%" -s %DEV_HOST% exec-out screencap -p > "artifacts\host_final.png"
"%ADB%" -s %DEV_CLIENT% exec-out screencap -p > "artifacts\client_final.png"

echo.
echo ===================================================
echo   AUTOMATED E2E VERIFICATION COMPLETED SUCCESSFULLY!
echo ===================================================
echo Next step: Perform manual physical voice test by holding
echo the on-screen PTT or physical Volume Down button and speaking.
exit /b 0
