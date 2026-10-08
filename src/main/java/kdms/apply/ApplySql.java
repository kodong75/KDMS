package kdms.apply;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import kdms.ddl.Names;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;

/**
 * 변경 하나를 대상에 멱등으로 적용하는 SQL(plan.md §4.4). 값은 모두 문자열 매개변수로 넘기고 대상 타입으로 CAST 한다
 * (전체 적재의 COPY text 와 같은 입력 표현이 된다).
 * <ul>
 * <li>입력·수정: {@code INSERT … ON CONFLICT (pk) DO UPDATE} — 변경 후 값 전체. 이미 있거나 없거나 결과가 같다</li>
 * <li>삭제: {@code DELETE … WHERE pk} — 없으면 0행</li>
 * <li>LOB 이 바뀌지 않아 값이 없는 수정(R5): 그 컬럼만 빼고 {@code UPDATE … WHERE pk}</li>
 * </ul>
 */
final class ApplySql {

    final TablePlan table;
    /** 값을 넣는 컬럼(계산 컬럼 제외), 원천 순서 */
    final List<ColumnPlan> columns;
    /** PK 컬럼, PK 순서 */
    final List<ColumnPlan> key;
    final String upsert;
    final String delete;

    ApplySql(TablePlan t) {
        if (t.primaryKey() == null) {
            throw new IllegalArgumentException(t.srcQualified() + ": PK 가 없는 테이블은 변경분을 반영하지 않는다(plan.md §4.6)");
        }
        this.table = t;
        this.columns = t.loadColumns();
        Map<String, ColumnPlan> byTarget = columns.stream().collect(Collectors.toMap(ColumnPlan::tgtName, Function.identity()));
        this.key = t.primaryKey().columns().stream().map(n -> {
            ColumnPlan c = byTarget.get(n);
            if (c == null) {
                throw new IllegalArgumentException(t.srcQualified() + ": PK 컬럼 " + n + " 이 값 컬럼에 없다");
            }
            return c;
        }).toList();
        String cols = columns.stream().map(c -> Names.quote(c.tgtName())).collect(Collectors.joining(", "));
        String values = columns.stream().map(ApplySql::param).collect(Collectors.joining(", "));
        String conflict = key.stream().map(c -> Names.quote(c.tgtName())).collect(Collectors.joining(", "));
        List<ColumnPlan> rest = columns.stream().filter(c -> !key.contains(c)).toList();
        this.upsert = "INSERT INTO " + t.tgtQualified() + " (" + cols + ") VALUES (" + values + ") ON CONFLICT (" + conflict + ") "
                + (rest.isEmpty() ? "DO NOTHING"
                        : "DO UPDATE SET " + rest.stream().map(c -> Names.quote(c.tgtName()) + " = EXCLUDED." + Names.quote(c.tgtName()))
                                .collect(Collectors.joining(", ")));
        this.delete = "DELETE FROM " + t.tgtQualified() + " WHERE " + where();
    }

    /** 값이 있는 컬럼만 바꾸는 UPDATE(LOB 미변경, R5) */
    String update(List<ColumnPlan> set) {
        return "UPDATE " + table.tgtQualified() + " SET "
                + set.stream().map(c -> Names.quote(c.tgtName()) + " = " + param(c)).collect(Collectors.joining(", "))
                + " WHERE " + where();
    }

    private String where() {
        return key.stream().map(c -> Names.quote(c.tgtName()) + " = " + param(c)).collect(Collectors.joining(" AND "));
    }

    /** CAST(? AS 대상 타입). 문자열 입력을 대상 타입의 입력 함수로 해석한다(COPY text 와 같다) */
    static String param(ColumnPlan c) {
        return "CAST(? AS " + c.tgtType() + ")";
    }
}
