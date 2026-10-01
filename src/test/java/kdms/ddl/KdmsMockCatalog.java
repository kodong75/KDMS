package kdms.ddl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import kdms.catalog.SourceCatalog;
import kdms.catalog.SourceCatalog.Column;
import kdms.catalog.SourceCatalog.Computed;
import kdms.catalog.SourceCatalog.ForeignKey;
import kdms.catalog.SourceCatalog.Identity;
import kdms.catalog.SourceCatalog.Index;
import kdms.catalog.SourceCatalog.IndexColumn;
import kdms.catalog.SourceCatalog.IndexKind;
import kdms.catalog.SourceCatalog.Table;

/**
 * KDMS_MOCK(= KIS MIG_MOCK 사본) 카탈로그를 KIS sql/10_mssql/11_schema_pitfalls.sql 대로 손으로 옮긴 것.
 * sys.columns 값(max_length 는 바이트, nvarchar 는 문자 수 × 2) 그대로. 실제 원천과 같은지는 SourceCatalogIT 가 Mac 에서 확인한다.
 * IDENTITY·SEQUENCE 현재값은 KIS sql/20_pg/gen/mock.sql(2026-09-30 생성)의 setval 값.
 * 노트북 실측(2026-10-01, 복원·CDC 결과)도 이 7개 테이블이다. KIS sql/10_mssql/19_file_name_data.sql 의 dbo.file_attach 는 노트북 MIG_MOCK 에 없다.
 */
final class KdmsMockCatalog {

    static final String CI = "Korean_Wansung_CI_AS";

    private KdmsMockCatalog() {
    }

