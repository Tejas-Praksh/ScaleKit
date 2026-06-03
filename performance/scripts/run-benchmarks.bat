@echo off
REM ===========================================================================
REM ScaleKit Performance Benchmark Runner (Windows)
REM ===========================================================================
REM Runs all JMeter load tests and collects results.
REM Prerequisites: JMeter 5.6.3+, Java 21, running ScaleKit instance
REM ===========================================================================

setlocal enabledelayedexpansion

REM ── Configuration ──────────────────────────────────────────────────────────
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%..\.."
set "JMETER_DIR=%SCRIPT_DIR%..\jmeter"
for /f "tokens=1-3 delims=/" %%a in ("%date%") do set "DATESTAMP=%%c%%a%%b"
for /f "tokens=1-2 delims=:." %%a in ("%time: =0%") do set "TIMESTAMP=%%a%%b"
set "RESULTS_DIR=%PROJECT_ROOT%performance\results\%DATESTAMP%_%TIMESTAMP%"
if "%HOST%"=="" set "HOST=localhost"
if "%PORT%"=="" set "PORT=8080"

REM ── Check JMeter ───────────────────────────────────────────────────────────
if "%JMETER_HOME%"=="" (
    where jmeter >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] JMeter not found. Set JMETER_HOME or add jmeter to PATH.
        exit /b 1
    )
    set "JMETER_CMD=jmeter"
) else (
    set "JMETER_CMD=%JMETER_HOME%\bin\jmeter.bat"
)

REM ── Create results directory ───────────────────────────────────────────────
mkdir "%RESULTS_DIR%" 2>nul

echo ==================================================================
echo   ScaleKit Performance Benchmark Suite (Windows)
echo   %date% %time%
echo ==================================================================
echo.

REM ── Run tests ──────────────────────────────────────────────────────────────
call :run_test "url-shortener"     "%JMETER_DIR%\url-shortener-test.jmx"
call :run_test "rate-limiter"       "%JMETER_DIR%\rate-limiter-test.jmx"
call :run_test "cache"              "%JMETER_DIR%\cache-test.jmx"
call :run_test "bloom-filter"       "%JMETER_DIR%\bloom-filter-test.jmx"
call :run_test "consistent-hashing" "%JMETER_DIR%\consistent-hashing-test.jmx"

echo.
echo ==================================================================
echo   All benchmarks complete!
echo   Results: %RESULTS_DIR%
echo ==================================================================

endlocal
exit /b 0

:run_test
set "TEST_NAME=%~1"
set "JMX_FILE=%~2"
echo [INFO] Running %TEST_NAME%...

%JMETER_CMD% -n ^
    -t "%JMX_FILE%" ^
    -l "%RESULTS_DIR%\%TEST_NAME%.csv" ^
    -j "%RESULTS_DIR%\%TEST_NAME%.log" ^
    -JHOST=%HOST% ^
    -JPORT=%PORT% ^
    -e -o "%RESULTS_DIR%\%TEST_NAME%-report"

if errorlevel 1 (
    echo [ERROR] %TEST_NAME% failed.
) else (
    echo [OK]    %TEST_NAME% completed.
)
exit /b 0
