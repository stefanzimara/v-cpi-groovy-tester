@echo off
setlocal
rem CPI Groovy Tester - Weboberflaeche auf http://localhost:8899
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo Java wurde nicht gefunden. JDK 17 oder neuer installieren und
    echo sicherstellen, dass es im PATH liegt ^(z.B. Eclipse Temurin,
    echo https://adoptium.net^).
    exit /b 2
)

set "JAR=target\cpi-groovy-tester.jar"
if not exist "%JAR%" (
    echo JAR fehlt - baue es zuerst mit build.bat
    exit /b 2
)
java -jar "%JAR%" ui %*
exit /b %ERRORLEVEL%
