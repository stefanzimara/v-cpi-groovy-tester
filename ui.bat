@echo off
setlocal
rem CPI Groovy Tester - Weboberflaeche auf http://localhost:8899
cd /d "%~dp0"
set "JAR=target\cpi-groovy-tester.jar"
if not exist "%JAR%" (
    echo JAR fehlt - baue es zuerst mit build.bat
    exit /b 2
)
java -jar "%JAR%" ui %*
exit /b %ERRORLEVEL%
