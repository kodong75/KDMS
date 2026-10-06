package kdms.ddl;

import java.util.List;
import java.util.stream.Collectors;

import kdms.catalog.SourceCatalog;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.Issue;
import kdms.ddl.SchemaPlan.TablePlan;

/**
 * {@code kdms plan} 보고서(사람이 읽고 규칙 파일을 고칠 근거). 행 값은 들어가지 않는다.
 */
public final class PlanReport {

    private PlanReport() {
    }

    /**
     * @param header 첫 줄들(작업 이름, 규칙 파일, 시각 등)
     */
    public static String render(SchemaPlan p, List<String> header) {
        StringBuilder b = new StringBuilder();
        header.forEach(h -> b.append(h).append('\n'));
        long cols = p.tables().stream().mapToLong(t -> t.columns().size()).sum();
        long noPk = p.tables().stream().filter(t -> t.primaryKey() == null).count();
        b.append("원천: ").append(p.sourceDb()).append(" (콜레이션 ").append(p.sourceCollation()).append(")\n");
        b.append("요약: 테이블 ").append(p.tables().size()).append("개 (PK 없음 ").append(noPk).append(", 제외 ")
                .append(p.excluded().size()).append(") · 컬럼 ").append(cols).append("개 · 시퀀스 ").append(p.sequences().size())
                .append("개 · 오류 ").append(p.count(Issue.Level.ERROR)).append(" · 경고 ").append(p.count(Issue.Level.WARN))
                .append(" · 주의 ").append(p.count(Issue.Level.NOTE)).append('\n');
        b.append("결과: ").append(p.blocked()
                ? "막힘. 아래 오류를 규칙 파일에서 고친 뒤 kdms plan 을 다시 실행한다"
                : "통과. kdms schema 로 대상에 만들 수 있다").append('\n');

        section(b, "오류 (고치기 전에는 대상에 만들지 않는다)", p.allIssues(), Issue.Level.ERROR);

        b.append("\n== 테이블 ==\n");
        for (TablePlan t : p.tables()) {
            table(b, t);
        }
        if (!p.excluded().isEmpty()) {
            b.append("\n== 제외한 테이블 ==\n");
            p.excluded().forEach(e -> b.append("  ").append(e.table()).append("  (").append(e.reason()).append(")\n"));
        }
        if (!p.sequences().isEmpty()) {
            b.append("\n== 시퀀스 ==\n");
            p.sequences().forEach(s -> b.append("  ").append(s.source().schema()).append('.').append(s.source().name())
                    .append(" → ").append(s.tgtSchema()).append('.').append(s.tgtName()).append("  ").append(s.tgtType())
                    .append(" 시작 ").append(s.source().start()).append(" 증가 ").append(s.source().increment())
                    .append(" 현재 ").append(s.source().current()).append(" (전환 때 setval, B13)\n"));
        }

        section(b, "경고 (적용은 하지만 확인할 것)", p.allIssues(), Issue.Level.WARN);
        section(b, "주의: 값은 같지만 조회 결과가 다른 곳(규칙 결정, KIS B01~B14)", p.allIssues(), Issue.Level.NOTE);

        if (!p.manualObjects().isEmpty()) {
            b.append("\n== 자동 변환하지 않는 객체 (KIS:docs/appcompat.md 방식으로 수작업) ==\n");
            p.manualObjects().stream().collect(Collectors.groupingBy(SourceCatalog.DbObject::typeDesc,
                            java.util.TreeMap::new, Collectors.mapping(o -> o.schema() + "." + o.name(), Collectors.toList())))
                    .forEach((type, names) -> b.append("  ").append(type).append(" ").append(names.size()).append(": ")
                            .append(String.join(", ", names)).append('\n'));
        }
        return b.toString();
    }

    private static void table(StringBuilder b, TablePlan t) {
        b.append('\n').append(t.srcQualified()).append(" → ").append(t.tgtSchema()).append('.').append(t.tgtName())
                .append("   행(추정) ").append(String.format("%,d", t.rowsEstimate()))
                .append("   PK ").append(t.primaryKey() == null ? "없음" : "(" + String.join(", ", t.primaryKey().columns()) + ")")
                .append('\n');
        int w1 = Math.max(12, t.columns().stream().mapToInt(c -> width(c.srcName())).max().orElse(0)) + 2;
        int w2 = Math.max(12, t.columns().stream().mapToInt(c -> width(c.srcType())).max().orElse(0)) + 2;
        int w3 = Math.max(12, t.columns().stream().mapToInt(c -> width(targetDisplay(c))).max().orElse(0)) + 2;
        b.append("  ").append(pad("원천 컬럼", w1)).append(pad("원천 타입", w2)).append(pad("대상", w3)).append("비고\n");
        for (ColumnPlan c : t.columns()) {
            b.append("  ").append(pad(c.srcName(), w1)).append(pad(c.srcType(), w2)).append(pad(targetDisplay(c), w3))
                    .append(String.join("; ", c.notes())).append('\n');
        }
        for (SchemaPlan.IndexPlan i : t.postLoad()) {
            b.append("  [적재 뒤] ").append(i.ddl().replace("\n", "\n            "))
                    .append(i.note() == null ? "" : "\n            ↳ " + i.note()).append('\n');
        }
        for (SchemaPlan.ForeignKeyPlan f : t.foreignKeys()) {
            b.append("  [전환 때] ").append(f.ddl()).append('\n');
        }
    }

    /** 대상 열: 이름이 바뀌면 "이름 타입" */
    static String targetDisplay(ColumnPlan c) {
        StringBuilder s = new StringBuilder();
        if (!c.tgtName().equals(c.srcName())) {
            s.append(c.tgtName()).append(' ');
        }
        s.append(c.tgtType());
        if (c.generated() != null) {
            s.append(" GENERATED");
        }
        if (c.identity() != null) {
            s.append(" IDENTITY");
        }
        if (!c.nullable()) {
            s.append(" NOT NULL");
        }
        if (c.defaultExpr() != null) {
            s.append(" DEFAULT ").append(c.defaultExpr());
        }
        if (c.check() != null) {
            s.append(" CHECK");
        }
        return s.toString();
    }

    private static void section(StringBuilder b, String title, List<Issue> issues, Issue.Level level) {
        List<Issue> list = issues.stream().filter(i -> i.level() == level).toList();
        if (list.isEmpty()) {
            return;
        }
        b.append("\n== ").append(title).append(" ==\n");
        list.forEach(i -> b.append("  [").append(i.where()).append("] ").append(i.message()).append('\n'));
    }

    /** 한글은 터미널에서 두 칸 */
    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            w += (ch >= 0x1100 && (ch <= 0x115F || (ch >= 0xAC00 && ch <= 0xD7A3) || (ch >= 0x3130 && ch <= 0x318F))) ? 2 : 1;
        }
        return w;
    }

    static String pad(String s, int w) {
        return s + " ".repeat(Math.max(1, w - width(s)));
    }
}
