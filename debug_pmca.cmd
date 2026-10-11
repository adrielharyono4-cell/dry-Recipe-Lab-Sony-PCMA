:: PMCA Logcat Monitor for Sony Camera Debugging (Windows CMD)
:: Filters for Dalvik GC, Crashes, and CameraEx lifecycle
@echo off
echo Starting PMCA Logcat...
pmca-cli.exe logcat -v time DalvikVM:D AndroidRuntime:E CameraEx:V *:S
