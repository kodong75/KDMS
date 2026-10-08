/*
  목적    : 4단계 CDC 시험용 "운영 중" 쓰기(plan.md §6 4단계 완료 기준, §8.2 T-C01~T-C07).
            KIS sql/50_cdc/11_mssql_writes.sql 과 같은 방식(반복·무작위 비율·끝난 시각 출력)을 KDMS_MOCK 테이블에 맞췄다.
            이 스크립트가 도는 동안 kdms sync(먼저)와 kdms load(워터마크 기록 뒤)를 실행한다.
            섞는 쓰기(대략 비율, 반복마다 하나):
              rating 입력 22 / 수정 18 / 삭제 8          (트리거 trg_rating_audit 가 rating_hist 에 이력 입력 → 그것도 캡처됨)
              issuer 입력 8(한글 nvarchar·CP949 varchar·GUID 기본값) / 수정 6
              research_doc view_cnt 만 수정 10(LOB body 미변경 → Debezium 이 body 를 보내지 않는다, T-C06)
                           body 수정 3 / 입력 4(긴 한글 본문)
              app_user 입력 3 / 수정 3 / 삭제 2
              code_master 코드(PK) 바꾸기 3(T-C07)
              입력 직후 삭제(다른 트랜잭션) 5 / 여러 테이블을 한 트랜잭션으로 4 / daily_count MERGE 1
            + 500 번째마다 3초 열어 두는 트랜잭션(커밋 순서와 변경 순서가 다르게)
  실행    : 노트북, 별도 PowerShell 7 창(끝날 때까지 기다린다). 쓰기 권한이 필요해 Windows 인증 관리자로 실행한다(kodong_ms 는 읽기 전용).
            ./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/30_writes.sql -Stage p4_writes -Var @{ DURATION_SEC = '300' }
  변수    : $(DURATION_SEC)  쓰기를 계속할 초. 끝난 시각 = 원천 쓰기 중지 시각(다음 kdms sync --drain).
  출력    : 종류별 건수, 끝난 시각, 테이블별 행 수.
  재실행  : 안전(행을 더하고 바꾼다). 시험 데이터를 처음으로 되돌리려면 00_restore_kdms_mock.sql -v REPLACE=1 → 10 → 20.
  RDS     : 해당 없음(시험 환경 전용).
  주의    : 고객 데이터가 아니라 합성 값만 쓴다.
*/
:on error exit
SET NOCOUNT ON;
SET XACT_ABORT ON;
USE KDMS_MOCK;

DECLARE @end datetime2(3) = DATEADD(SECOND, $(DURATION_SEC), SYSDATETIME());
DECLARE @long_tx_every int = 500;
DECLARE @i int = 0, @r int, @id bigint, @iss int, @doc int, @usr int, @new bigint, @cnt int;
DECLARE @grp varchar(20), @code varchar(10), @code2 varchar(10);
DECLARE @tag varchar(12);
DECLARE @n_rating_ins int = 0, @n_rating_upd int = 0, @n_rating_del int = 0, @n_issuer_ins int = 0, @n_issuer_upd int = 0,
        @n_doc_cnt int = 0, @n_doc_body int = 0, @n_doc_ins int = 0, @n_user int = 0, @n_pk int = 0,
        @n_insdel int = 0, @n_multi int = 0, @n_merge int = 0, @n_long int = 0;

PRINT CONCAT(N'쓰기 시작 ', CONVERT(varchar(23), SYSDATETIME(), 121), N', 끝 예정 ', CONVERT(varchar(23), @end, 121));

