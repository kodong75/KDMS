/*
  목적    : KDMS_MOCK 에 CDC(Change Data Capture, 변경 데이터 캡처)를 켜고, PK 가 있는 dbo 테이블 전부(MVP 범위, plan.md §3.1)를 캡처한다.
            캡처 인스턴스는 게이팅 역할 kdms_cdc_reader 로 묶는다. 이 역할의 구성원만 변경 테이블을 읽는다(운영과 같은 방식).
  실행    : 노트북, Windows 인증 관리자(-E). sp_cdc_enable_db 는 sysadmin 필요. SQL Server Agent 가 실행 중이어야 한다.
            sqlcmd -S localhost,1433 -E -f 65001 -b -I -i test/sql/mssql/10_enable_cdc.sql -o runs/<시각>_p0_cdc.txt
  재실행  : 안전. 이미 캡처 중인 테이블은 건너뛴다.
  RDS     : sys.sp_cdc_enable_db 대신 EXEC msdb.dbo.rds_cdc_enable_db 'KDMS_MOCK'. 나머지는 같다(KIS:docs/zero-downtime.md §3).
  비고    : 계산 컬럼은 변경 테이블에 들어가도 값이 항상 NULL 이다. KDMS 는 대상에서 GENERATED 로 다시 계산한다(plan.md §4.4).
            supports_net_changes = 0: KDMS 는 모든 변경을 순서대로 반영한다(net changes 를 쓰지 않는다).
*/
:on error exit
SET NOCOUNT ON;
USE master;

-- servicename 은 OS 언어에 따라 바뀐다(한국어 Windows: 'SQL Server 에이전트 (MSSQLSERVER)'). 실행 파일 이름 SQLAGENT 로 찾는다
IF NOT EXISTS (SELECT 1 FROM sys.dm_server_services WHERE filename LIKE N'%SQLAGENT%' AND status_desc = N'Running')
    RAISERROR(N'SQL Server Agent 가 실행 중이 아니다. 관리자 PowerShell: Start-Service SQLSERVERAGENT', 16, 1);
IF DB_ID(N'KDMS_MOCK') IS NULL
    RAISERROR(N'KDMS_MOCK 이 없다. 00_restore_kdms_mock.sql 을 먼저 실행한다.', 16, 1);
GO

USE KDMS_MOCK;
GO
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND is_cdc_enabled = 1)
BEGIN
    EXEC sys.sp_cdc_enable_db;
    PRINT N'CDC enabled on KDMS_MOCK';
END
GO

IF DATABASE_PRINCIPAL_ID(N'kdms_cdc_reader') IS NULL
    CREATE ROLE kdms_cdc_reader;
GO

-- PK 가 있는 dbo 사용자 테이블 중 아직 캡처하지 않는 것
DECLARE @t sysname;
DECLARE c CURSOR LOCAL FAST_FORWARD FOR
    SELECT t.name
    FROM sys.tables t
    WHERE t.schema_id = SCHEMA_ID(N'dbo')
      AND t.is_ms_shipped = 0
      AND t.is_tracked_by_cdc = 0
      AND EXISTS (SELECT 1 FROM sys.indexes i WHERE i.object_id = t.object_id AND i.is_primary_key = 1)
    ORDER BY t.name;
OPEN c;
FETCH NEXT FROM c INTO @t;
WHILE @@FETCH_STATUS = 0
BEGIN
    EXEC sys.sp_cdc_enable_table
         @source_schema        = N'dbo',
         @source_name          = @t,
         @role_name            = N'kdms_cdc_reader',
         @supports_net_changes = 0;
    PRINT CONCAT(N'capture enabled: dbo.', @t);
    FETCH NEXT FROM c INTO @t;
END
CLOSE c;
DEALLOCATE c;
GO

-- 확인
SELECT name, is_cdc_enabled, snapshot_isolation_state_desc FROM sys.databases WHERE database_id = DB_ID();
SELECT s.name AS schema_name, t.name AS table_name, t.is_tracked_by_cdc,
       CASE WHEN EXISTS (SELECT 1 FROM sys.indexes i WHERE i.object_id = t.object_id AND i.is_primary_key = 1) THEN 1 ELSE 0 END AS has_pk
FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id
WHERE t.is_ms_shipped = 0
ORDER BY s.name, t.name;
SELECT capture_instance, OBJECT_NAME(source_object_id) AS source_table, role_name, supports_net_changes,
       CONVERT(varchar(22), start_lsn, 1) AS start_lsn
FROM cdc.change_tables ORDER BY capture_instance;
EXEC sys.sp_cdc_help_jobs;
