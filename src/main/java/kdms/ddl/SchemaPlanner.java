package kdms.ddl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import kdms.catalog.SourceCatalog;
import kdms.catalog.SourceDataScanner;
import kdms.config.KdmsConfig;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.ForeignKeyPlan;
import kdms.ddl.SchemaPlan.IndexPlan;
import kdms.ddl.SchemaPlan.Issue;
import kdms.ddl.SchemaPlan.Issue.Level;
import kdms.ddl.SchemaPlan.KeyPlan;
import kdms.ddl.SchemaPlan.SequencePlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.ddl.SchemaPlan.ValueRule;
import kdms.rules.Rules;

/**
 * 원천 카탈로그에 변환 규칙을 적용해 {@link SchemaPlan} 을 만든다. DB 에 접속하지 않는다(단위 시험 가능).
 * <p>
 * 규칙 근거: plan.md §5, KIS:docs/normalization.md §1, KIS:docs/issues.md A·B 항목.
 */
public final class SchemaPlanner {

    /** PG btree 항목 최대 크기(8kB 페이지의 1/3 근처). 키 + INCLUDE 의 UTF-8 최대 바이트가 넘으면 경고(T-L11) */
    static final int BTREE_MAX_BYTES = 2700;

    private static final Set<String> IDENTITY_TYPES = Set.of("smallint", "integer", "bigint");

    private final Rules rules;
    private final Map<String, SourceDataScanner.TableScan> scan;
    private final Names.Namespace namespace = new Names.Namespace();
    private final List<Issue> globalIssues = new ArrayList<>();
    /** 원천 "schema.seq"(소문자) → 대상 "schema"."seq" */
    private final Map<String, String> sequenceTargets = new HashMap<>();
    /** 원천 "schema.table"(소문자) → 대상 이름 */
    private final Map<String, String[]> tableTargets = new HashMap<>();
    /** FK 가 가리키는 키: "schema.table|col,col"(원천, 소문자) */
    private final Set<String> referencedKeys = new HashSet<>();
    private final Set<String> collationSchemas = new LinkedHashSet<>();

    private SchemaPlanner(Rules rules, Map<String, SourceDataScanner.TableScan> scan) {
        this.rules = rules;
        this.scan = scan == null ? Map.of() : scan;
    }

    /**
     * @param scan {@code kdms plan --scan} 결과("schema.table" 소문자 → 결과). 없으면 null
     */
    public static SchemaPlan plan(SourceCatalog catalog, KdmsConfig.TableSelection selection, Rules rules,
                                  Map<String, SourceDataScanner.TableScan> scan) {
        return new SchemaPlanner(rules, scan).run(catalog, selection);
    }

