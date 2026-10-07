@echo off
REM ===========================================================================
REM  Build the Living World Launcher into a single, self-contained exe.
REM  Run this ONCE on a machine with the .NET 8 SDK installed:
REM      https://dotnet.microsoft.com/download/dotnet/8.0
REM  It produces dist\LivingWorld.exe - the player double-clicks that. No .NET
REM  runtime and no PowerShell are needed to run it.
REM ===========================================================================
setlocal
cd /d "%~dp0"

where dotnet >nul 2>&1
if errorlevel 1 (
  echo [FAIL] The .NET 8 SDK was not found on PATH.
  echo        Install it from https://dotnet.microsoft.com/download/dotnet/8.0 and run this again.
  pause
  exit /b 1
)

echo Building the launcher (this can take a minute the first time)...
dotnet publish "launcher-app\LivingWorld.csproj" -c Release -o "launcher-app\publish"
if errorlevel 1 (
  echo [FAIL] Build failed - see the messages above.
  pause
  exit /b 1
)

copy /y "launcher-app\publish\LivingWorld.exe" "LivingWorld.exe" >nul
if errorlevel 1 (
  echo [FAIL] Could not copy LivingWorld.exe into the dist folder.
  pause
  exit /b 1
)

echo.
echo [ OK ] Built dist\LivingWorld.exe
echo        Double-click it to launch the server (and the game, if you set a client).
echo.
pause
endlocal
