@echo off
rem Double-click this to start the Chalkline Deployer.
rem
rem It runs Deploy-Chalkline.ps1 with the execution policy relaxed for this one
rem PowerShell process only; nothing about the computer's policy is changed.
rem The deployer then asks for administrator rights.
powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0Deploy-Chalkline.ps1"
