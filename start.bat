@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ========================================
echo   布莱克 Black Launcher
echo ========================================
echo.

where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Java，请先安装 JDK 17 或更高版本。
    echo 下载地址: https://adoptium.net/
    pause
    exit /b 1
)

if not exist "gradlew.bat" (
    echo [错误] 找不到 gradlew.bat，请确认脚本位于项目根目录。
    pause
    exit /b 1
)

echo 正在启动...
call ".\gradlew.bat" run --quiet
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
    echo.
    echo [错误] 启动失败，退出码: %EXIT_CODE%
    pause
)

endlocal
exit /b %EXIT_CODE%
