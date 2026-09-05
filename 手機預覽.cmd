@echo off
chcp 65001 >nul
title PickPico 手機預覽

echo 請先用 USB 或無線偵錯連接一支 Android 手機。
echo 正在建置並開啟 PickPico Preview...
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\preview-on-phone.ps1"

echo.
if errorlevel 1 (
    echo 尚未完成。請查看上方訊息；最常見原因是手機尚未允許偵錯。
) else (
    echo 完成：手機上已開啟 PickPico Preview。
)
pause
