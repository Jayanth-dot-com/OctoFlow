#!/usr/bin/env bash

# OctoFlow Build Script

set -e

echo "=== OctoFlow Build ==="
echo ""

# Check for Android SDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "ERROR: Android SDK not found. Please set ANDROID_HOME or ANDROID_SDK_ROOT."
    echo "  Example: export ANDROID_HOME=~/Android/Sdk"
    exit 1
fi

echo "Android SDK: ${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
echo ""

# Check for Gradle
if ! command -v gradle &> /dev/null; then
    echo "Gradle not found. Using gradlew wrapper..."
    if [ -f "./gradlew" ]; then
        ./gradlew assembleDebug
    else
        echo "ERROR: No gradle wrapper found. Please run 'gradle wrapper' first."
        exit 1
    fi
else
    gradle assembleDebug
fi

echo ""
echo "=== Build Complete ==="
echo "APK location: app/build/outputs/apk/debug/app-debug.apk"