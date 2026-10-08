#Requires -Version 7
<#
.SYNOPSIS
  6단계 MVP 리허설(docs/rehearsal.md)에서 노트북이 할 일을 한 줄 명령으로 묶는다. Mac 의 scripts/rehearsal.sh 가 단계마다 이 명령을 알려 준다.
.DESCRIPTION
  모두 Invoke-KdmsSql.ps1(Windows 인증, sqlcmd -f 65001, 결과는 runs/YYYYMMDD_HHMM_<Stage>.txt)로 실행한다.
    restore        시험 원천을 처음 상태로: 00_restore(REPLACE=1) → 10_enable_cdc → 20_grant (KDMS_MOCK 이 없으면 REPLACE=0)
    writes         원천 쓰기(30_writes.sql) -Sec 초 동안. 끝날 때까지 이 창을 쓴다
    capture-stop   CDC 캡처 Job 중지(T-C09, 40_capture_job.sql)
    capture-start  CDC 캡처 Job 다시 시작
    tc10           변경 몇 건 → 캡처 → 모든 변경 기록 정리(T-C10, 50_cdc_cleanup_all.sql). Mac 의 kdms sync 를 멈춘 뒤에
.EXAMPLE
  ./scripts/Invoke-KdmsRehearsal.ps1 -Step restore
  ./scripts/Invoke-KdmsRehearsal.ps1 -Step writes -Sec 300
#>
param(
    [Parameter(Mandatory)][ValidateSet('restore', 'writes', 'capture-stop', 'capture-start', 'tc10')][string]$Step,
    [int]$Sec = 300,
    [string]$Server = 'localhost,1433',
    [string]$Login = 'kodong_ms'
)
$ErrorActionPreference = 'Stop'
$sql = Join-Path $PSScriptRoot 'Invoke-KdmsSql.ps1'

function Run([string]$File, [string]$Stage, [hashtable]$Var = @{}) {
    & $sql -File $File -Stage $Stage -Var $Var -Server $Server
    if ($LASTEXITCODE -ne 0) { Write-Host "실패: $File (exit $LASTEXITCODE). 결과 파일을 Mac 채팅에 붙여 준다" -ForegroundColor Red; exit $LASTEXITCODE }
}

switch ($Step) {
    'restore' {
        $exists = (& sqlcmd -S $Server -E -h -1 -W -Q "SET NOCOUNT ON; SELECT CASE WHEN DB_ID('KDMS_MOCK') IS NULL THEN 0 ELSE 1 END" | Out-String).Trim()
        Run 'test/sql/mssql/00_restore_kdms_mock.sql' 'p6_restore' @{ REPLACE = $exists }
        Run 'test/sql/mssql/10_enable_cdc.sql' 'p6_cdc'
        Run 'test/sql/mssql/20_grant_kdms_login.sql' 'p6_grant' @{ KDMS_LOGIN = $Login }
        Write-Host '원천 되돌림 끝(00 → 10 → 20 모두 exit 0)' -ForegroundColor Green
    }
    'writes' {
        Write-Host "원천 쓰기 ${Sec}초. 끝나면 '쓰기 끝' 결과 파일 경로가 나온다"
        Run 'test/sql/mssql/30_writes.sql' 'p6_writes' @{ DURATION_SEC = "$Sec" }
        Write-Host "쓰기 끝 $(Get-Date -Format 'HH:mm:ss')" -ForegroundColor Green
    }
    'capture-stop' { Run 'test/sql/mssql/40_capture_job.sql' 'p6_capture_stop' @{ ACTION = 'stop' } }
    'capture-start' { Run 'test/sql/mssql/40_capture_job.sql' 'p6_capture_start' @{ ACTION = 'start' } }
    'tc10' { Run 'test/sql/mssql/50_cdc_cleanup_all.sql' 'p6_tc10' }
}
