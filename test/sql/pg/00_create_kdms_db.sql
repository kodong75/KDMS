/*
  목적    : KDMS 대상 DB kdms 와 전용 로그인 역할 kdms_app 을 만든다(plan.md §1.2, §2.1). superuser 는 KDMS 가 쓰지 않는다.
  실행    : PG superuser 로 1회. 재실행 안전(있으면 건너뛰고 암호만 다시 맞춘다).
            암호는 명령줄에 싣지 않고 환경 변수 KDMS_TGT_PASSWORD 로 넘긴다(psql 16 \getenv).
            노트북 PowerShell 7 (KDMS 저장소 루트, docs/test-env.md §3):
              Get-Content -Raw test/sql/pg/00_create_kdms_db.sql |
                docker exec -i -e KDMS_TGT_PASSWORD mig-pg psql -U postgres -d postgres -v ON_ERROR_STOP=1
  결과    : kdms_app 이 소유한 DB kdms(UTF8, C.UTF-8). kdms_app 은 superuser·CREATEDB·CREATEROLE 아님.
            관리 스키마 kdms 는 여기서 만들지 않는다(kdms init 이 kdms_app 으로 만든다).
  주의    : 이 스크립트는 암호를 화면·로그에 출력하지 않는다. psql -a / -e(입력 반향)를 붙이지 않는다.
*/
\set ON_ERROR_STOP on
\getenv kdms_app_password KDMS_TGT_PASSWORD
\if :{?kdms_app_password}
\else
    DO $$ BEGIN RAISE EXCEPTION 'KDMS_TGT_PASSWORD 환경 변수가 없다(.env 의 KDMS_TGT_PASSWORD 를 넘긴다)'; END $$;
\endif

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kdms_app') AS need_role \gset
\if :need_role
    CREATE ROLE kdms_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    \echo 'created role kdms_app'
\else
    \echo 'role kdms_app already exists'
\endif
-- 있으면 .env 값으로 다시 맞춘다
ALTER ROLE kdms_app PASSWORD :'kdms_app_password';

SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'kdms') AS need_db \gset
\if :need_db
    CREATE DATABASE kdms OWNER kdms_app ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
    \echo 'created database kdms'
\else
    \echo 'database kdms already exists'
\endif

REVOKE ALL ON DATABASE kdms FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE kdms TO kdms_app;

\connect kdms
-- PG 15+ 기본값과 같지만 명시한다: public 스키마에 아무나 객체를 만들지 못하게
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO kdms_app;

-- 확인
SELECT r.rolname, r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolcanlogin
FROM pg_roles r WHERE r.rolname = 'kdms_app';
SELECT d.datname, pg_get_userbyid(d.datdba) AS owner, pg_encoding_to_char(d.encoding) AS encoding, d.datcollate
FROM pg_database d WHERE d.datname = 'kdms';
