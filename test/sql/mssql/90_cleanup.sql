/*
  목적    : KDMS 시험 원천을 지운다. CDC 를 먼저 꺼서 캡처·정리 Job(msdb)을 남기지 않는다. MIG_MOCK 은 건드리지 않는다.
  실행    : 노트북, Windows 인증 관리자(-E).
            sqlcmd -S localhost,1433 -E -f 65001 -b -I -i test/sql/mssql/90_cleanup.sql -o runs/<시각>_p0_cleanup.txt
  재실행  : 안전.
*/
:on error exit
SET NOCOUNT ON;
USE master;
IF DB_ID(N'KDMS_MOCK') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.databases WHERE name = N'KDMS_MOCK' AND is_cdc_enabled = 1)
        EXEC (N'USE KDMS_MOCK; EXEC sys.sp_cdc_disable_db;');
    ALTER DATABASE KDMS_MOCK SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE KDMS_MOCK;
    PRINT N'dropped KDMS_MOCK';
END
ELSE
    PRINT N'KDMS_MOCK does not exist';
GO
SELECT name FROM sys.databases WHERE name LIKE N'%MOCK%';
SELECT name FROM msdb.dbo.sysjobs WHERE name LIKE N'cdc.KDMS_MOCK%';