WHILE SYSDATETIME() < @end
BEGIN
    SET @i += 1;
    SET @r   = ABS(CHECKSUM(NEWID())) % 100;
    SET @tag = LEFT(REPLACE(CONVERT(varchar(36), NEWID()), '-', ''), 12);
    -- 지워진 번호면 0행 변경(그래도 된다)
    SET @id  = 1 + ABS(CHECKSUM(NEWID())) % CAST(IDENT_CURRENT(N'dbo.rating') AS bigint);
    SET @iss = 1 + ABS(CHECKSUM(NEWID())) % CAST(IDENT_CURRENT(N'dbo.issuer') AS int);
    SET @doc = 1 + ABS(CHECKSUM(NEWID())) % CAST(IDENT_CURRENT(N'dbo.research_doc') AS int);
    SET @usr = 1 + ABS(CHECKSUM(NEWID())) % CAST(IDENT_CURRENT(N'dbo.app_user') AS int);

    IF @i % @long_tx_every = 0
    BEGIN
        -- 긴 트랜잭션: 먼저 바꾸고 3초 뒤 커밋. 그 사이 다른 반복의 변경은 없지만(한 세션) 다른 세션(kdms load) 의 읽기와 겹친다
        BEGIN TRAN;
            UPDATE dbo.rating SET outlook_cd = CASE outlook_cd WHEN 'P' THEN 'N' ELSE 'P' END, is_watch = 1 - is_watch WHERE rating_id = @id;
            UPDATE dbo.issuer SET upd_dtm = SYSDATETIME() WHERE issuer_id = @iss;
            WAITFOR DELAY '00:00:03';
        COMMIT;
        SET @n_long += 1;
    END
    ELSE IF @r < 22
    BEGIN
        IF EXISTS (SELECT 1 FROM dbo.issuer WHERE issuer_id = @iss)
        BEGIN
            INSERT INTO dbo.rating (issuer_id, rating_cd, outlook_cd, rating_dt, eff_dtm, issue_amt, coupon_rate, is_watch)
            VALUES (@iss, CHOOSE(1 + @i % 5, 'AAA', 'AA+', 'A-', 'BBB', 'BB+'), CHOOSE(1 + @i % 3, 'P', 'S', 'N'),
                    CAST(SYSDATETIME() AS date), DATEADD(MILLISECOND, @i % 1000, CAST(SYSDATETIME() AS datetime)),
                    CAST(@i AS money) * 1000.0123, CAST((@i % 9000) / 1000.0 AS decimal(9, 4)), @i % 2);
            SET @n_rating_ins += 1;
        END
    END
    ELSE IF @r < 40
    BEGIN
        UPDATE dbo.rating
           SET rating_cd   = CHOOSE(1 + @i % 6, 'AAA', 'AA', 'AA-', 'A+', 'BBB-', 'B'),
               eff_dtm     = DATEADD(MILLISECOND, 7 * (@i % 150), eff_dtm),     -- datetime 3.33ms 반올림(.997 등)
               issue_amt   = issue_amt + 0.0001,
               coupon_rate = NULL
         WHERE rating_id = @id;
        SET @n_rating_upd += @@ROWCOUNT;
    END
    ELSE IF @r < 48
    BEGIN
        DELETE FROM dbo.rating WHERE rating_id = @id;
        SET @n_rating_del += @@ROWCOUNT;
    END
    ELSE IF @r < 56
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM dbo.issuer WHERE issuer_cd = LEFT(@tag, 6))
        BEGIN
            INSERT INTO dbo.issuer (issuer_cd, issuer_nm, issuer_nm_cp949, issuer_nm_en, region_cd, industry_cd, is_listed, upd_dtm)
            VALUES (LEFT(@tag, 6), CONCAT(N'쓰기시험발행사 ', @i, N' (주)'), CONCAT('한글', @i % 1000), CONCAT('Write Test ', @i, '  '),
                    RIGHT(CONCAT('0', @i % 50), 2), CHAR(65 + @i % 26), @i % 2, SYSDATETIME());
            SET @n_issuer_ins += 1;
        END
    END
    ELSE IF @r < 62
    BEGIN
        UPDATE dbo.issuer SET issuer_nm = CONCAT(N'이름변경 ', @i), is_listed = 1 - is_listed, upd_dtm = SYSDATETIME()
         WHERE issuer_id = @iss;
        SET @n_issuer_upd += @@ROWCOUNT;
    END
    ELSE IF @r < 72
    BEGIN
        -- LOB(body) 를 건드리지 않는 수정: Debezium 은 body 를 "값 없음" 으로 보낸다(R5, T-C06)
        UPDATE dbo.research_doc SET view_cnt = view_cnt + 1 WHERE doc_id = @doc;
        SET @n_doc_cnt += @@ROWCOUNT;
    END
    ELSE IF @r < 75
    BEGIN
        UPDATE dbo.research_doc SET body = CONCAT(N'본문 수정 ', @i, N' ', REPLICATE(N'가나다라마바사 ', 1 + @i % 300)), view_cnt = view_cnt + 1
         WHERE doc_id = @doc;
        SET @n_doc_body += @@ROWCOUNT;
    END
    ELSE IF @r < 79
    BEGIN
        IF EXISTS (SELECT 1 FROM dbo.issuer WHERE issuer_id = @iss)
        BEGIN
            INSERT INTO dbo.research_doc (doc_type_cd, issuer_id, title, file_nm, file_path, pub_dtm, body)
            VALUES (CHOOSE(1 + @i % 3, '01', '02', '03'), @iss, CONCAT(N'쓰기 시험 보고서 ', @i), CONCAT('W', @i, '.pdf'),
                    CONCAT(N'/upload/리서치/쓰기/', @i, N'.pdf'), DATEADD(MILLISECOND, 3, CAST(SYSDATETIME() AS datetime)),
                    REPLICATE(CAST(N'긴 본문 한글 텍스트 ' AS nvarchar(max)), 200 + @i % 500));
            SET @n_doc_ins += 1;
        END
    END
    ELSE IF @r < 82
    BEGIN
        INSERT INTO dbo.app_user (login_id, user_nm, email, member_level)
        VALUES (CONCAT('w', @tag), CONCAT(N'쓰기사용자', @i), CONCAT('w', @tag, '@example.invalid'), @i % 5);
        SET @n_user += 1;
    END
    ELSE IF @r < 85
    BEGIN
        UPDATE dbo.app_user SET member_level = (member_level + 1) % 10, user_nm = CONCAT(N'변경', @i) WHERE user_id = @usr;
        SET @n_user += @@ROWCOUNT;
    END
    ELSE IF @r < 87
    BEGIN
        DELETE FROM dbo.app_user WHERE user_id = @usr;
        SET @n_user += @@ROWCOUNT;
    END
    ELSE IF @r < 90
    BEGIN
        -- PK 바꾸기(T-C07): 코드 끝에 X 를 붙이거나 뗀다. Debezium 은 삭제 + 입력 두 이벤트로 보낸다
        SELECT TOP 1 @grp = code_grp, @code = code FROM dbo.code_master ORDER BY NEWID();
        SET @code2 = CASE WHEN RIGHT(@code, 1) = 'X' THEN LEFT(@code, LEN(@code) - 1) ELSE LEFT(CONCAT(@code, 'X'), 10) END;
        IF @code2 <> @code AND NOT EXISTS (SELECT 1 FROM dbo.code_master WHERE code_grp = @grp AND code = @code2)
        BEGIN
            UPDATE dbo.code_master SET code = @code2 WHERE code_grp = @grp AND code = @code;
            SET @n_pk += @@ROWCOUNT;
        END
    END
    ELSE IF @r < 95
    BEGIN
        -- 입력 직후 삭제(각각 다른 트랜잭션). 전체 적재가 이 행을 읽었다면 반영이 지워야 한다
        IF EXISTS (SELECT 1 FROM dbo.issuer WHERE issuer_id = @iss)
        BEGIN
            INSERT INTO dbo.rating (issuer_id, rating_cd, outlook_cd, rating_dt, eff_dtm, issue_amt, is_watch)
            VALUES (@iss, 'TMP', 'S', CAST(SYSDATETIME() AS date), CAST(SYSDATETIME() AS datetime), 0, 0);
            SET @new = SCOPE_IDENTITY();
            WAITFOR DELAY '00:00:00.200';
            DELETE FROM dbo.rating WHERE rating_id = @new;
            SET @n_insdel += 1;
        END
    END
    ELSE IF @r < 99
    BEGIN
        -- 여러 테이블을 한 트랜잭션으로: 발행사 입력 + 등급 입력 + 보고서 수정
        IF NOT EXISTS (SELECT 1 FROM dbo.issuer WHERE issuer_cd = LEFT(@tag, 6))
        BEGIN
            BEGIN TRAN;
                INSERT INTO dbo.issuer (issuer_cd, issuer_nm, issuer_nm_cp949, region_cd, is_listed)
                VALUES (LEFT(@tag, 6), CONCAT(N'묶음발행사', @i), '묶음', '11', 0);
                SET @new = SCOPE_IDENTITY();
                INSERT INTO dbo.rating (issuer_id, rating_cd, outlook_cd, rating_dt, eff_dtm, issue_amt, coupon_rate, is_watch)
                VALUES (@new, 'A', 'S', CAST(SYSDATETIME() AS date), CAST(SYSDATETIME() AS datetime), 123456.7891, 1.2345, 0);
                UPDATE dbo.research_doc SET title = CONCAT(N'묶음 수정 ', @i), view_cnt = view_cnt + 1 WHERE doc_id = @doc;
            COMMIT;
            SET @n_multi += 1;
        END
    END
    ELSE
    BEGIN
        MERGE dbo.daily_count AS t
        USING (SELECT CAST(SYSDATETIME() AS date) AS snap_dt, N'rating' AS table_nm) AS s
           ON t.snap_dt = s.snap_dt AND t.table_nm = s.table_nm
        WHEN MATCHED THEN UPDATE SET row_cnt = (SELECT COUNT_BIG(*) FROM dbo.rating)
        WHEN NOT MATCHED THEN INSERT (snap_dt, table_nm, row_cnt) VALUES (s.snap_dt, s.table_nm, (SELECT COUNT_BIG(*) FROM dbo.rating));
        SET @n_merge += 1;
    END

    WAITFOR DELAY '00:00:00.020';
END

PRINT CONCAT(N'쓰기 끝 ', CONVERT(varchar(23), SYSDATETIME(), 121));
SELECT @i AS loops, @n_rating_ins AS rating_ins, @n_rating_upd AS rating_upd, @n_rating_del AS rating_del,
       @n_issuer_ins AS issuer_ins, @n_issuer_upd AS issuer_upd, @n_doc_cnt AS doc_viewcnt_only, @n_doc_body AS doc_body,
       @n_doc_ins AS doc_ins, @n_user AS app_user_rows, @n_pk AS pk_changes, @n_insdel AS insert_then_delete,
       @n_multi AS multi_table_tx, @n_merge AS daily_merge, @n_long AS long_tx,
       CONVERT(varchar(23), SYSDATETIME(), 121) AS writes_stopped_at;
SELECT o.name AS table_name, SUM(p.rows) AS rows_now
FROM sys.partitions p JOIN sys.objects o ON o.object_id = p.object_id
WHERE o.type = 'U' AND o.is_ms_shipped = 0 AND SCHEMA_NAME(o.schema_id) = 'dbo' AND p.index_id < 2
GROUP BY o.name ORDER BY o.name;
