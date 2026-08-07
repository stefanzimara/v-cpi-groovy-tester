@echo off
setlocal enabledelayedexpansion
rem CPI Groovy Tester - Rauchtest ueber alle mitgelieferten Beispiele.
rem
rem   smoke.bat              prueft gegen die Sollstaende in testdata\expected
rem   smoke.bat --update     schreibt die Sollstaende neu (vorher ansehen!)
rem   smoke.bat --profiles   baut groovy4 und groovy3 und prueft beide
rem
rem Verglichen werden output.*, properties.json und headers.json - nicht die
rem Attachments (deren Namen enthalten einen Zeitstempel) und nicht
rem console.log (kann Pfade des ausfuehrenden Rechners enthalten).
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo Java wurde nicht gefunden. JDK 17 oder neuer installieren und
    echo sicherstellen, dass es im PATH liegt ^(z.B. Eclipse Temurin,
    echo https://adoptium.net^).
    exit /b 2
)

set "UPDATE=0"
set "PROFILES=0"
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--update"   set "UPDATE=1"
if /i "%~1"=="--profiles" set "PROFILES=1"
shift
goto parse
:parsed

set "EXPECTED_ROOT=testdata\expected"
set "WORK=%TEMP%\cpi-smoke-%RANDOM%"
mkdir "%WORK%" >nul 2>nul

set /a FAILURES=0
set /a COMPARISONS=0

if "%PROFILES%"=="1" (
    for %%P in (groovy4 groovy3) do (
        echo == Profil %%P ==
        call build.bat -P%%P >nul
        if errorlevel 1 ( echo   Build fehlgeschlagen & rmdir /s /q "%WORK%" & exit /b 2 )
        call :all_cases
    )
) else (
    if not exist "target\cpi-groovy-tester.jar" (
        echo JAR fehlt - baue es zuerst mit build.bat
        rmdir /s /q "%WORK%"
        exit /b 2
    )
    call :all_cases
)

echo.
rmdir /s /q "%WORK%" >nul 2>nul
if "%UPDATE%"=="1" (
    echo Sollstaende neu geschrieben. Vor dem Einchecken mit "git diff" ansehen -
    echo eine unbeabsichtigte Aenderung sieht hier genauso aus wie eine gewollte.
    exit /b 0
)
echo !COMPARISONS! Vergleiche, !FAILURES! Faelle mit Abweichung.
if !FAILURES! GTR 0 exit /b 1
exit /b 0

rem ---------------------------------------------------------------- Faelle

:all_cases
call :run_case order-v1 --config testdata\config.json
call :run_case order-v2 --config testdata\config.json --script scripts\OrderToXml_v2.groovy
for %%F in (sample kein_treffer ahv_zeitscheiben hiring_not_completed) do (
    call :run_case perperson-v1-%%F --config examples\config.json --script examples\PerPersonToXml.groovy --body examples\PerPerson_%%F.json
    call :run_case perperson-v2-%%F --config examples\config.json --script examples\PerPersonToXml_v2.groovy --body examples\PerPerson_%%F.json
)
goto :eof

rem %1 = Bezeichnung, danach die Argumente fuer run.bat
:run_case
set "LABEL=%~1"
shift
set "ARGS="
:collect
if "%~1"=="" goto collected
set "ARGS=!ARGS! %1"
shift
goto collect
:collected

set "ACTUAL=%WORK%\%LABEL%"
call run.bat run !ARGS! --outdir "%ACTUAL%" --quiet >"%WORK%\%LABEL%.log" 2>&1
if errorlevel 1 (
    echo   FEHLER   %LABEL%  Lauf abgebrochen
    set /a FAILURES+=1
    goto :eof
)

set "EXPECTED=%EXPECTED_ROOT%\%LABEL%"

if "%UPDATE%"=="1" (
    if exist "%EXPECTED%" rmdir /s /q "%EXPECTED%"
    mkdir "%EXPECTED%" >nul 2>nul
    for %%G in ("%ACTUAL%\output.*" "%ACTUAL%\properties.json" "%ACTUAL%\headers.json") do (
        if exist "%%~G" copy /y "%%~G" "%EXPECTED%\" >nul
    )
    echo   NEU      %LABEL%  Sollstand geschrieben
    goto :eof
)

if not exist "%EXPECTED%" (
    echo   FEHLER   %LABEL%  kein Sollstand - einmal mit --update erzeugen
    set /a FAILURES+=1
    goto :eof
)

set "DIFFERING="
for %%G in ("%EXPECTED%\*") do (
    set /a COMPARISONS+=1
    if not exist "%ACTUAL%\%%~nxG" (
        set "DIFFERING=!DIFFERING! %%~nxG(fehlt)"
    ) else (
        fc /b "%%~G" "%ACTUAL%\%%~nxG" >nul 2>nul
        if errorlevel 1 set "DIFFERING=!DIFFERING! %%~nxG"
    )
)

if defined DIFFERING (
    echo   ABWEICH  %LABEL% !DIFFERING!
    set /a FAILURES+=1
) else (
    echo   OK       %LABEL%
)
goto :eof
