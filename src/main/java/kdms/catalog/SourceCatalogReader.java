package kdms.catalog;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 원천 MS-SQL 카탈로그 뷰(sys.*)를 읽어 {@link SourceCatalog} 를 만든다. 읽기만 한다.
 * <p>
 * 필요 권한: DB 사용자 + VIEW DEFINITION(계산 컬럼 식·기본값 원문). test/sql/mssql/20_grant_kdms_login.sql 이 준다.
 * RDS SQL Server 마스터 계정으로도 동작한다(카탈로그 뷰만 읽음).
 * <p>
 * CDC 가 만드는 객체(cdc 스키마, dbo.systranschemas)는 사용자 테이블에서 뺀다.
 */
public final class SourceCatalogReader {

    /** 사용자 테이블 조건. 모든 쿼리가 같은 조건을 쓴다 */
    static final String USER_TABLE = """
            t.is_ms_shipped = 0 AND SCHEMA_NAME(t.schema_id) <> N'cdc'
              AND NOT (SCHEMA_NAME(t.schema_id) = N'dbo' AND t.name = N'systranschemas')""";

    static final String TABLES_SQL = """
            SELECT t.object_id, SCHEMA_NAME(t.schema_id), t.name,
                   COALESCE((SELECT SUM(p.rows) FROM sys.partitions AS p
                             WHERE p.object_id = t.object_id AND p.index_id IN (0, 1)), 0),
                   t.temporal_type, t.is_memory_optimized
            FROM sys.tables AS t
            WHERE %s
            ORDER BY SCHEMA_NAME(t.schema_id), t.name""".formatted(USER_TABLE);

    // 별칭 타입(UDT)은 기반 시스템 타입(bt)으로 규칙을 찾는다. sysname·timestamp 는 시스템 타입이라 ut 이름 그대로
    static final String COLUMNS_SQL = """
            SELECT c.object_id, c.column_id, c.name,
                   ut.name, ut.is_user_defined, bt.name,
                   c.max_length, c.precision, c.scale, c.is_nullable,
                   c.collation_name, COALESCE(CAST(COLLATIONPROPERTY(c.collation_name, 'CodePage') AS int), 0),
                   c.is_identity,
                   CONVERT(nvarchar(40), ic.seed_value), CONVERT(nvarchar(40), ic.increment_value), CONVERT(nvarchar(40), ic.last_value),
                   c.is_computed, cc.definition, cc.is_persisted,
                   dc.name, dc.definition,
                   c.is_sparse, c.is_rowguidcol
            FROM sys.columns AS c
            JOIN sys.tables AS t ON t.object_id = c.object_id
            JOIN sys.types AS ut ON ut.user_type_id = c.user_type_id
            JOIN sys.types AS bt ON bt.user_type_id = c.system_type_id
            LEFT JOIN sys.identity_columns AS ic ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            LEFT JOIN sys.computed_columns AS cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
            LEFT JOIN sys.default_constraints AS dc ON dc.object_id = c.default_object_id
            WHERE %s
            ORDER BY c.object_id, c.column_id""".formatted(USER_TABLE);

    static final String INDEXES_SQL = """
            SELECT i.object_id, i.index_id, i.name, i.type, i.type_desc,
                   i.is_primary_key, i.is_unique_constraint, i.is_unique, i.is_disabled, i.has_filter, i.filter_definition,
                   ic.is_included_column, ic.is_descending_key, col.name
            FROM sys.indexes AS i
            JOIN sys.tables AS t ON t.object_id = i.object_id
            JOIN sys.index_columns AS ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns AS col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
            WHERE %s AND i.type > 0 AND i.is_hypothetical = 0
            ORDER BY i.object_id, i.index_id, ic.is_included_column, ic.key_ordinal, ic.index_column_id""".formatted(USER_TABLE);

