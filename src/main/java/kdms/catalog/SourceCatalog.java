package kdms.catalog;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

/**
 * 원천 MS-SQL DB 하나의 카탈로그(이관 대상 판단에 필요한 것만). {@link SourceCatalogReader} 가 읽는다.
 * 행 값은 들어 있지 않다.
 *
 * @param database      DB 이름
 * @param collation     DB 기본 콜레이션
 * @param tables        사용자 테이블 전부(선택 규칙 적용 전)
 * @param sequences     SEQUENCE 객체
 * @param otherObjects  자동 변환하지 않는 객체(뷰·SP·함수·SYNONYM 등). 보고서에 목록만 낸다
 */
public record SourceCatalog(
        String database,
        String collation,
        List<Table> tables,
        List<Sequence> sequences,
        List<DbObject> otherObjects) {

    /**
     * @param rowsEstimate sys.partitions 행 수(추정)
     * @param temporal     시스템 버전 임시 테이블 여부(temporal_type <> 0)
     * @param memoryOptimized 메모리 최적화 테이블
     */
    public record Table(
            String schema,
            String name,
            long rowsEstimate,
            boolean temporal,
            boolean memoryOptimized,
            List<Column> columns,
            List<Index> indexes,
            List<ForeignKey> foreignKeys,
            List<Check> checks,
            List<Trigger> triggers) {

        public String qualifiedName() {
            return schema + "." + name;
        }

        public Index primaryKey() {
            return indexes.stream().filter(i -> i.kind() == IndexKind.PRIMARY_KEY).findFirst().orElse(null);
        }

        public Column column(String columnName) {
            return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst().orElse(null);
        }
    }

    /**
     * @param typeName    규칙을 찾을 타입 이름(소문자). 별칭 타입(UDT)이면 기반 시스템 타입, rowversion 은 "rowversion"
     * @param aliasType   별칭 타입(UDT) 이름. 아니면 null
     * @param maxLength   sys.columns.max_length (바이트, (max) = -1)
     * @param collation   문자 컬럼 콜레이션. 아니면 null
     * @param codePage    콜레이션 코드페이지(949 = CP949, 65001 = UTF-8). 문자 컬럼이 아니면 0
     * @param defaultName 기본값 제약 이름, 없으면 null
     * @param defaultDefinition 기본값 원문(예: ((0)), (getdate())), 없으면 null
     */
    public record Column(
            int ordinal,
            String name,
            String typeName,
            String aliasType,
            int maxLength,
            int precision,
            int scale,
            boolean nullable,
            String collation,
            int codePage,
            Identity identity,
            Computed computed,
            String defaultName,
            String defaultDefinition,
            boolean sparse,
            boolean rowGuid) {

        /** char·varchar·nchar·nvarchar·text·ntext·sysname */
        public boolean isText() {
            return switch (typeName) {
                case "char", "varchar", "nchar", "nvarchar", "text", "ntext", "sysname" -> true;
                default -> false;
            };
        }

        /** 유니코드(n…) 문자 타입 */
        public boolean isNational() {
            return typeName.startsWith("n") || typeName.equals("sysname");
        }

        public boolean caseInsensitive() {
            return collation != null && collation.toUpperCase(Locale.ROOT).matches(".*_CI(_.*)?$");
        }

        /** 날짜·시각 타입(센티널 날짜 규칙 대상) */
        public boolean isDateTime() {
            return switch (typeName) {
                case "date", "datetime", "datetime2", "smalldatetime", "datetimeoffset" -> true;
                default -> false;
            };
        }
    }

    /** IDENTITY. lastValue = IDENT_CURRENT, 한 번도 쓰지 않았으면 null */
    public record Identity(BigInteger seed, BigInteger increment, BigInteger lastValue) {
    }

    public record Computed(String definition, boolean persisted) {
    }

    public enum IndexKind { PRIMARY_KEY, UNIQUE_CONSTRAINT, UNIQUE_INDEX, INDEX }

    /**
     * @param type     sys.indexes.type (1 클러스터드, 2 비클러스터드, 그 밖은 columnstore·XML·공간 등)
     * @param filter   필터 인덱스 조건 원문, 없으면 null
     */
    public record Index(
            String name,
            IndexKind kind,
            int type,
            String typeDesc,
            boolean disabled,
            String filter,
            List<IndexColumn> keys,
            List<String> includes) {

        public boolean unique() {
            return kind != IndexKind.INDEX;
        }
    }

    public record IndexColumn(String column, boolean descending) {
    }

    /** onDelete/onUpdate: NO_ACTION | CASCADE | SET_NULL | SET_DEFAULT */
    public record ForeignKey(
            String name,
            List<String> columns,
            String refSchema,
            String refTable,
            List<String> refColumns,
            String onDelete,
            String onUpdate,
            boolean disabled,
            boolean notTrusted) {
    }

    public record Check(String name, String definition, boolean disabled) {
    }

    public record Trigger(String name, boolean disabled, boolean insteadOf) {
    }

    /** lastUsed = sys.sequences.last_used_value, 한 번도 안 썼으면 null */
    public record Sequence(
            String schema,
            String name,
            String typeName,
            int precision,
            BigInteger start,
            BigInteger increment,
            BigInteger min,
            BigInteger max,
            boolean cycling,
            BigInteger current,
            BigInteger lastUsed) {
    }

    /** typeDesc 예: VIEW, SQL_STORED_PROCEDURE, SYNONYM */
    public record DbObject(String schema, String name, String typeDesc) {
    }
}