    private SchemaPlan run(SourceCatalog catalog, KdmsConfig.TableSelection selection) {
        // 1. 대상 테이블 고르기
        List<SourceCatalog.Table> selected = new ArrayList<>();
        List<SchemaPlan.Excluded> excluded = new ArrayList<>();
        for (SourceCatalog.Table t : catalog.tables()) {
            Rules.TableRule tr = rules.table(t.schema(), t.name());
            if (!matchesAny(t.qualifiedName(), selection.include())) {
                continue; // include 밖은 보고서에도 넣지 않는다
            }
            if (matchesAny(t.qualifiedName(), selection.exclude())) {
                excluded.add(new SchemaPlan.Excluded(t.qualifiedName(), "설정 tables.exclude"));
            } else if (tr != null && tr.exclude()) {
                excluded.add(new SchemaPlan.Excluded(t.qualifiedName(), "규칙 tables." + Rules.tableKey(t.schema(), t.name()) + ".exclude"));
            } else {
                selected.add(t);
            }
        }
        checkRuleKeys(catalog);

        // 2. 이름 정하기: 테이블·시퀀스가 먼저 자리를 잡는다
        for (SourceCatalog.Table t : selected) {
            String schema = targetSchema(t.schema());
            String name = rules.identifierMap().getOrDefault(Rules.tableKey(t.schema(), t.name()),
                    Names.applyCase(t.name(), rules.identifierCase()));
            tableTargets.put(Rules.tableKey(t.schema(), t.name()), new String[]{schema, name});
            if (!namespace.reserve(schema, name)) {
                globalIssues.add(new Issue(Level.ERROR, t.qualifiedName(), "대상 이름 " + schema + "." + name
                        + " 이 다른 테이블과 겹친다(identifiers.map 으로 바꾼다)"));
            }
            for (SourceCatalog.ForeignKey fk : t.foreignKeys()) {
                referencedKeys.add(keyOf(fk.refSchema(), fk.refTable(), fk.refColumns()));
            }
        }
        Set<String> schemas = selected.stream().map(t -> t.schema().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<SequencePlan> sequences = new ArrayList<>();
        for (SourceCatalog.Sequence s : catalog.sequences()) {
            if (schemas.contains(s.schema().toLowerCase(Locale.ROOT))) {
                sequences.add(sequence(s));
            }
        }

        // 3. 테이블
        List<TablePlan> tables = new ArrayList<>();
        for (SourceCatalog.Table t : selected) {
            tables.add(table(t));
        }

        // 4. 전체 주의(KIS B 항목)
        behaviorNotes(tables);
        if (!catalog.otherObjects().isEmpty()) {
            globalIssues.add(new Issue(Level.WARN, "(DB)", "자동 변환하지 않는 객체 " + catalog.otherObjects().size()
                    + "개(뷰·SP·함수·SYNONYM 등). KIS:docs/appcompat.md 방식으로 수작업 전환(plan.md §3.2)"));
        }
        return new SchemaPlan(catalog.database(), catalog.collation(), List.copyOf(tables), List.copyOf(excluded),
                List.copyOf(sequences), List.copyOf(collationSchemas), List.copyOf(globalIssues), catalog.otherObjects());
    }

    // ---------------------------------------------------------------- 테이블

    private TablePlan table(SourceCatalog.Table t) {
        String[] tgt = tableTargets.get(Rules.tableKey(t.schema(), t.name()));
        String schema = tgt[0];
        String name = tgt[1];
        List<Issue> issues = new ArrayList<>();
        String where = t.qualifiedName();
        checkIdentifier(name, where, "테이블", issues);

        if (t.temporal()) {
            issues.add(new Issue(Level.WARN, where, "시스템 버전 임시 테이블(temporal). 기록 테이블·기간 컬럼은 자동으로 옮기지 않는다"));
        }
        if (t.memoryOptimized()) {
            issues.add(new Issue(Level.WARN, where, "메모리 최적화 테이블. 대상은 일반 테이블"));
        }
        for (SourceCatalog.Trigger tr : t.triggers()) {
            issues.add(new Issue(Level.WARN, where, "트리거 " + tr.name() + (tr.disabled() ? "(원천에서 꺼짐)" : "")
                    + ": 자동 변환 안 함(B11). 반영 중에는 대상 트리거를 두지 않고 전환 뒤 PL/pgSQL 로 배포"));
        }
        for (SourceCatalog.Check ck : t.checks()) {
            issues.add(new Issue(Level.WARN, where, "CHECK 제약 " + ck.name() + " 자동 변환 안 함. 원문: " + ck.definition()));
        }

        // 컬럼
        Map<String, String> colNames = new LinkedHashMap<>(); // 원천(소문자) → 대상
        Set<String> seenTarget = new HashSet<>();
        List<ColumnPlan> columns = new ArrayList<>();
        Map<String, SourceDataScanner.ColumnScan> colScan = scan.containsKey(Rules.tableKey(t.schema(), t.name()))
                ? scan.get(Rules.tableKey(t.schema(), t.name())).columns() : Map.of();
        for (SourceCatalog.Column c : t.columns()) {
            ColumnPlan cp = column(t, schema, c, colScan.get(c.name()), issues);
            if (!seenTarget.add(cp.tgtName())) {
                issues.add(new Issue(Level.ERROR, where + "." + c.name(), "대상 컬럼 이름 " + cp.tgtName()
                        + " 이 겹친다(tables." + Rules.tableKey(t.schema(), t.name()) + ".columns." + c.name() + ".name 으로 바꾼다)"));
            }
            colNames.put(c.name().toLowerCase(Locale.ROOT), cp.tgtName());
            columns.add(cp);
        }

        // PK
        SourceCatalog.Index pk = t.primaryKey();
        KeyPlan pkPlan = null;
        List<IndexPlan> postLoad = new ArrayList<>();
        if (pk == null) {
            issues.add(new Issue(Level.WARN, where, "PK 없음: CDC 반영을 멱등으로 못 한다 → 전환 때 전체 재적재(plan.md §4.6)"));
        } else {
            String pkName = namespace.claim(schema, Names.applyCase(pk.name(), rules.identifierCase()), name);
            List<String> cols = pk.keys().stream().map(k -> colNames.get(k.column().toLowerCase(Locale.ROOT))).toList();
            pkPlan = new KeyPlan(pkName, cols);
            if (pk.disabled()) {
                issues.add(new Issue(Level.WARN, where, "원천 PK " + pk.name() + " 가 꺼져 있다"));
            }
            if (ciLowerIndex(t, pk)) {
                String ixName = namespace.claim(schema, pkName + "_ci", name);
                postLoad.add(new IndexPlan(pk.name(), ixName, uniqueLowerIndex(ixName, t, schema, name, pk, colNames),
                        "PK 대소문자 무시 중복 방지(B14, collation.ci_unique: lower_index)"));
            }
            keyBytesWarning(t, pk, issues);
        }

        // UNIQUE·인덱스
        for (SourceCatalog.Index ix : t.indexes()) {
            if (ix.kind() == SourceCatalog.IndexKind.PRIMARY_KEY) {
                continue;
            }
            IndexPlan ip = index(t, schema, name, ix, colNames, issues);
            if (ip != null) {
                postLoad.add(ip);
            }
        }

        // FK
        List<ForeignKeyPlan> fks = new ArrayList<>();
        for (SourceCatalog.ForeignKey fk : t.foreignKeys()) {
            ForeignKeyPlan f = foreignKey(t, schema, name, fk, colNames, issues);
            if (f != null) {
                fks.add(f);
            }
        }

        return new TablePlan(t.schema(), t.name(), schema, name, t.rowsEstimate(), List.copyOf(columns), pkPlan,
                List.copyOf(postLoad), List.copyOf(fks), List.copyOf(issues));
    }

    // ---------------------------------------------------------------- 컬럼

    private ColumnPlan column(SourceCatalog.Table t, String tgtSchema, SourceCatalog.Column c,
                              SourceDataScanner.ColumnScan cs, List<Issue> issues) {
        String where = t.qualifiedName() + "." + c.name();
        Rules.ColumnRule cr = rules.column(t.schema(), t.name(), c.name());
        String tgtName = cr != null && cr.name() != null ? cr.name() : Names.applyCase(c.name(), rules.identifierCase());
        checkIdentifier(tgtName, where, "컬럼", issues);
        List<String> notes = new ArrayList<>();

        // 타입
        Rules.TypeRule tr = rules.types().get(c.typeName());
        String tgtType;
        String round = null;
        String check = null;
        if (cr != null && cr.type() != null) {
            tgtType = cr.type();
            notes.add("타입 지정(규칙 tables)");
        } else if (tr == null) {
            issues.add(new Issue(Level.ERROR, where, "타입 규칙 없음: " + c.typeName()
                    + " (types 에 추가하거나 tables." + Rules.tableKey(t.schema(), t.name()) + ".columns." + c.name() + ".type 지정)"));
            tgtType = "text";
        } else {
            int p = isTemporal(c.typeName()) ? c.scale() : c.precision();
            if (tr.maxPrecision() != null && p > tr.maxPrecision()) {
                notes.add("소수 " + p + "자리 → " + tr.maxPrecision() + "자리(" + tr.round() + ", A06)");
                p = tr.maxPrecision();
                round = tr.round();
            }
            String template = c.maxLength() == -1 && tr.max() != null ? tr.max() : tr.to();
            if (template.contains("{n}") && c.maxLength() == -1) {
                issues.add(new Issue(Level.ERROR, where, "(max) 길이 규칙(types." + c.typeName() + ".max)이 없다"));
                tgtType = "text";
            } else {
                int n = c.isNational() ? c.maxLength() / 2 : c.maxLength();
                tgtType = template.replace("{n}", String.valueOf(n)).replace("{p}", String.valueOf(p))
                        .replace("{s}", String.valueOf(c.scale()));
            }
            if (tr.check() != null) {
                check = Names.quote(tgtName) + " " + tr.check();
            }
            if (tr.warn() != null) {
                issues.add(new Issue(Level.WARN, where, c.typeName() + " → " + tgtType + ": " + tr.warn()));
            }
        }
        if (c.aliasType() != null) {
            issues.add(new Issue(Level.WARN, where, "별칭 타입 " + c.aliasType() + " 을 기반 타입 " + c.typeName() + " 규칙으로 옮겼다"));
        }
        if (c.sparse()) {
            notes.add("SPARSE 속성 버림");
        }

        // 콜레이션
        String collate = null;
        if (c.isText()) {
            collate = collationFor(c, t, tgtSchema);
            if (c.codePage() != 0 && c.codePage() != 65001 && !c.isNational()) {
                notes.add("CP" + c.codePage() + " → UTF-8(바이트 증가)");
            }
        }

        // IDENTITY
        String identity = null;
        if (c.identity() != null) {
            if (!IDENTITY_TYPES.contains(tgtType)) {
                issues.add(new Issue(Level.ERROR, where, "IDENTITY 컬럼의 대상 타입이 정수가 아니다(" + tgtType
                        + "). tables 에서 type: bigint 등으로 지정"));
            }
            identity = identityClause(c.identity());
            notes.add("IDENT_CURRENT " + (c.identity().lastValue() == null ? "없음" : c.identity().lastValue()) + " → 전환 때 setval(A09)");
        }

        // 계산 컬럼
        String generated = null;
        if (c.computed() != null) {
            String action = cr != null && cr.computed() != null ? cr.computed() : rules.computedColumns();
            if (cr != null && cr.generated() != null) {
                generated = cr.generated();
                notes.add("계산 컬럼 → GENERATED STORED(B12), 적재·반영에서 뺀다");
            } else if ("generated_stored".equals(action)) {
                issues.add(new Issue(Level.ERROR, where, "계산 컬럼 식을 PG 로 옮겨야 한다: tables." + Rules.tableKey(t.schema(), t.name())
                        + ".columns." + c.name() + ".generated 에 PG 식을 적거나 computed: value_with_warning. 원문: " + c.computed().definition()));
            } else {
                issues.add(new Issue(Level.WARN, where, "계산 컬럼을 값만 옮긴다(식 없음, CDC 는 계산 컬럼을 캡처하지 않아 반영 때 원천 재계산 값이 빠진다). 원문: "
                        + c.computed().definition()));
            }
        }

        // 기본값
        String defaultExpr = null;
        if (cr != null && cr.defaultExpr() != null) {
            defaultExpr = "none".equalsIgnoreCase(cr.defaultExpr()) ? null : cr.defaultExpr();
            notes.add("기본값 지정(규칙 tables)");
        } else if (c.defaultDefinition() != null && identity == null && generated == null) {
            DefaultTranslator.Result r = DefaultTranslator.translate(c.defaultDefinition(), tgtType, rules, t.schema(),
                    (s, n) -> sequenceTargets.get((s + "." + n).toLowerCase(Locale.ROOT)));
            if (r.error() != null) {
                issues.add(new Issue(Level.ERROR, where, "기본값 " + r.error() + " (tables." + Rules.tableKey(t.schema(), t.name())
                        + ".columns." + c.name() + ".default 에 PG 식 또는 none)"));
            } else {
                defaultExpr = r.expr();
                if (r.warn() != null) {
                    issues.add(new Issue(Level.WARN, where, "기본값 " + c.defaultDefinition() + " → " + r.expr() + ": " + r.warn()));
                }
                if (defaultExpr != null && defaultExpr.startsWith("nextval(")) {
                    notes.add("NEXT VALUE FOR → nextval(B13)");
                }
            }
        }

        // 값 규칙
        ValueRule value = valueRule(c, cr, round);
        if (cs != null && cs.nulRows() > 0) {
            String rule = value.nulChar();
            issues.add(new Issue("fail".equals(rule) ? Level.ERROR : Level.WARN, where, "NUL 문자가 든 행 " + cs.nulRows()
                    + "건(A02). text.nul_char: " + rule + ("fail".equals(rule) ? " → strip/replace 로 정하거나 원천을 고친다" : "")));
        }
        if (cs != null && cs.sentinelRows() > 0) {
            issues.add(new Issue(Level.NOTE, where, "센티널 날짜 행 " + cs.sentinelRows() + "건(A08). sentinel_dates.action: " + value.sentinel()));
        }

        return new ColumnPlan(c, c.name(), sourceType(c), tgtName, tgtType, collate, c.nullable(), identity, defaultExpr,
                generated, check, value, List.copyOf(notes));
    }

    private ValueRule valueRule(SourceCatalog.Column c, Rules.ColumnRule cr, String round) {
        if (c.isText()) {
            Rules.TextRule tx = rules.text();
            return new ValueRule(
                    cr != null && cr.trailingSpace() != null ? cr.trailingSpace() : tx.trailingSpace(),
                    cr != null && cr.caseRule() != null ? cr.caseRule() : tx.caseRule(),
                    cr != null && cr.nulChar() != null ? cr.nulChar() : tx.nulChar(),
                    null, null);
        }
        if (c.isDateTime()) {
            return new ValueRule(null, null, null, rules.sentinelAction(), round);
        }
        return new ValueRule(null, null, null, null, round);
    }

    private String collationFor(SourceCatalog.Column c, SourceCatalog.Table t, String tgtSchema) {
        boolean ciValue = c.caseInsensitive() && "keep".equals(effectiveCase(t, c));
        boolean useCi = "ci".equals(rules.collationTarget()) && ciValue
                || "ci_collation".equals(rules.ciUnique()) && ciValue && inUniqueKey(t, c);
        if (useCi) {
            collationSchemas.add(tgtSchema);
            return Names.quote(tgtSchema) + "." + Names.quote(SchemaPlan.CI_COLLATION);
        }
        return "ci".equals(rules.collationTarget()) ? Names.quote("C") : Names.quote(rules.collationTarget());
    }

    private String effectiveCase(SourceCatalog.Table t, SourceCatalog.Column c) {
        Rules.ColumnRule cr = rules.column(t.schema(), t.name(), c.name());
        return cr != null && cr.caseRule() != null ? cr.caseRule() : rules.text().caseRule();
    }

    private static boolean inUniqueKey(SourceCatalog.Table t, SourceCatalog.Column c) {
        return t.indexes().stream().filter(SourceCatalog.Index::unique)
                .anyMatch(i -> i.keys().stream().anyMatch(k -> k.column().equalsIgnoreCase(c.name())));
    }

    private String identityClause(SourceCatalog.Identity id) {
        String base = rules.identityTo().toUpperCase(Locale.ROOT);
        boolean defaults = BigInteger.ONE.equals(id.seed()) && BigInteger.ONE.equals(id.increment());
        if (defaults) {
            return base;
        }
        String opts = "START WITH " + id.seed() + " INCREMENT BY " + id.increment();
        if (id.increment().signum() < 0) {
            opts += " MAXVALUE " + id.seed();
        }
        return base + " (" + opts + ")";
    }

    static String sourceType(SourceCatalog.Column c) {
        String t = c.aliasType() != null ? c.aliasType() + "=" + c.typeName() : c.typeName();
        String len = c.maxLength() == -1 ? "max" : String.valueOf(c.isNational() ? c.maxLength() / 2 : c.maxLength());
        String s = switch (c.typeName()) {
            case "char", "varchar", "binary", "varbinary", "nchar", "nvarchar" -> t + "(" + len + ")";
            case "decimal", "numeric" -> t + "(" + c.precision() + "," + c.scale() + ")";
            case "datetime2", "time", "datetimeoffset" -> t + "(" + c.scale() + ")";
            default -> t;
        };
        if (c.identity() != null) {
            s += " IDENTITY(" + c.identity().seed() + "," + c.identity().increment() + ")";
        }
        if (c.computed() != null) {
            s += c.computed().persisted() ? " 계산(PERSISTED)" : " 계산";
        }
        if (c.isText() && c.caseInsensitive()) {
            s += " CI";
        }
        return s;
    }

    private static boolean isTemporal(String type) {
        return type.equals("time") || type.equals("datetime2") || type.equals("datetimeoffset");
    }

    // ---------------------------------------------------------------- 인덱스

    private IndexPlan index(SourceCatalog.Table t, String schema, String table, SourceCatalog.Index ix,
                            Map<String, String> colNames, List<Issue> issues) {
        String where = t.qualifiedName() + " 인덱스 " + ix.name();
        if (ix.type() != 1 && ix.type() != 2) {
            issues.add(new Issue(Level.WARN, where, ix.typeDesc() + " 인덱스는 옮기지 않는다"));
            return null;
        }
        if (ix.disabled()) {
            issues.add(new Issue(Level.WARN, where, "원천에서 꺼진 인덱스라 만들지 않는다"));
            return null;
        }
        String filter = null;
        if (ix.filter() != null) {
            filter = translateFilter(ix.filter(), colNames);
            if (filter == null) {
                issues.add(new Issue(Level.WARN, where, "필터 인덱스 조건을 자동으로 옮기지 못해 만들지 않는다. 원문: " + ix.filter()));
                return null;
            }
        }
        keyBytesWarning(t, ix, issues);
        String name = namespace.claim(schema, Names.applyCase(ix.name(), rules.identifierCase()), table);
        String qt = Names.quote(schema) + "." + Names.quote(table);
        boolean nullable = ix.unique() && ix.keys().stream().anyMatch(k -> t.column(k.column()).nullable());
        String nulls = nullable && filter == null ? " NULLS NOT DISTINCT" : "";
        String noteNulls = nulls.isEmpty() ? null : "NULL 허용 키: MS-SQL 처럼 NULL 1건만(NULLS NOT DISTINCT)";
        String include = ix.includes().isEmpty() ? ""
                : " INCLUDE (" + ix.includes().stream().map(c -> Names.quote(colNames.get(c.toLowerCase(Locale.ROOT)))).collect(Collectors.joining(", ")) + ")";
        String whereClause = filter == null ? "" : " WHERE " + filter;

        if (ix.unique() && ciLowerIndex(t, ix)) {
            String note = join("대소문자 무시 UNIQUE → lower() 유일 인덱스(B14, collation.ci_unique: lower_index)", noteNulls);
            if (ix.kind() == SourceCatalog.IndexKind.UNIQUE_CONSTRAINT && referencedKeys.contains(keyOf(t.schema(), t.name(),
                    ix.keys().stream().map(SourceCatalog.IndexColumn::column).toList()))) {
                // FK 는 식 인덱스를 가리킬 수 없다 → 일반 UNIQUE 제약도 둔다
                String ciName = namespace.claim(schema, name + "_ci", table);
                String plain = "ALTER TABLE " + qt + " ADD CONSTRAINT " + Names.quote(name) + " UNIQUE" + nulls + " ("
                        + plainColumns(ix, colNames) + ")" + include;
                return new IndexPlan(ix.name(), name,
                        plain + ";\n" + uniqueLowerIndex(ciName, t, schema, table, ix, colNames) + include + nulls + whereClause,
                        join(note, "FK 가 가리켜 일반 UNIQUE 제약 " + name + " 도 둔다"));
            }
            return new IndexPlan(ix.name(), name, uniqueLowerIndex(name, t, schema, table, ix, colNames) + include + nulls + whereClause, note);
        }
        if (ix.kind() == SourceCatalog.IndexKind.UNIQUE_CONSTRAINT && filter == null) {
            return new IndexPlan(ix.name(), name, "ALTER TABLE " + qt + " ADD CONSTRAINT " + Names.quote(name) + " UNIQUE" + nulls + " ("
                    + plainColumns(ix, colNames) + ")" + include, noteNulls);
        }
        String keys = ix.keys().stream()
                .map(k -> Names.quote(colNames.get(k.column().toLowerCase(Locale.ROOT))) + (k.descending() ? " DESC" : ""))
                .collect(Collectors.joining(", "));
        return new IndexPlan(ix.name(), name, "CREATE " + (ix.unique() ? "UNIQUE " : "") + "INDEX " + Names.quote(name) + " ON " + qt
                + " (" + keys + ")" + include + (ix.unique() ? nulls : "") + whereClause, ix.unique() ? noteNulls : null);
    }

    private static String plainColumns(SourceCatalog.Index ix, Map<String, String> colNames) {
        return ix.keys().stream().map(k -> Names.quote(colNames.get(k.column().toLowerCase(Locale.ROOT)))).collect(Collectors.joining(", "));
    }

    /** CREATE UNIQUE INDEX 이름 ON 테이블 (lower(ci컬럼), 그 밖 컬럼) */
    private String uniqueLowerIndex(String name, SourceCatalog.Table t, String schema, String table, SourceCatalog.Index ix,
                                    Map<String, String> colNames) {
        String keys = ix.keys().stream().map(k -> {
            SourceCatalog.Column c = t.column(k.column());
            String q = Names.quote(colNames.get(k.column().toLowerCase(Locale.ROOT)));
            return (needsLower(t, c) ? "lower(" + q + ")" : q) + (k.descending() ? " DESC" : "");
        }).collect(Collectors.joining(", "));
        return "CREATE UNIQUE INDEX " + Names.quote(name) + " ON " + Names.quote(schema) + "." + Names.quote(table) + " (" + keys + ")";
    }

    private boolean ciLowerIndex(SourceCatalog.Table t, SourceCatalog.Index ix) {
        if (!"lower_index".equals(rules.ciUnique()) || "ci".equals(rules.collationTarget())) {
            return false;
        }
        return ix.keys().stream().anyMatch(k -> needsLower(t, t.column(k.column())));
    }

    private boolean needsLower(SourceCatalog.Table t, SourceCatalog.Column c) {
        return c.isText() && c.caseInsensitive() && "keep".equals(effectiveCase(t, c));
    }

    /** "([col] IS NOT NULL)" 처럼 컬럼·IS [NOT] NULL·AND·OR·비교·상수만 있는 조건만 옮긴다. 못 옮기면 null */
    static String translateFilter(String filter, Map<String, String> colNames) {
        Matcher m = Pattern.compile("\\[([^\\]]+)]").matcher(filter);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String tgt = colNames.get(m.group(1).toLowerCase(Locale.ROOT));
            if (tgt == null) {
                return null;
            }
            m.appendReplacement(b, Matcher.quoteReplacement(Names.quote(tgt)));
        }
        m.appendTail(b);
        String out = b.toString();
        String rest = out.replaceAll("\"(?:[^\"]|\"\")*\"", " ").replaceAll("N?'(?:[^']|'')*'", " ")
                .replaceAll("(?i)\\b(AND|OR|IS|NOT|NULL)\\b", " ").replaceAll("[-+]?[0-9]+(\\.[0-9]+)?", " ");
        return rest.matches("[\\s()=<>!]*") ? out.replaceAll("N'", "'") : null;
    }

