@echo off

echo === OctoFlow Build ===
echo.

REM Check for Android SDK
if "%ANDROID_HOME%"=="" if "%ANDROID_SDK_ROOT%"=="" (
    echo ERROR: Android SDK not found. Please set ANDROID_HOME or ANDROID_SDK_ROOT.
    echo   Example: set ANDROID_HOME=C:\Users\jayan\AppData\Local\Android\Sdk
    exit /b 1
)

echo Android SDK: %ANDROID_HOME%%ANDROID_SDK_ROOT%
echo.

REM Run Gradle
if exist "gradlew.bat" (
    call gradlew.bat assembleDebug
) else (
    gradle assembleDebug
)

echo.
echo === Build Complete ===
echo APK location: app\build\outputs\apk\debug\app-debug.apk