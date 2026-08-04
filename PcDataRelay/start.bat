@echo off
REM PcDataRelay - 启动脚本 (Windows)
REM 双击运行, 会弹出控制台窗口显示日志和错误

setlocal
cd /d "%~dp0"

where python >nul 2>nul
if %ERRORLEVEL%==0 (
  python main.py
  goto :end
)

where python3 >nul 2>nul
if %ERRORLEVEL%==0 (
  python3 main.py
  goto :end
)

echo.
echo [错误] 未找到 Python!
echo 请先安装 Python 3.8+ (勾选 Add to PATH): https://www.python.org/downloads/
echo.
pause

:end
endlocal