    /** 키(+INCLUDE) 의 UTF-8 최대 바이트가 btree 한도를 넘을 수 있으면 경고(T-L11) */
    private void keyBytesWarning(SourceCatalog.Table t, SourceCatalog.Index ix, List<Issue> issues) {
        long total = 0;
        boolean unbounded = false;
        List<String> cols = new ArrayList<>(ix.keys().stream().map(SourceCatalog.IndexColumn::column).toList());
        cols.addAll(ix.includes());
        for (String name : cols) {
            SourceCatalog.Column c = t.column(name);
            if (c == null) {
                continue;
            }
            long b = maxUtf8Bytes(c);
            if (b < 0) {
                unbounded = true;
            } else {
                total += b;
            }
        }
        if (unbounded || total > BTREE_MAX_BYTES) {
            issues.add(new Issue(Level.WARN, t.qualifiedName() + " 인덱스 " + ix.name(), "키 최대 UTF-8 "
                    + (unbounded ? "길이 제한 없음(max 컬럼 포함)" : total + "바이트") + " > " + BTREE_MAX_BYTES
                    + ": 긴 값이 들어오면 PG 인덱스 입력이 실패한다(T-L11)"));
        }
    }

    /** 컬럼 값의 UTF-8 최대 바이트(추정). (max) 는 -1 */
    static long maxUtf8Bytes(SourceCatalog.Column c) {
        if (c.maxLength() == -1) {
            return -1;
        }
        if (!c.isText()) {
            return c.maxLength();
        }
        if (c.isNational()) {
            return (long) c.maxLength() / 2 * 3; // UTF-16 단위 하나 = UTF-8 최대 3바이트(보충 문자는 2단위 4바이트)
        }
        return switch (c.codePage()) {
            case 65001 -> c.maxLength();
            case 932, 936, 949, 950 -> (long) c.maxLength() * 3 / 2; // 2바이트 문자 → 3바이트
            default -> (long) c.maxLength() * 2;                    // 1바이트 코드페이지의 비ASCII → 2바이트
        };
    }

