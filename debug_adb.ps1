# ADB Logcat Monitor for Sony Camera Debugging (PowerShell)
# Filters for Dalvik GC, Crashes, and CameraEx lifecycle

Write-Host "Starting ADB Logcat..." -ForegroundColor Cyan
adb logcat -v time DalvikVM:D AndroidRuntime:E CameraEx:V *:S
