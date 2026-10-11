:: ADB Logcat Monitor for Sony Camera Debugging (Windows CMD)
:: Filters for Dalvik GC, Crashes, and CameraEx lifecycle
@echo off
echo Starting ADB Logcat...
adb logcat -v time DalvikVM:D AndroidRuntime:E CameraEx:V *:S
