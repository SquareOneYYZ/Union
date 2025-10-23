@echo off
REM DC600 Test Script Runner for Windows
REM
REM Usage:
REM   run_test.bat                    - Test against localhost:5049
REM   run_test.bat 192.168.1.100      - Test against specific host
REM   run_test.bat 192.168.1.100 5049 - Test against specific host:port

echo ================================================================
echo DC600 Event and Multimedia Upload Test
echo ================================================================
echo.

REM Check if Python is installed
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python is not installed or not in PATH
    echo Please install Python 3.6 or higher
    pause
    exit /b 1
)

REM Set default values
set HOST=localhost
set PORT=5049
set DEVICE_ID=123456789012345

REM Override with command line arguments if provided
if not "%1"=="" set HOST=%1
if not "%2"=="" set PORT=%2
if not "%3"=="" set DEVICE_ID=%3

echo Target Server: %HOST%:%PORT%
echo Device ID: %DEVICE_ID%
echo.
echo Starting test...
echo.

REM Run the test
python test_event_multimedia.py %HOST% %PORT% %DEVICE_ID%

if errorlevel 1 (
    echo.
    echo TEST FAILED!
    pause
    exit /b 1
) else (
    echo.
    echo TEST COMPLETED!
    pause
    exit /b 0
)
