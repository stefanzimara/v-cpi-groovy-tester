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
rem
rem Zur Bauweise: die Faelle stehen bewusst einzeln untereinander, statt in
rem einer for-Schleife, und :run_case bekommt seine Argumente ueber %* statt
rem sie mit shift einzusammeln. Beides ist Absicht - ein "call" in eine
rem Unterroutine, die shift und goto benutzt, verhaelt sich aus einem
rem for-Block heraus unzuverlaessig. Genau daran scheiterten hier einmal
rem alle acht PerPerson-Faelle, waehrend die zwei ausserhalb der Schleife
rem aufgerufenen liefen.
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
:parse_args
if "%~1"=="" goto args_parsed
if /i "%~1"=="--update"   set "UPDATE=1"
if /i "%~1"=="--profiles" set "PROFILES=1"
shift
goto parse_args
:args_parsed

set "EXPECTED_ROOT=testdata\expected"
set "WORK=%TEMP%\cpi-smoke-%RANDOM%"
mkdir "%WORK%" >nul 2>nul

set /a FAILURES=0
set /a COMPARISONS=0

if "%PROFILES%"=="1" goto both_profiles

if not exist "target\cpi-groovy-tester.jar" (
    echo JAR fehlt - baue es zuerst mit build.bat
    goto abort
)
call :all_cases
goto summary

:both_profiles
echo == Profil groovy4 ==
call build.bat -Pgroovy4 >nul
if errorlevel 1 goto build_failed
call :all_cases
echo == Profil groovy3 ==
call build.bat -Pgroovy3 >nul
if errorlevel 1 goto build_failed
call :all_cases
goto summary

:build_failed
echo   Build fehlgeschlagen
:abort
rmdir /s /q "%WORK%" >nul 2>nul
exit /b 2

:summary
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
set "LABEL=order-v1"
call :run_case --config testdata\config.json
set "LABEL=order-v2"
call :run_case --config testdata\config.json --script scripts\OrderToXml_v2.groovy

set "LABEL=perperson-v1-sample"
call :run_case --config examples\config.json --script examples\PerPersonToXml.groovy --body examples\PerPerson_sample.json
set "LABEL=perperson-v2-sample"
call :run_case --config examples\config.json --script examples\PerPersonToXml_v2.groovy --body examples\PerPerson_sample.json

set "LABEL=perperson-v1-kein_treffer"
call :run_case --config examples\config.json --script examples\PerPersonToXml.groovy --body examples\PerPerson_kein_treffer.json
set "LABEL=perperson-v2-kein_treffer"
call :run_case --config examples\config.json --script examples\PerPersonToXml_v2.groovy --body examples\PerPerson_kein_treffer.json

set "LABEL=perperson-v1-ahv_zeitscheiben"
call :run_case --config examples\config.json --script examples\PerPersonToXml.groovy --body examples\PerPerson_ahv_zeitscheiben.json
set "LABEL=perperson-v2-ahv_zeitscheiben"
call :run_case --config examples\config.json --script examples\PerPersonToXml_v2.groovy --body examples\PerPerson_ahv_zeitscheiben.json

set "LABEL=perperson-v1-hiring_not_completed"
call :run_case --config examples\config.json --script examples\PerPersonToXml.groovy --body examples\PerPerson_hiring_not_completed.json
set "LABEL=perperson-v2-hiring_not_completed"
call :run_case --config examples\config.json --script examples\PerPersonToXml_v2.groovy --body examples\PerPerson_hiring_not_completed.json
goto :eof

rem Bezeichnung des Falls steht in LABEL, die Argumente fuer run.bat kommen als %*
:run_case
set "ACTUAL=%WORK%\%LABEL%"
set "CASELOG=%WORK%\%LABEL%.log"
call run.bat run %* --outdir "%ACTUAL%" --quiet >"%CASELOG%" 2>&1
if not errorlevel 1 goto case_ran

echo   FEHLER   %LABEL%  Lauf abgebrochen
rem Den Grund zeigen statt ihn im Logfile zu begraben - ohne diese Zeilen
rem stand beim ersten Windows-Lauf nur "Lauf abgebrochen" da.
set /a LOGLINE=0
for /f "usebackq delims=" %%L in ("%CASELOG%") do (
    set /a LOGLINE+=1
    if !LOGLINE! LEQ 3 echo              %%L
)
set /a FAILURES+=1
goto :eof

:case_ran
set "EXPECTED=%EXPECTED_ROOT%\%LABEL%"
if not "%UPDATE%"=="1" goto case_compare

if exist "%EXPECTED%" rmdir /s /q "%EXPECTED%"
mkdir "%EXPECTED%" >nul 2>nul
for %%G in ("%ACTUAL%\output.*" "%ACTUAL%\properties.json" "%ACTUAL%\headers.json") do (
    if exist "%%~G" copy /y "%%~G" "%EXPECTED%\" >nul
)
echo   NEU      %LABEL%  Sollstand geschrieben
goto :eof

:case_compare
if exist "%EXPECTED%" goto case_diff
echo   FEHLER   %LABEL%  kein Sollstand - einmal mit --update erzeugen
set /a FAILURES+=1
goto :eof

:case_diff
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
if defined DIFFERING goto case_differs
echo   OK       %LABEL%
goto :eof

:case_differs
echo   ABWEICH  %LABEL% !DIFFERING!
set /a FAILURES+=1
goto :eof
