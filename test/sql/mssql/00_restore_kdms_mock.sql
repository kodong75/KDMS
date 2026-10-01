/*
  목적    : KIS 의 MIG_MOCK 을 백업(COPY_ONLY)해 KDMS 시험 원천 KDMS_MOCK 으로 복원한다(plan.md §1.2, §2.1).
            CDC 를 켠 테이블은 TRUNCATE 가 안 되고 테이블을 지우면 캡처 인스턴스도 지워지므로, KIS 스크립트가 쓰는 MIG_MOCK 은 건드리지 않는다.
            복원은 스키마·데이터·IDENTITY 현재값·SEQUENCE·계산 컬럼·트리거·DB 사용자를 그대로 가져온다.
  실행    : 노트북, Windows 인증 관리자(-E, sysadmin). docs/test-env.md §2
            sqlcmd -S localhost,1433 -E -f 65001 -b -I -i test/sql/mssql/00_restore_kdms_mock.sql -v REPLACE=0 -o runs/<시각>_p0_restore.txt
  변수    : $(REPLACE)  0 = KDMS_MOCK 이 있으면 멈춤(기본), 1 = CDC 를 끄고 지운 뒤 다시 복원(시험 데이터 초기화)
  재실행  : REPLACE=1 이면 안전(KDMS_MOCK 의 변경 이력·CDC 설정은 사라진다. 10_enable_cdc.sql 을 다시 실행).
  RDS     : 해당 없음(시험 환경 전용). RDS 는 msdb.dbo.rds_backup_database / rds_restore_database.
  비고    : 동의어 dbo.syn_region·뷰 vw_issuer_region 은 MIG_MOCK_REF 를 그대로 가리킨다(읽기 전용 참조, MVP 대상 아님).
*/
:on error exit
SET NOCOUNT ON;
USE master;

IF DB_ID(N'MIG_MOCK') IS NULL
    RAISERROR(N'MIG_MOCK 이 없다. KIS sql/10_mssql/10~12 를 먼저 실행한다.', 16, 1);
GO

-- 이미 있으면: REPLACE=1 일 때만 CDC 를 끄고(캡처·정리 Job 제거) 지운다
IF DB_ID(N'KDMS_MOCK') IS NOT NULL
BEGIN
    IF N'$(REPLACE)' <> N'1'
        RAISERROR(N'KDMS_MOCK 이 이미 있다. 다시 만들려면 -v REPLACE=1', 16, 1);
    ELSE
    BEGIN
        IF EXISTS (SELECT 1 FROM sys.databases WHERE name = N'KDMS_MOCK' AND is_cdc_enabled = 1)
            EXEC (N'USE KDMS_MOCK; EXEC sys.sp_cdc_disable_db;');
        ALTER DATABASE KDMS_MOCK SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
        DROP DATABASE KDMS_MOCK;
        PRINT N'dropped KDMS_MOCK';
    END
END
GO

DECLARE @bak  nvarchar(4000) = CONCAT(CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(4000)), N'\KDMS_MOCK_from_MIG_MOCK.bak');
DECLARE @data nvarchar(4000) = CAST(SERVERPROPERTY('InstanceDefaultDataPath') AS nvarchar(4000));
DECLARE @log  nvarchar(4000) = CAST(SERVERPROPERTY('InstanceDefaultLogPath')  AS nvarchar(4000));

-- COPY_ONLY: MIG_MOCK 의 백업 체인(차등·로그 백업 기준)을 바꾸지 않는다
BACKUP DATABASE MIG_MOCK TO DISK = @bak WITH COPY_ONLY, INIT, FORMAT, CHECKSUM;

-- 논리 파일 이름을 읽어 KDMS_MOCK 용 물리 파일로 옮긴다
DECLARE @files TABLE (
    LogicalName nvarchar(128), PhysicalName nvarchar(260), [Type] char(1), FileGroupName nvarchar(128), Size numeric(20,0),
    MaxSize numeric(20,0), FileId bigint, CreateLSN numeric(25,0), DropLSN numeric(25,0), UniqueId uniqueidentifier,
    ReadOnlyLSN numeric(25,0), ReadWriteLSN numeric(25,0), BackupSizeInBytes bigint, SourceBlockSize int, FileGroupId int,
    LogGroupGUID uniqueidentifier, DifferentialBaseLSN numeric(25,0), DifferentialBaseGUID uniqueidentifier, IsReadOnly bit,
    IsPresent bit, TDEThumbprint varbinary(32), SnapshotUrl nvarchar(360));
INSERT INTO @files EXEC (N'RESTORE FILELISTONLY FROM DISK = N''' + @bak + N'''');

DECLARE @move nvarchar(max) = N'';
SELECT @move = @move + N', MOVE N''' + LogicalName + N''' TO N'''
             + CASE [Type] WHEN 'L' THEN @log ELSE @data END
             + N'KDMS_MOCK_' + LogicalName + CASE [Type] WHEN 'L' THEN N'.ldf' ELSE N'.mdf' END + N''''
FROM @files;

DECLARE @sql nvarchar(max) = N'RESTORE DATABASE KDMS_MOCK FROM DISK = N''' + @bak + N''' WITH CHECKSUM, RECOVERY' + @move;
EXEC (@sql);
PRINT N'restored KDMS_MOCK';
GO

-- 전체 적재가 일관된 시점으로 읽도록(plan.md §4.3). 복구 모델은 모의 환경이라 SIMPLE(KIS MIG_CDC 와 같음)
ALTER DATABASE KDMS_MOCK SET RECOVERY SIMPLE;
ALTER DATABASE KDMS_MOCK SET ALLOW_SNAPSHOT_ISOLATION ON;
GO

-- 확인: 테이블별 건수가 MIG_MOCK 과 같아야 한다
SELECT name, collation_name, recovery_model_desc, snapshot_isolation_state_desc, is_cdc_enabled
FROM sys.databases WHERE name IN (N'MIG_MOCK', N'KDMS_MOCK');

SELECT s.name AS schema_name, t.name AS table_name,
       (SELECT SUM(p.rows) FROM MIG_MOCK.sys.partitions p WHERE p.index_id IN (0, 1)
          AND p.object_id = OBJECT_ID(N'MIG_MOCK.' + s.name + N'.' + t.name)) AS mig_mock_rows,
       (SELECT SUM(p.rows) FROM KDMS_MOCK.sys.partitions p WHERE p.object_id = t.object_id AND p.index_id IN (0, 1)) AS kdms_mock_rows
FROM KDMS_MOCK.sys.tables t JOIN KDMS_MOCK.sys.schemas s ON s.schema_id = t.schema_id
ORDER BY s.name, t.name;