    static final String FOREIGN_KEYS_SQL = """
            SELECT fk.parent_object_id, fk.object_id, fk.name,
                   SCHEMA_NAME(rt.schema_id), rt.name,
                   fk.delete_referential_action_desc, fk.update_referential_action_desc,
                   fk.is_disabled, fk.is_not_trusted,
                   pc.name, rc.name
            FROM sys.foreign_keys AS fk
            JOIN sys.tables AS t  ON t.object_id  = fk.parent_object_id
            JOIN sys.tables AS rt ON rt.object_id = fk.referenced_object_id
            JOIN sys.foreign_key_columns AS fkc ON fkc.constraint_object_id = fk.object_id
            JOIN sys.columns AS pc ON pc.object_id = fkc.parent_object_id     AND pc.column_id = fkc.parent_column_id
            JOIN sys.columns AS rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
            WHERE %s
            ORDER BY fk.parent_object_id, fk.name, fkc.constraint_column_id""".formatted(USER_TABLE);

    static final String CHECKS_SQL = """
            SELECT k.parent_object_id, k.name, k.definition, k.is_disabled
            FROM sys.check_constraints AS k
            JOIN sys.tables AS t ON t.object_id = k.parent_object_id
            WHERE %s
            ORDER BY k.parent_object_id, k.name""".formatted(USER_TABLE);

    static final String TRIGGERS_SQL = """
            SELECT tr.parent_id, tr.name, tr.is_disabled, tr.is_instead_of_trigger
            FROM sys.triggers AS tr
            JOIN sys.tables AS t ON t.object_id = tr.parent_id
            WHERE tr.parent_class = 1 AND %s
            ORDER BY tr.parent_id, tr.name""".formatted(USER_TABLE);

    static final String SEQUENCES_SQL = """
            SELECT SCHEMA_NAME(s.schema_id), s.name, TYPE_NAME(s.system_type_id), s.precision,
                   CONVERT(nvarchar(40), s.start_value), CONVERT(nvarchar(40), s.increment),
                   CONVERT(nvarchar(40), s.minimum_value), CONVERT(nvarchar(40), s.maximum_value),
                   s.is_cycling, CONVERT(nvarchar(40), s.current_value), CONVERT(nvarchar(40), s.last_used_value)
            FROM sys.sequences AS s
            WHERE SCHEMA_NAME(s.schema_id) <> N'cdc'
            ORDER BY SCHEMA_NAME(s.schema_id), s.name""";

    // 뷰·SP·함수·SYNONYM·CLR 객체·테이블 타입. 트리거는 테이블별로 따로 읽는다
    static final String OTHER_OBJECTS_SQL = """
            SELECT SCHEMA_NAME(o.schema_id), o.name, o.type_desc
            FROM sys.objects AS o
            WHERE o.is_ms_shipped = 0 AND SCHEMA_NAME(o.schema_id) <> N'cdc'
              AND o.type IN ('V', 'P', 'PC', 'X', 'FN', 'IF', 'TF', 'FS', 'FT', 'AF', 'SN', 'TT')
            ORDER BY o.type_desc, SCHEMA_NAME(o.schema_id), o.name""";

    private SourceCatalogReader() {
    }

    public static SourceCatalog read(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            String db;
            String collation;
            try (ResultSet rs = st.executeQuery(
                    "SELECT DB_NAME(), CAST(DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS nvarchar(128))")) {
                rs.next();
                db = rs.getString(1);
                collation = rs.getString(2);
            }

            Map<Integer, TableBuilder> tables = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery(TABLES_SQL)) {
                while (rs.next()) {
                    tables.put(rs.getInt(1), new TableBuilder(rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getInt(5) != 0, rs.getBoolean(6)));
                }
            }

