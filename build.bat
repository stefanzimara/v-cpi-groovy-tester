@echo off
setlocal
rem Baut das JAR. Zusaetzliche Argumente gehen an Maven durch, z.B.:
rem   build.bat -Pgroovy3
cd /d "%~dp0"
call mvn -q -B clean package %*
if errorlevel 1 exit /b %ERRORLEVEL%
echo Fertig: target\cpi-groovy-tester.jar
