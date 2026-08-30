@echo off
REM ===================================================================
REM  Rebuild the CodeGraph index for the clone at C:\dev\designer-portal
REM
REM  The old index lived on the F: drive and is gone with it. This builds
REM  a fresh one here so code queries work against the working copy.
REM
REM  Takes about 10-20 seconds for ~1,800 files.
REM ===================================================================

cd /d "%~dp0"

echo.
echo ================================================================
echo   INDEXING CODEGRAPH  -  C:\dev\designer-portal
echo ================================================================
echo.

REM Any stale daemon from the old F: project holds a lock; stop it first.
taskkill /f /im node.exe /fi "WINDOWTITLE eq codegraph*" >nul 2>&1

codegraph init . 2>&1
if errorlevel 1 (
  echo.
  echo   init failed - trying a full rebuild instead...
  codegraph index . 2>&1
)

echo.
echo ================================================================
echo   STATUS
echo ================================================================
codegraph status 2>&1

echo.
pause
