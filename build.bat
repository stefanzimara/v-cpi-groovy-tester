@echo off
setlocal
rem Baut das JAR. Zusaetzliche Argumente gehen an Maven durch, z.B.:
rem   build.bat -Pgroovy3
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo Java wurde nicht gefunden. JDK 17 oder neuer installieren und
    echo sicherstellen, dass es im PATH liegt ^(z.B. Eclipse Temurin,
    echo https://adoptium.net^).
    exit /b 2
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo Maven ^(mvn^) wurde nicht gefunden. Maven installieren und
    echo sicherstellen, dass es im PATH liegt: https://maven.apache.org/download.cgi
    echo Schnellweg mit winget: winget install Apache.Maven
    echo Nach der Installation ein NEUES Terminalfenster oeffnen.
    exit /b 2
)

call mvn -q -B clean package %*
if errorlevel 1 exit /b %ERRORLEVEL%
echo Fertig: target\cpi-groovy-tester.jar