            try (ResultSet rs = st.executeQuery(COLUMNS_SQL)) {
                while (rs.next()) {
                    TableBuilder t = tables.get(rs.getInt(1));
                    if (t == null) {
                        continue;
                    }
                    boolean userDefined = rs.getBoolean(5);
                    String type = typeName(userDefined ? rs.getString(6) : rs.getString(4));
                    SourceCatalog.Identity identity = rs.getBoolean(13)
                            ? new SourceCatalog.Identity(big(rs.getString(14)), big(rs.getString(15)), big(rs.getString(16)))
                            : null;
                    SourceCatalog.Computed computed = rs.getBoolean(17)
                            ? new SourceCatalog.Computed(rs.getString(18), rs.getBoolean(19))
                            : null;
                    t.columns.add(new SourceCatalog.Column(
                            rs.getInt(2), rs.getString(3), type, userDefined ? rs.getString(4) : null,
                            rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getBoolean(10),
                            rs.getString(11), rs.getInt(12), identity, computed,
                            rs.getString(20), rs.getString(21), rs.getBoolean(22), rs.getBoolean(23)));
                }
            }

            try (ResultSet rs = st.executeQuery(INDEXES_SQL)) {
                IndexBuilder cur = null;
                while (rs.next()) {
                    TableBuilder t = tables.get(rs.getInt(1));
                    if (t == null) {
                        continue;
                    }
                    int indexId = rs.getInt(2);
                    if (cur == null || cur.objectId != rs.getInt(1) || cur.indexId != indexId) {
                        SourceCatalog.IndexKind kind = rs.getBoolean(6) ? SourceCatalog.IndexKind.PRIMARY_KEY
                                : rs.getBoolean(7) ? SourceCatalog.IndexKind.UNIQUE_CONSTRAINT
                                : rs.getBoolean(8) ? SourceCatalog.IndexKind.UNIQUE_INDEX
                                : SourceCatalog.IndexKind.INDEX;
                        cur = new IndexBuilder(rs.getInt(1), indexId, rs.getString(3), kind, rs.getInt(4), rs.getString(5),
                                rs.getBoolean(9), rs.getBoolean(10) ? rs.getString(11) : null);
                        t.indexes.add(cur);
                    }
                    if (rs.getBoolean(12)) {
                        cur.includes.add(rs.getString(14));
                    } else {
                        cur.keys.add(new SourceCatalog.IndexColumn(rs.getString(14), rs.getBoolean(13)));
                    }
                }
            }

