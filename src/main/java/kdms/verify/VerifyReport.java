package kdms.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import kdms.ddl.SchemaPlan;
import kdms.verify.Verifier.Check;
import kdms.verify.Verifier.TableResult;

/** kdms verify 보고서(텍스트). 숫자와 PK 만 쓴다(행 값 없음). */
public final class VerifyReport {

    private VerifyReport() {
    }

    /** 테이블 한 줄(+ 불일치 내역) */
    public static String table(TableResult r) {
        StringBuilder b = new StringBuilder();
        String name = r.table().srcQualified() + " → " + r.table().tgtSchema() + "." + r.table().tgtName();
        if (r.error() != null) {
            return b.append("[오류]   ").append(name).append(": ").append(r.error()).toString();
        }
        long sums = r.checks().stream().filter(c -> c.kind().equals("sum")).count();
        long badSums = r.checks().stream().filter(c -> c.kind().equals("sum") && !c.matched()).count();
        Check count = r.checks().get(0);
        Check hash = r.checks().get(1);
        b.append(r.matched() ? "[일치]   " : "[불일치] ").append(name).append(": ")
                .append("건수 ").append(count.matched() ? fmt(count.source()) : fmt(count.source()) + " ≠ " + fmt(count.tgt()))
                .append(" · 해시 ").append(hash.matched() ? "같음" : "다름")
                .append(" · 합계 ").append(sums).append("개").append(badSums == 0 ? (sums == 0 ? "" : " 같음") : " 중 " + badSums + "개 다름")
                .append(" (").append(String.format("%.1f초", r.elapsedMs() / 1000.0)).append(')');
        for (Check c : r.checks()) {
            if (c.kind().equals("sum") && !c.matched()) {
                b.append("\n    합계 ").append(c.target()).append(": 원천 ").append(plain(c.source())).append(" ≠ 대상 ").append(plain(c.tgt()));
            }
        }
        if (r.diffTotal() > 0) {
            long missing = r.diffSides().getOrDefault("missing", 0L);
            long extra = r.diffSides().getOrDefault("extra", 0L);
            long diff = r.diffSides().getOrDefault("diff", 0L);
            b.append("\n    차이 행 ").append(r.diffTotal()).append("건")
                    .append(r.diffTotal() > r.rowDiffs().size() ? "(앞 " + r.rowDiffs().size() + "건만 기록)" : "")
                    .append(": missing(원천에만) ").append(missing).append(" · extra(대상에만) ").append(extra)
                    .append(" · diff(값 다름) ").append(diff);
            r.rowDiffs().stream().limit(20).forEach(d -> b.append("\n      ").append(d.side()).append(" PK ").append(d.pk()));
        }
        for (String n : r.notes()) {
            b.append("\n    참고: ").append(n);
        }
        return b.toString();
    }

    /** 합계 줄 + 주의(규칙 결정 때문에 조회 결과가 달라지는 곳, KIS B01~B14) */
    public static String summary(Verifier.Result result, SchemaPlan plan) {
        List<String> lines = new ArrayList<>();
        long tablesOk = result.tables().stream().filter(TableResult::matched).count();
        lines.add("결과: 검증 항목 " + result.checks() + "개 중 일치 " + (result.checks() - result.mismatches() + errors(result))
                + " · 불일치 " + result.mismatches() + " / 테이블 " + result.tables().size() + "개 중 일치 " + tablesOk
                + " (kdms.verify_run run_id " + result.runId() + ")");
        List<SchemaPlan.Issue> notes = plan.allIssues().stream().filter(i -> i.level() == SchemaPlan.Issue.Level.NOTE).toList();
        if (!notes.isEmpty()) {
            lines.add("");
            lines.add("주의: 값은 같지만 조회 결과가 원천과 다를 수 있는 곳(이관 오류가 아니라 규칙 결정, plan.md §8.3)");
            lines.addAll(notes.stream().map(i -> "  - " + i.where() + ": " + i.message()).collect(Collectors.toList()));
        }
        return String.join("\n", lines);
    }

    /** 오류 난 테이블은 항목이 없으므로 불일치 수에서 1 로 셌다 → 일치 수 계산에서 되돌린다 */
    private static long errors(Verifier.Result r) {
        return r.tables().stream().filter(t -> t.error() != null).count();
    }

    private static String fmt(java.math.BigDecimal v) {
        return v == null ? "NULL" : String.format("%,d", v.longValue());
    }

    private static String plain(java.math.BigDecimal v) {
        return v == null ? "NULL" : v.toPlainString();
    }
}