    // ---------------------------------------------------------------- FK

    private ForeignKeyPlan foreignKey(SourceCatalog.Table t, String schema, String table, SourceCatalog.ForeignKey fk,
                                      Map<String, String> colNames, List<Issue> issues) {
        String where = t.qualifiedName() + " FK " + fk.name();
        String[] ref = tableTargets.get(Rules.tableKey(fk.refSchema(), fk.refTable()));
        if (ref == null) {
            issues.add(new Issue(Level.WARN, where, "참조 테이블 " + fk.refSchema() + "." + fk.refTable() + " 이 이관 대상이 아니라 FK 를 만들지 않는다"));
            return null;
        }
        if (fk.disabled()) {
            issues.add(new Issue(Level.WARN, where, "원천에서 꺼진 FK 라 만들지 않는다"));
            return null;
        }
        if (fk.notTrusted()) {
            issues.add(new Issue(Level.WARN, where, "원천에서 검증되지 않은 FK(is_not_trusted). 전환 때 검사에서 실패할 수 있다"));
        }
        String name = Names.fit(Names.applyCase(fk.name(), rules.identifierCase()));
        Rules.TableRule refRule = rules.table(fk.refSchema(), fk.refTable());
        String refCols = fk.refColumns().stream().map(c -> {
            Rules.ColumnRule cr = refRule == null ? null : refRule.columns().get(c.toLowerCase(Locale.ROOT));
            return Names.quote(cr != null && cr.name() != null ? cr.name() : Names.applyCase(c, rules.identifierCase()));
        }).collect(Collectors.joining(", "));
        String ddl = "ALTER TABLE " + Names.quote(schema) + "." + Names.quote(table) + " ADD CONSTRAINT " + Names.quote(name)
                + " FOREIGN KEY (" + fk.columns().stream().map(c -> Names.quote(colNames.get(c.toLowerCase(Locale.ROOT))))
                .collect(Collectors.joining(", ")) + ") REFERENCES " + Names.quote(ref[0]) + "." + Names.quote(ref[1])
                + " (" + refCols + ")" + action("DELETE", fk.onDelete()) + action("UPDATE", fk.onUpdate());
        return new ForeignKeyPlan(fk.name(), name, ddl);
    }