            try (ResultSet rs = st.executeQuery(FOREIGN_KEYS_SQL)) {
                FkBuilder cur = null;
                while (rs.next()) {
                    TableBuilder t = tables.get(rs.getInt(1));
                    if (t == null) {
                        continue;
                    }
                    if (cur == null || cur.objectId != rs.getInt(2)) {
                        cur = new FkBuilder(rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9));
                        t.foreignKeys.add(cur);
                    }
                    cur.columns.add(rs.getString(10));
                    cur.refColumns.add(rs.getString(11));
                }
            }

            try (ResultSet rs = st.executeQuery(CHECKS_SQL)) {
                while (rs.next()) {
                    TableBuilder t = tables.get(rs.getInt(1));
                    if (t != null) {
                        t.checks.add(new SourceCatalog.Check(rs.getString(2), rs.getString(3), rs.getBoolean(4)));
                    }
                }
            }

            try (ResultSet rs = st.executeQuery(TRIGGERS_SQL)) {
                while (rs.next()) {
                    TableBuilder t = tables.get(rs.getInt(1));
                    if (t != null) {
                        t.triggers.add(new SourceCatalog.Trigger(rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)));
                    }
                }
            }

            List<SourceCatalog.Sequence> sequences = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(SEQUENCES_SQL)) {
                while (rs.next()) {
                    sequences.add(new SourceCatalog.Sequence(rs.getString(1), rs.getString(2), typeName(rs.getString(3)),
                            rs.getInt(4), big(rs.getString(5)), big(rs.getString(6)), big(rs.getString(7)),
                            big(rs.getString(8)), rs.getBoolean(9), big(rs.getString(10)), big(rs.getString(11))));
                }
            }

            List<SourceCatalog.DbObject> others = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(OTHER_OBJECTS_SQL)) {
                while (rs.next()) {
                    others.add(new SourceCatalog.DbObject(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }

            return new SourceCatalog(db, collation, tables.values().stream().map(TableBuilder::build).toList(),
                    List.copyOf(sequences), List.copyOf(others));
        }
    }

    /** sys.types 이름 → 규칙 키. timestamp 는 rowversion 의 옛 이름 */
    static String typeName(String sysTypeName) {
        String t = sysTypeName.toLowerCase(Locale.ROOT);
        return t.equals("timestamp") ? "rowversion" : t;
    }

    /** sql_variant 를 nvarchar 로 바꾼 정수 문자열(예: "1", "-9223372036854775808"). decimal 이면 소수부 0 을 뗀다 */
    static BigInteger big(String s) {
        if (s == null) {
            return null;
        }
        String v = s.strip();
        int dot = v.indexOf('.');
        if (dot >= 0) {
            v = v.substring(0, dot);
        }
        return new BigInteger(v);
    }

    private static final class TableBuilder {
        final String schema;
        final String name;
        final long rows;
        final boolean temporal;
        final boolean memoryOptimized;
        final List<SourceCatalog.Column> columns = new ArrayList<>();
        final List<IndexBuilder> indexes = new ArrayList<>();
        final List<FkBuilder> foreignKeys = new ArrayList<>();
        final List<SourceCatalog.Check> checks = new ArrayList<>();
        final List<SourceCatalog.Trigger> triggers = new ArrayList<>();

        TableBuilder(String schema, String name, long rows, boolean temporal, boolean memoryOptimized) {
            this.schema = schema;
            this.name = name;
            this.rows = rows;
            this.temporal = temporal;
            this.memoryOptimized = memoryOptimized;
        }

        SourceCatalog.Table build() {
            return new SourceCatalog.Table(schema, name, rows, temporal, memoryOptimized, List.copyOf(columns),
                    indexes.stream().map(IndexBuilder::build).toList(),
                    foreignKeys.stream().map(FkBuilder::build).toList(),
                    List.copyOf(checks), List.copyOf(triggers));
        }
    }

    private static final class IndexBuilder {
        final int objectId;
        final int indexId;
        final String name;
        final SourceCatalog.IndexKind kind;
        final int type;
        final String typeDesc;
        final boolean disabled;
        final String filter;
        final List<SourceCatalog.IndexColumn> keys = new ArrayList<>();
        final List<String> includes = new ArrayList<>();

        IndexBuilder(int objectId, int indexId, String name, SourceCatalog.IndexKind kind, int type, String typeDesc,
                     boolean disabled, String filter) {
            this.objectId = objectId;
            this.indexId = indexId;
            this.name = name;
            this.kind = kind;
            this.type = type;
            this.typeDesc = typeDesc;
            this.disabled = disabled;
            this.filter = filter;
        }

        SourceCatalog.Index build() {
            return new SourceCatalog.Index(name, kind, type, typeDesc, disabled, filter, List.copyOf(keys), List.copyOf(includes));
        }
    }

    private static final class FkBuilder {
        final int objectId;
        final String name;
        final String refSchema;
        final String refTable;
        final String onDelete;
        final String onUpdate;
        final boolean disabled;
        final boolean notTrusted;
        final List<String> columns = new ArrayList<>();
        final List<String> refColumns = new ArrayList<>();

        FkBuilder(int objectId, String name, String refSchema, String refTable, String onDelete, String onUpdate,
                  boolean disabled, boolean notTrusted) {
            this.objectId = objectId;
            this.name = name;
            this.refSchema = refSchema;
            this.refTable = refTable;
            this.onDelete = onDelete;
            this.onUpdate = onUpdate;
            this.disabled = disabled;
            this.notTrusted = notTrusted;
        }

        SourceCatalog.ForeignKey build() {
            return new SourceCatalog.ForeignKey(name, List.copyOf(columns), refSchema, refTable, List.copyOf(refColumns),
                    onDelete, onUpdate, disabled, notTrusted);
        }
    }
}
