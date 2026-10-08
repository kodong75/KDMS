/*
  목적    : 6단계 리허설 T-C10(plan.md §8.2): CDC 보존 기간이 지난 것처럼 만든다.
            kdms sync 를 멈춘 상태에서 변경을 몇 건 넣고, 캡처가 그것을 읽은 뒤 모든 캡처 인스턴스를 최대 LSN 까지 정리한다.
            다음 kdms sync 는 "마지막 위치 뒤 변경이 지워졌다" 로 멈춰야 한다(조용히 틀리지 않음).
  실행    : 노트북, Windows 인증 관리자(-E). kdms sync 가 멈춘 뒤에. docs/rehearsal.md
            ./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/50_cdc_cleanup_all.sql -Stage p6_tc10
  재실행  : 안전(issuer 몇 행의 upd_dtm 을 바꾸고 변경 기록을 지운다). 끝나면 kdms reset --yes 뒤 처음부터 적재한다.
  RDS     : sys.sp_cdc_cleanup_change_table 은 db_owner 로 된다.
  주의    : 시험 DB 전용. 운영에서 실행하면 반영하지 않은 변경이 사라진다.
*/
:on error exit
SET NOCOUNT ON;
SET XACT_ABORT ON;
USE KDMS_MOCK;

DECLARE @before binary(10) = sys.fn_cdc_get_max_lsn();
DECLARE @i int = 0;
WHILE @i < 10
BEGIN
    UPDATE dbo.issuer SET upd_dtm = SYSDATETIME() WHERE issuer_id = (SELECT MIN(issuer_id) FROM dbo.issuer) + @i;
    SET @i += 1;
END
PRINT CONCAT(N'변경 10건 ', CONVERT(varchar(23), SYSDATETIME(), 121));

-- 캡처 Job 이 위 변경을 읽을 때까지(최대 60초)
DECLARE @wait int = 0;
WHILE sys.fn_cdc_get_max_lsn() = @before AND @wait < 60
BEGIN
    WAITFOR DELAY '00:00:01';
    SET @wait += 1;
END
IF sys.fn_cdc_get_max_lsn() = @before
    RAISERROR(N'캡처 Job 이 60초 동안 새 변경을 읽지 않았다. SQL Agent·캡처 Job 을 확인한다(40_capture_job.sql start)', 16, 1);
GO

DECLARE @lsn binary(10) = sys.fn_cdc_get_max_lsn();
DECLARE @ci sysname;
DECLARE c CURSOR LOCAL FAST_FORWARD FOR SELECT capture_instance FROM cdc.change_tables;
OPEN c;
FETCH NEXT FROM c INTO @ci;
WHILE @@FETCH_STATUS = 0
BEGIN
    EXEC sys.sp_cdc_cleanup_change_table @capture_instance = @ci, @low_water_mark = @lsn, @threshold = 1000000;
    FETCH NEXT FROM c INTO @ci;
END
CLOSE c;
DEALLOCATE c;
PRINT CONCAT(N'정리 끝: 모든 캡처 인스턴스를 ', CONVERT(varchar(22), @lsn, 1), N' 까지 지웠다');
SELECT capture_instance, CONVERT(varchar(22), start_lsn, 1) AS start_lsn FROM cdc.change_tables ORDER BY capture_instance;