    private static String action(String on, String desc) {
        return switch (desc == null ? "NO_ACTION" : desc) {
            case "CASCADE" -> " ON " + on + " CASCADE";
            case "SET_NULL" -> " ON " + on + " SET NULL";
            case "SET_DEFAULT" -> " ON " + on + " SET DEFAULT";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- 시퀀스

    private SequencePlan sequence(SourceCatalog.Sequence s) {
        String where = s.schema() + "." + s.name();
        List<Issue> issues = new ArrayList<>();
        String schema = targetSchema(s.schema());
        String name = Names.applyCase(s.name(), rules.identifierCase());
        checkIdentifier(name, where, "시퀀스", issues);
        if (!namespace.reserve(schema, name)) {
            issues.add(new Issue(Level.ERROR, where, "대상 이름 " + schema + "." + name + " 이 테이블과 겹친다"));
        }
        sequenceTargets.put((s.schema() + "." + s.name()).toLowerCase(Locale.ROOT), Names.quote(schema) + "." + Names.quote(name));

        String type;
        BigInteger lo;
        BigInteger hi;
        switch (s.typeName()) {
            case "tinyint", "smallint" -> {
                type = "smallint";
                lo = BigInteger.valueOf(Short.MIN_VALUE);
                hi = BigInteger.valueOf(Short.MAX_VALUE);
            }
            case "int" -> {
                type = "integer";
                lo = BigInteger.valueOf(Integer.MIN_VALUE);
                hi = BigInteger.valueOf(Integer.MAX_VALUE);
            }
            default -> {
                type = "bigint";
                lo = BigInteger.valueOf(Long.MIN_VALUE);
                hi = BigInteger.valueOf(Long.MAX_VALUE);
                if (!s.typeName().equals("bigint")) {
                    issues.add(new Issue(Level.WARN, where, s.typeName() + "(" + s.precision() + ") 시퀀스를 bigint 로 만든다"));
                }
            }
        }
        BigInteger min = clamp(s.min(), lo, hi);
        BigInteger max = clamp(s.max(), lo, hi);
        if (!min.equals(s.min()) || !max.equals(s.max())) {
            issues.add(new Issue(Level.WARN, where, "최소·최대값을 " + type + " 범위로 줄였다(" + s.min() + " ~ " + s.max() + ")"));
        }
        if (s.start().compareTo(min) < 0 || s.start().compareTo(max) > 0) {
            issues.add(new Issue(Level.ERROR, where, "시작값 " + s.start() + " 이 " + type + " 범위 밖"));
        }
        String ddl = "CREATE SEQUENCE " + Names.quote(schema) + "." + Names.quote(name) + " AS " + type
                + " START WITH " + s.start() + " INCREMENT BY " + s.increment()
                + " MINVALUE " + min + " MAXVALUE " + max + (s.cycling() ? " CYCLE" : " NO CYCLE");
        globalIssues.addAll(issues);
        return new SequencePlan(s, schema, name, type, ddl, List.copyOf(issues));
    }

    private static BigInteger clamp(BigInteger v, BigInteger lo, BigInteger hi) {
        return v.max(lo).min(hi);
    }

    // ---------------------------------------------------------------- 공통

    private String targetSchema(String srcSchema) {
        return rules.schemaMap().getOrDefault(srcSchema.toLowerCase(Locale.ROOT), Names.applyCase(srcSchema, rules.identifierCase()));
    }

    private static void checkIdentifier(String name, String where, String what, List<Issue> issues) {
        if (Names.bytes(name) > Names.MAX_BYTES) {
            issues.add(new Issue(Level.ERROR, where, what + " 이름 " + name + " 이 63바이트를 넘는다(" + Names.bytes(name)
                    + "). PG 는 조용히 잘라 쓰므로 규칙 파일에서 이름을 정한다"));
        }
    }

    private static String keyOf(String schema, String table, List<String> cols) {
        return (schema + "." + table + "|" + String.join(",", cols)).toLowerCase(Locale.ROOT);
    }

    /** 규칙 파일 tables 의 테이블·컬럼이 원천에 있는지(오타 방지) */
    private void checkRuleKeys(SourceCatalog catalog) {
        for (Map.Entry<String, Rules.TableRule> e : rules.tables().entrySet()) {
            SourceCatalog.Table t = catalog.tables().stream()
                    .filter(x -> Rules.tableKey(x.schema(), x.name()).equals(e.getKey())).findFirst().orElse(null);
            if (t == null) {
                globalIssues.add(new Issue(Level.WARN, "규칙 tables." + e.getKey(), "원천에 없는 테이블"));
                continue;
            }
            for (String col : e.getValue().columns().keySet()) {
                if (t.column(col) == null) {
                    globalIssues.add(new Issue(Level.WARN, "규칙 tables." + e.getKey() + ".columns." + col, "원천에 없는 컬럼"));
                }
            }
        }
        for (String k : rules.identifierMap().keySet()) {
            if (catalog.tables().stream().noneMatch(x -> Rules.tableKey(x.schema(), x.name()).equals(k))) {
                globalIssues.add(new Issue(Level.WARN, "규칙 identifiers.map." + k, "원천에 없는 테이블"));
            }
        }
    }

    /** 값은 같지만 조회 결과가 달라지는 곳(KIS B01~B14) — 합격·불합격이 아니라 규칙 결정의 결과 */
    private void behaviorNotes(List<TablePlan> tables) {
        long ci = 0;
        long varcharKeep = 0;
        long cp = 0;
        for (TablePlan t : tables) {
            for (ColumnPlan c : t.columns()) {
                SourceCatalog.Column s = c.source();
                if (s.isText() && s.caseInsensitive() && (c.collate() == null || !c.collate().endsWith(Names.quote(SchemaPlan.CI_COLLATION)))
                        && "keep".equals(c.value().caseRule())) {
                    ci++;
                }
                // char 는 PG 도 끝 공백을 무시하고 비교한다(bpchar). text/ntext 는 원천에서 = 비교가 안 된다
                boolean varying = s.typeName().equals("varchar") || s.typeName().equals("nvarchar") || s.typeName().equals("sysname");
                if (varying && "keep".equals(c.value().trailingSpace())) {
                    varcharKeep++;
                }
                if (s.isText() && !s.isNational() && s.codePage() != 0 && s.codePage() != 65001) {
                    cp++;
                }
            }
        }
        if (ci > 0) {
            globalIssues.add(new Issue(Level.NOTE, "collation.target: " + rules.collationTarget(), "원천 대소문자 무시(CI) 문자 컬럼 " + ci
                    + "개가 대상에선 대소문자를 구분해 비교·정렬한다: = 'aa+' 결과 건수, 키 조회, 영문 정렬 순서가 달라진다(B01·B03·B06)"));
        }
        if ("none".equals(rules.ciUnique())) {
            globalIssues.add(new Issue(Level.NOTE, "collation.ci_unique: none", "대소문자만 다른 값('Kim01'/'kim01')이 대상 UNIQUE 를 통과한다(B14)"));
        } else if ("ci_collation".equals(rules.ciUnique()) || "ci".equals(rules.collationTarget())) {
            globalIssues.add(new Issue(Level.NOTE, "비결정 콜레이션 " + SchemaPlan.CI_COLLATION, "PG 16 은 비결정 콜레이션 컬럼에 LIKE·정규식을 쓸 수 없다. 앱 SQL 확인"));
        }
        if (varcharKeep > 0) {
            globalIssues.add(new Issue(Level.NOTE, "text.trailing_space: keep", "끝 공백을 그대로 옮긴 varchar 컬럼 " + varcharKeep
                    + "개: MS-SQL 의 'A' = 'A ' 는 참이지만 대상에선 거짓(B02·B04). 코드성 컬럼은 tables 에서 trailing_space: rtrim"));
        }
        if (cp > 0) {
            globalIssues.add(new Issue(Level.NOTE, "문자 인코딩", "비유니코드(CP949 등) 문자 컬럼 " + cp
                    + "개: 문자 수는 같고 UTF-8 바이트는 늘어난다. LEN ↔ length·octet_length 결과가 다르다(B05). 원천에 이미 '?' 로 저장된 문자는 되살릴 수 없다(A03)"));
        }
    }

    // ---------------------------------------------------------------- 테이블 선택

    /** "dbo.*", "dbo.rating*" 같은 패턴(대소문자 무시) */
    static boolean matchesAny(String qualified, List<String> patterns) {
        for (String p : patterns) {
            String regex = Pattern.quote(p.toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
            if (qualified.toLowerCase(Locale.ROOT).matches(regex)) {
                return true;
            }
        }
        return false;
    }

    private static String join(String a, String b) {
        return b == null ? a : a + "; " + b;
    }
}
