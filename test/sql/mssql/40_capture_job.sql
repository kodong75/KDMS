/*
  목적    : 6단계 리허설 T-C09(plan.md §8.2): CDC 캡처 Job 을 멈추거나 다시 시작한다. SQL Agent 서비스를 멈춘 것과 같은 효과
            (캡처가 로그를 읽지 않아 지연이 늘고 원천 로그가 REPLICATION 대기로 잘리지 않는다). 서비스를 멈추지 않아 관리자 창이 필요 없다.
  실행    : 노트북, Windows 인증 관리자(-E). docs/rehearsal.md
            ./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/40_capture_job.sql -Stage p6_capture_stop -Var @{ ACTION = 'stop' }
  변수    : $(ACTION)  stop | start
  재실행  : 안전. 이미 멈춘 Job 을 stop 하거나 도는 Job 을 start 하면 아무것도 하지 않는다.
  RDS     : msdb.dbo.sp_stop_job / sp_start_job 은 RDS 마스터 계정으로도 된다(SQLAgentOperatorRole).
*/
:on error exit
SET NOCOUNT ON;
USE msdb;

DECLARE @job sysname = N'cdc.KDMS_MOCK_capture';
DECLARE @running bit = CASE WHEN EXISTS (
    SELECT 1 FROM msdb.dbo.sysjobactivity a JOIN msdb.dbo.sysjobs j ON j.job_id = a.job_id
    WHERE j.name = @job AND a.start_execution_date IS NOT NULL AND a.stop_execution_date IS NULL
      AND a.session_id = (SELECT MAX(session_id) FROM msdb.dbo.syssessions)) THEN 1 ELSE 0 END;

IF N'$(ACTION)' = N'stop'
BEGIN
    IF @running = 1
    BEGIN
        EXEC msdb.dbo.sp_stop_job @job_name = @job;
        PRINT CONCAT(N'캡처 Job 중지 ', CONVERT(varchar(23), SYSDATETIME(), 121));
    END
    ELSE PRINT N'캡처 Job 이 이미 멈춰 있다';
END
ELSE IF N'$(ACTION)' = N'start'
BEGIN
    IF @running = 0
    BEGIN
        EXEC msdb.dbo.sp_start_job @job_name = @job;
        PRINT CONCAT(N'캡처 Job 시작 ', CONVERT(varchar(23), SYSDATETIME(), 121));
    END
    ELSE PRINT N'캡처 Job 이 이미 돌고 있다';
END
ELSE
    RAISERROR(N'ACTION 은 stop 또는 start', 16, 1);
GO
WAITFOR DELAY '00:00:02';
SELECT j.name AS job_name,
       CASE WHEN a.start_execution_date IS NOT NULL AND a.stop_execution_date IS NULL THEN N'실행 중' ELSE N'멈춤' END AS state,
       a.start_execution_date, a.stop_execution_date
FROM msdb.dbo.sysjobs j
LEFT JOIN msdb.dbo.sysjobactivity a ON a.job_id = j.job_id AND a.session_id = (SELECT MAX(session_id) FROM msdb.dbo.syssessions)
WHERE j.name = N'cdc.KDMS_MOCK_capture';