    static SourceCatalog catalog() {
        List<Table> t = new ArrayList<>();
        t.add(table("app_user", 1002,
                List.of(
                        identity(col(1, "user_id", "int", 4, 10, 0, false), 1002),
                        text(2, "login_id", "varchar", 30, false),
                        text(3, "user_nm", "nvarchar", 100, false),
                        text(4, "email", "varchar", 100, true),
                        withDefault(col(5, "member_level", "tinyint", 1, 3, 0, false), "df_user_level", "((0))"),
                        withDefault(col(6, "joined_dtm", "datetime", 8, 23, 3, false), "df_user_joined", "(getdate())")),
                List.of(pk("pk_app_user", "user_id"),
                        new Index("uq_app_user_login", IndexKind.UNIQUE_CONSTRAINT, 2, "NONCLUSTERED", false, null,
                                List.of(new IndexColumn("login_id", false)), List.of())),
                List.of(), List.of()));
        t.add(table("code_master", 0,
                List.of(
                        text(1, "code_grp", "varchar", 20, false),
                        text(2, "code", "varchar", 10, false),
                        text(3, "code_nm", "nvarchar", 200, false),
                        text(4, "code_nm_short", "varchar", 20, true),
                        withDefault(col(5, "sort_no", "smallint", 2, 5, 0, false), "df_code_sort", "((0))"),
                        withDefault(text(6, "use_yn", "char", 1, false), "df_code_use", "('Y')")),
                List.of(pk("pk_code_master", "code_grp", "code")),
                List.of(), List.of()));
        t.add(table("daily_count", 0,
                List.of(
                        col(1, "snap_dt", "date", 3, 10, 0, false),
                        text(2, "table_nm", "sysname", 256, false),
                        col(3, "row_cnt", "bigint", 8, 19, 0, false)),
                List.of(pk("pk_daily_count", "snap_dt", "table_nm")),
                List.of(), List.of()));
        t.add(table("issuer", 2010,
                List.of(
                        identity(col(1, "issuer_id", "int", 4, 10, 0, false), 2010),
                        text(2, "issuer_cd", "char", 6, false),
                        text(3, "issuer_nm", "nvarchar", 200, false),
                        text(4, "issuer_nm_cp949", "varchar", 40, true),
                        text(5, "issuer_nm_en", "varchar", 200, true),
                        text(6, "region_cd", "char", 2, true),
                        text(7, "industry_cd", "varchar", 2, true),
                        withDefault(col(8, "is_listed", "bit", 1, 1, 0, false), "df_issuer_listed", "((0))"),
                        withDefault(col(9, "issuer_guid", "uniqueidentifier", 16, 0, 0, false), "df_issuer_guid", "(newsequentialid())"),
                        col(10, "row_ver", "rowversion", 8, 0, 0, false),
                        withDefault(col(11, "reg_dtm", "datetime", 8, 23, 3, false), "df_issuer_reg", "(getdate())"),
                        col(12, "upd_dtm", "datetime2", 8, 27, 7, true)),
                List.of(pk("pk_issuer", "issuer_id"),
                        new Index("uq_issuer_cd", IndexKind.UNIQUE_CONSTRAINT, 2, "NONCLUSTERED", false, null,
                                List.of(new IndexColumn("issuer_cd", false)), List.of()),
                        index("ix_issuer_nm", "issuer_nm")),
                List.of(), List.of()));
        t.add(table("rating", 20004,
                List.of(
                        identity(col(1, "rating_id", "bigint", 8, 19, 0, false), 20004),
                        col(2, "issuer_id", "int", 4, 10, 0, false),
                        text(3, "rating_cd", "varchar", 10, false),
                        text(4, "outlook_cd", "char", 1, true),
                        col(5, "rating_dt", "date", 3, 10, 0, false),
                        col(6, "eff_dtm", "datetime", 8, 23, 3, false),
                        col(7, "issue_amt", "money", 8, 19, 4, true),
                        col(8, "coupon_rate", "decimal", 5, 9, 4, true),
                        withDefault(col(9, "is_watch", "bit", 1, 1, 0, false), "df_rating_watch", "((0))"),
                        computed(col(10, "rating_rank", "int", 4, 10, 0, true),
                                "(case rtrim([rating_cd]) when 'AAA' then (1) when 'AA+' then (2) when 'AA' then (3) when 'AA-' then (4) "
                                        + "when 'A+' then (5) when 'A' then (6) when 'A-' then (7) when 'BBB+' then (8) when 'BBB' then (9) "
                                        + "when 'BBB-' then (10) else (99) end)", false)),
                List.of(pk("pk_rating", "rating_id"), index("ix_rating_issuer_dt", "issuer_id", "rating_dt"), index("ix_rating_dt", "rating_dt")),
                List.of(fk("fk_rating_issuer", "issuer_id", "issuer", "issuer_id")),
                List.of(new SourceCatalog.Trigger("trg_rating_audit", false, false))));
        t.add(table("rating_hist", 20004,
                List.of(
                        identity(col(1, "hist_id", "bigint", 8, 19, 0, false), 20004),
                        col(2, "rating_id", "bigint", 8, 19, 0, false),
                        text(3, "action_cd", "char", 1, false),
                        text(4, "old_rating_cd", "varchar", 10, true),
                        text(5, "new_rating_cd", "varchar", 10, true),
                        withDefault(text(6, "changed_by", "sysname", 256, false), "df_hist_by", "(suser_sname())"),
                        withDefault(col(7, "changed_dtm", "datetime2", 7, 23, 3, false), "df_hist_dtm", "(sysdatetime())")),
                List.of(pk("pk_rating_hist", "hist_id")),
                List.of(), List.of()));
        t.add(table("research_doc", 10001,
                List.of(
                        identity(col(1, "doc_id", "int", 4, 10, 0, false), 10001),
                        withDefault(col(2, "doc_no", "bigint", 8, 19, 0, false), "df_doc_no", "(NEXT VALUE FOR [dbo].[seq_doc_no])"),
                        text(3, "doc_type_cd", "varchar", 2, false),
                        col(4, "issuer_id", "int", 4, 10, 0, true),
                        text(5, "title", "nvarchar", 800, false),
                        text(6, "file_nm", "varchar", 100, true),
                        text(7, "file_path", "nvarchar", 800, true),
                        computed(text(8, "file_ext", "varchar", 200, true),
                                "(case when charindex('.',[file_nm])>(0) then lower(right([file_nm],charindex('.',reverse([file_nm]))-(1)))  end)", true),
                        col(9, "pub_dtm", "datetime", 8, 23, 3, false),
                        text(10, "body", "nvarchar", -1, true),
                        withDefault(col(11, "view_cnt", "int", 4, 10, 0, false), "df_doc_view", "((0))")),
                List.of(pk("pk_research_doc", "doc_id"), index("ix_doc_pub", "pub_dtm")),
                List.of(fk("fk_doc_issuer", "issuer_id", "issuer", "issuer_id")),
                List.of()));

        List<SourceCatalog.Sequence> seq = List.of(new SourceCatalog.Sequence("dbo", "seq_doc_no", "bigint", 19,
                BigInteger.valueOf(202600001), BigInteger.ONE, BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE),
                false, BigInteger.valueOf(202605006), BigInteger.valueOf(202605006)));
        List<SourceCatalog.DbObject> others = List.of(
                new SourceCatalog.DbObject("dbo", "usp_daily_count", "SQL_STORED_PROCEDURE"),
                new SourceCatalog.DbObject("dbo", "usp_search_docs", "SQL_STORED_PROCEDURE"),
                new SourceCatalog.DbObject("dbo", "usp_upsert_ratings", "SQL_STORED_PROCEDURE"),
                new SourceCatalog.DbObject("dbo", "syn_region", "SYNONYM"),
                new SourceCatalog.DbObject("dbo", "TT_rating_tvp_0000", "TYPE_TABLE"),
                new SourceCatalog.DbObject("dbo", "vw_issuer_region", "VIEW"));
        return new SourceCatalog("KDMS_MOCK", CI, List.copyOf(t), seq, others);
    }

    static Table table(String name, long rows, List<Column> cols, List<Index> idx, List<ForeignKey> fks,
                       List<SourceCatalog.Trigger> triggers) {
        return new Table("dbo", name, rows, false, false, cols, idx, fks, List.of(), triggers);
    }

    static Column col(int ord, String name, String type, int maxLen, int prec, int scale, boolean nullable) {
        return new Column(ord, name, type, null, maxLen, prec, scale, nullable, null, 0, null, null, null, null, false, false);
    }

    /** 문자 컬럼(DB 기본 콜레이션 CI, CP949) */
    static Column text(int ord, String name, String type, int maxLen, boolean nullable) {
        return new Column(ord, name, type, null, maxLen, 0, 0, nullable, CI, 949, null, null, null, null, false, false);
    }

    static Column identity(Column c, long last) {
        return new Column(c.ordinal(), c.name(), c.typeName(), null, c.maxLength(), c.precision(), c.scale(), c.nullable(),
                c.collation(), c.codePage(), new Identity(BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(last)), null, null, null,
                false, false);
    }

    static Column computed(Column c, String def, boolean persisted) {
        return new Column(c.ordinal(), c.name(), c.typeName(), null, c.maxLength(), c.precision(), c.scale(), c.nullable(),
                c.collation(), c.codePage(), null, new Computed(def, persisted), null, null, false, false);
    }

    static Column withDefault(Column c, String name, String def) {
        return new Column(c.ordinal(), c.name(), c.typeName(), null, c.maxLength(), c.precision(), c.scale(), c.nullable(),
                c.collation(), c.codePage(), c.identity(), c.computed(), name, def, false, false);
    }

    static Index pk(String name, String... cols) {
        return new Index(name, IndexKind.PRIMARY_KEY, 1, "CLUSTERED", false, null,
                java.util.Arrays.stream(cols).map(c -> new IndexColumn(c, false)).toList(), List.of());
    }

    static Index index(String name, String... cols) {
        return new Index(name, IndexKind.INDEX, 2, "NONCLUSTERED", false, null,
                java.util.Arrays.stream(cols).map(c -> new IndexColumn(c, false)).toList(), List.of());
    }

    static ForeignKey fk(String name, String col, String refTable, String refCol) {
        return new ForeignKey(name, List.of(col), "dbo", refTable, List.of(refCol), "NO_ACTION", "NO_ACTION", false, false);
    }
}
