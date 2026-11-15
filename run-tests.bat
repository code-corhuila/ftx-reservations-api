@echo off
REM ===========================================================================
REM  ftx-reservations-api - run ALL the tests locally with Docker (no Java or
REM  Maven needed on this computer). Use it while GitHub Actions is blocked.
REM
REM  Needs: Docker Desktop running, and ftx-reservations-db cloned next to this
REM  folder (..\ftx-reservations-db), because the integration tests run its REAL
REM  migrations.
REM ===========================================================================
setlocal
cd /d "%~dp0"
if not exist "..\ftx-reservations-db\01_ddl" (
  echo [ERROR] No encuentro ..\ftx-reservations-db. Clonalo al lado de esta carpeta.
  pause & exit /b 1
)
set PGPASS=it%RANDOM%%RANDOM%%RANDOM%
docker network create ftx-test >nul 2>&1
docker rm -f ftx-test-pg >nul 2>&1
echo == 1/3 Iniciando PostgreSQL 16 de prueba...
docker run -d --name ftx-test-pg --network ftx-test -e POSTGRES_DB=ftx -e POSTGRES_USER=it_admin -e POSTGRES_PASSWORD=%PGPASS% postgres:16-alpine >nul
:wait
docker exec ftx-test-pg pg_isready -U it_admin -d ftx >nul 2>&1 || (timeout /t 1 >nul & goto wait)
timeout /t 2 >nul
echo == 2/3 Ejecutando mvn verify (unitarias, web e integracion)...
docker run --rm --network ftx-test ^
  -v "%CD%":/work/ftx-reservations-api ^
  -v "%CD%\..\ftx-reservations-db":/work/ftx-reservations-db:ro ^
  -v ftx-m2:/root/.m2 ^
  -w /work/ftx-reservations-api ^
  -e FTX_IT_JDBC_URL=jdbc:postgresql://ftx-test-pg:5432/ftx ^
  -e FTX_IT_ADMIN_USER=it_admin ^
  -e FTX_IT_ADMIN_PASSWORD=%PGPASS% ^
  maven:3.9.9-eclipse-temurin-21 mvn -B verify -Dreservations.db.dir=/work/ftx-reservations-db
set RESULT=%ERRORLEVEL%
echo == 3/3 Limpiando...
docker rm -f ftx-test-pg >nul 2>&1
docker network rm ftx-test >nul 2>&1
if %RESULT%==0 (echo. & echo ===== TODAS LAS PRUEBAS PASARON =====) else (echo. & echo ===== HAY PRUEBAS FALLANDO: copia el error y envialo =====)
pause
exit /b %RESULT%
