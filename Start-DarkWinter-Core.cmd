@echo off
setlocal
cd /d "%~dp0"
if exist "build\core\game-client-v14\DarkWinter.exe" (
  start "" "build\core\game-client-v14\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client-v13\DarkWinter.exe" (
  start "" "build\core\game-client-v13\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client-v12\DarkWinter.exe" (
  start "" "build\core\game-client-v12\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client-v11\DarkWinter.exe" (
  start "" "build\core\game-client-v11\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client-v10\DarkWinter.exe" (
  start "" "build\core\game-client-v10\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client-v9\DarkWinter.exe" (
  start "" "build\core\game-client-v9\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client-v8\DarkWinter.exe" (
  start "" "build\core\game-client-v8\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client-v7\DarkWinter.exe" (
  start "" "build\core\game-client-v7\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\game-client\DarkWinter.exe" (
  start "" "build\core\game-client\DarkWinter.exe" --saves "build\core\world-client\saves"
  exit /b 0
)
if exist "build\core\world-client\DarkWinter.exe" (
  start "" "build\core\world-client\DarkWinter.exe"
  exit /b 0
)
echo Build the native game: .\gradlew.bat coreBuildGame
pause
exit /b 1
