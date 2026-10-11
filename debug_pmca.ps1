# PMCA Logcat Monitor for Sony Camera Debugging (PowerShell)
# Filters for Dalvik GC, Crashes, and CameraEx lifecycle

Write-Host "Starting PMCA Logcat..." -ForegroundColor Cyan
pmca-cli.exe logcat -v time DalvikVM:D AndroidRuntime:E CameraEx:V *:S
