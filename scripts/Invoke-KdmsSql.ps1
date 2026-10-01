#Requires -Version 7
<#
.SYNOPSIS
  노트북에서 KDMS 시험 준비 SQL(test/sql/mssql)을 sqlcmd 로 실행하고 결과를 runs/YYYYMMDD_HHMM_<Stage>.txt 에 남긴다.
.DESCRIPTION
  - Windows 인증(-E, 관리자)으로 실행한다. CDC 켜기·복원은 sysadmin 이 필요하다.
  - sqlcmd -f 65001(UTF-8), -b(오류 시 중단), -I(QUOTED_IDENTIFIER), 결과는 -o 로 직접 쓴다(파이프를 거치면 한글이 깨진다).
  - -Var 의 값은 sqlcmd 스크립팅 변수로 넘긴다. 비밀번호를 넘기는 스크립트는 없다.
.EXAMPLE
  ./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/00_restore_kdms_mock.sql -Stage p0_restore -Var @{ REPLACE = '0' }
#>
param(
    [Parameter(Mandatory)][string]$File,
    [Parameter(Mandatory)][string]$Stage,
    [hashtable]$Var = @{},
    [string]$Server = 'localhost,1433'
)
$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$runs  = Join-Path $root 'runs'
New-Item -ItemType Directory -Force -Path $runs | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd_HHmm'
$out   = Join-Path $runs "${stamp}_${Stage}.txt"
if (Test-Path $out) { $out = Join-Path $runs ("{0}_{1}.txt" -f (Get-Date -Format 'yyyyMMdd_HHmmss'), $Stage) }

$sqlArgs = @('-S', $Server, '-E', '-d', 'master', '-f', '65001', '-b', '-I', '-W', '-i', (Join-Path $root $File), '-o', $out)
foreach ($k in $Var.Keys) { $sqlArgs += @('-v', "$k=$($Var[$k])") }

$t0 = Get-Date
& sqlcmd @sqlArgs
$code = $LASTEXITCODE
$sec  = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
Add-Content -Path $out -Encoding utf8 -Value @(
    '',
    "-- file: $File",
    "-- by: Windows:$env:USERDOMAIN\$env:USERNAME  server: $Server",
    "-- exit: $code  elapsed: ${sec}s  at: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
)
Write-Host "결과: $out (exit $code, ${sec}s)"
exit $code
