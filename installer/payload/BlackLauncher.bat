@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "JAR="
for %%F in ("%~dp0black-launcher-*.jar") do set "JAR=%%~fF"
if not defined JAR if exist "%~dp0black-launcher.jar" set "JAR=%~dp0black-launcher.jar"

if not defined JAR (
  echo [布莱克启动器] 未找到程序 jar 文件。
  echo 请重新安装，或确认 black-launcher-*.jar 与本脚本在同一目录。
  pause
  exit /b 1
)

where javaw >nul 2>&1
if errorlevel 1 (
  where java >nul 2>&1
  if errorlevel 1 (
    echo [布莱克启动器] 未检测到 Java。
    echo 请安装 Java 17 或更高版本（64 位），并确保已加入 PATH。
    echo 下载提示：可使用 Eclipse Temurin / Microsoft Build of OpenJDK。
    pause
    exit /b 1
  )
  start "Black Launcher" java -jar "%JAR%"
  exit /b 0
)

start "Black Launcher" javaw -jar "%JAR%"
exit /b 0
