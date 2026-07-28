@echo off
setlocal
rem CPI Groovy Tester - CLI-Modus
rem Beispiel: run.bat run --config testdata\config.json --outdir out
cd /d "%~dp0"
set "JAR=target\cpi-groovy-tester.jar"
if not exist "%JAR%" (
    echo JAR fehlt - baue es zuerst mit build.bat
    exit /b 2
)
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
