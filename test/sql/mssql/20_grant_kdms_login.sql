/*
  목적    : KDMS 원천 로그인($(KDMS_LOGIN), 시험은 kodong_ms)에 KDMS_MOCK 읽기 권한을 준다. 쓰기 권한은 주지 않는다.
            KDMS 가 하는 일: 전체 적재 SELECT(SNAPSHOT 격리), 카탈로그 조회, Debezium CDC 스트리밍(변경 테이블·CDC 함수 읽기).
  실행    : 노트북, Windows 인증 관리자(-E). 10_enable_cdc.sql 다음.
            sqlcmd -S localhost,1433 -E -f 65001 -b -I -i test/sql/mssql/20_grant_kdms_login.sql -v KDMS_LOGIN=kodong_ms -o runs/<시각>_p0_grant.txt
  변수    : $(KDMS_LOGIN)  이미 있는 SQL 로그인(KIS sql/10_mssql/00_login_mig.sql 이 만든 것)
  재실행  : 안전.
  권한 근거(Debezium 3.7 SQL Server 커넥터 문서, 2026-10-01 확인):
    - 캡처 인스턴스에 게이팅 역할이 있으면 그 역할의 구성원이어야 변경 테이블을 읽는다 → kdms_cdc_reader
    - 캡처 대상 테이블의 SELECT(캡처한 모든 컬럼) → db_datareader
    - SQL Server Agent 상태 확인(sys.dm_server_services)에 VIEW SERVER STATE(서버 수준) → KIS 00_login_mig.sql 이 이미 준다. 없으면 아래 주석 해제
    - 확인: 이 로그인으로 EXEC sys.sp_cdc_help_change_data_capture 결과가 비어 있지 않아야 한다(아래 마지막 블록)
  최소 권한 목록은 4단계에서 Debezium 실측(스트리밍 시작·스키마 읽기)으로 확정하고 docs/test-env.md 에 고친다(plan.md R1).
*/
:on error exit
SET NOCOUNT ON;
USE master;
IF SUSER_ID(N'$(KDMS_LOGIN)') IS NULL
    RAISERROR(N'로그인 $(KDMS_LOGIN) 이 없다(KIS sql/10_mssql/00_login_mig.sql).', 16, 1);
-- GRANT VIEW SERVER STATE TO [$(KDMS_LOGIN)];
GO

USE KDMS_MOCK;
GO
IF USER_ID(N'$(KDMS_LOGIN)') IS NULL
    CREATE USER [$(KDMS_LOGIN)] FOR LOGIN [$(KDMS_LOGIN)];
ALTER ROLE db_datareader   ADD MEMBER [$(KDMS_LOGIN)];
ALTER ROLE kdms_cdc_reader ADD MEMBER [$(KDMS_LOGIN)];
GRANT SELECT ON SCHEMA::cdc TO [$(KDMS_LOGIN)];      -- 변경 테이블·lsn_time_mapping·change_tables
GRANT VIEW DEFINITION TO [$(KDMS_LOGIN)];            -- 카탈로그(계산 컬럼 식·트리거·기본값) 읽기
GRANT VIEW DATABASE STATE TO [$(KDMS_LOGIN)];        -- 로그 사용률·복제 대기 표시(plan.md R2)
GO

-- 확인: 이 로그인으로 바꿔 캡처 인스턴스가 보이는지
SELECT dp.name AS member, r.name AS role_name
FROM sys.database_role_members m
JOIN sys.database_principals r  ON r.principal_id  = m.role_principal_id
JOIN sys.database_principals dp ON dp.principal_id = m.member_principal_id
WHERE dp.name = N'$(KDMS_LOGIN)';

EXECUTE AS LOGIN = N'$(KDMS_LOGIN)';
    SELECT SUSER_SNAME() AS checked_as,
           HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER STATE') AS view_server_state,
           IS_ROLEMEMBER(N'kdms_cdc_reader') AS in_cdc_role;
    EXEC sys.sp_cdc_help_change_data_capture;           -- 비어 있으면 CDC 를 못 읽는다
REVERT;
