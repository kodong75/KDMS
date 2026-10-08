package kdms.apply;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kdms.cdc.CdcValues;
import kdms.cdc.Lsn;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.CopyValues;
import kdms.load.SafeMessage;
import kdms.rules.Rules;

/**
 * 반영기(plan.md §4.4, docs/cdc.md §4). kdms.change_log 를 LSN 순서로 읽어 대상 테이블에 멱등으로 적용한다.
 * <ul>
 * <li>전체 적재가 끝난(LOADED) 테이블의 변경만 적용한다. 적재 중인 테이블의 변경은 change_log 에서 기다린다</li>
 * <li>한 배치 = 한 대상 트랜잭션: 적용 + change_log 에서 지우기(행 값을 남기지 않는다, R8) + 테이블별·작업 반영 위치 갱신</li>
 * <li>테이블 안에서는 (commit_lsn, change_lsn, event_serial_no) 순서를 지킨다. 테이블끼리는 서로 기다리지 않는다
 *     (반영 중에는 대상에 FK·트리거가 없다. FK 는 전환 때 만든다)</li>
 * <li>값 변환은 전체 적재와 같은 {@link CopyValues#value} 를 지난다(R4, T-C05)</li>
 * </ul>
 * 적용이 실패하면(값 규칙 nul_char: fail 인 NUL, 대상 제약 위반 등) 그 배치를 되돌리고 {@link Failed} 를 던진다. 조용히 건너뛰지 않는다.
 */
public final class Applier {

    /** 적용 실패. 메시지에 행 값은 없다(테이블·LSN 위치·원인만) */
    public static final class Failed extends Exception {
        public Failed(String message) {
            super(message);
        }
    }

    /**
     * @param applied   이 배치에서 적용한 변경 수
     * @param last      작업 전체에서 마지막으로 적용한 위치(없으면 null)
     */
    public record Batch(int applied, Lsn.Position last) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final long jobId;
    private final Connection tgt;
    private final int batchSize;
    /** 원천 "schema.table"(소문자) → 적용 SQL·값 변환기 */
    private final Map<String, Table> tables = new HashMap<>();

    private static final class Table {
        final ApplySql sql;
        final Map<String, CopyValues.Column> conv = new LinkedHashMap<>();
        final Map<String, PreparedStatement> statements = new HashMap<>();

        Table(TablePlan t, Rules rules) {
            this.sql = new ApplySql(t);
            List<CopyValues.Column> cols = CopyValues.columns(sql.columns, rules.text().nulReplacement(), rules.sentinelValues());
            for (CopyValues.Column c : cols) {
                conv.put(c.plan().srcName(), c);
            }
        }
    }

    /**
     * @param plans PK 가 있는 계획 테이블(변경을 반영할 수 있는 것)
     * @param tgt   반영 전용 대상 연결(이 객체가 트랜잭션을 관리한다)
     */
    public Applier(long jobId, List<TablePlan> plans, Rules rules, Connection tgt, int batchSize) {
        this.jobId = jobId;
        this.tgt = tgt;
        this.batchSize = batchSize;
        for (TablePlan t : plans) {
            tables.put((t.srcSchema() + "." + t.srcName()).toLowerCase(Locale.ROOT), new Table(t, rules));
        }
    }

    /** 반영할 수 있는 변경을 한 배치(최대 batchSize) 적용한다. 적용할 것이 없으면 applied = 0 */
    public Batch applyOnce() throws SQLException, Failed {
        tgt.setAutoCommit(false);
        try {
            try (Statement st = tgt.createStatement()) {
                // 반영 트랜잭션이 사라지면 change_log 삭제도 함께 사라져 다시 반영된다. 그래서 동기 커밋이 필요 없다
                st.execute("SET LOCAL synchronous_commit TO off");
            }
            // 적재가 끝난 테이블에 공유 잠금: 같은 테이블을 kdms load --reset 이 비우는 것과 겹치지 않게
            try (PreparedStatement ps = tgt.prepareStatement("""
                    SELECT job_table_id FROM kdms.job_table
                    WHERE job_id = ? AND status = 'LOADED' AND has_pk FOR SHARE""")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        tgt.commit();
                        return new Batch(0, null);
                    }
                }
            }
            List<Long> ids = new ArrayList<>();
            Map<String, Lsn.Position> lastByTable = new LinkedHashMap<>();
            Map<String, Integer> countByTable = new HashMap<>();
            Map<String, String[]> names = new HashMap<>();
            Lsn.Position last = null;
            OffsetDateTime lastCommitAt = null;
            try (PreparedStatement ps = tgt.prepareStatement("""
                    SELECT c.change_id, c.commit_lsn, c.change_lsn, c.event_serial_no, c.op, c.src_schema, c.src_table, c.payload::text, c.src_commit_at
                    FROM kdms.change_log c
                    WHERE c.job_id = ?
                      AND EXISTS (SELECT 1 FROM kdms.job_table t
                                  WHERE t.job_id = c.job_id AND t.status = 'LOADED' AND t.has_pk
                                    AND lower(t.src_schema) = lower(c.src_schema) AND lower(t.src_table) = lower(c.src_table))
                    ORDER BY c.commit_lsn COLLATE "C", c.change_lsn COLLATE "C", c.event_serial_no
                    LIMIT ?""")) {
                ps.setLong(1, jobId);
                ps.setInt(2, batchSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Lsn.Position pos = new Lsn.Position(rs.getString(2), rs.getString(3), rs.getLong(4));
                        String key = (rs.getString(6) + "." + rs.getString(7)).toLowerCase(Locale.ROOT);
                        Table t = tables.get(key);
                        if (t == null) {
                            throw new Failed(rs.getString(6) + "." + rs.getString(7) + " 의 변경을 반영할 계획이 없다(PK 없음 또는 계획 밖) · 위치 " + pos);
                        }
                        apply(t, rs.getString(5), JSON.readTree(rs.getString(8)), pos);
                        ids.add(rs.getLong(1));
                        lastByTable.put(key, pos);
                        countByTable.merge(key, 1, Integer::sum);
                        names.put(key, new String[] {rs.getString(6), rs.getString(7)});
                        last = pos;
                        lastCommitAt = rs.getObject(9, OffsetDateTime.class);
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("change_log.payload 를 읽지 못했다", e);
            }
            if (ids.isEmpty()) {
                tgt.commit();
                return new Batch(0, null);
            }
            try (PreparedStatement ps = tgt.prepareStatement("DELETE FROM kdms.change_log WHERE change_id = ANY (?)")) {
                Array a = tgt.createArrayOf("bigint", ids.toArray());
                ps.setArray(1, a);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tgt.prepareStatement("""
                    UPDATE kdms.job_table SET applied_commit_lsn = ?, applied_change_lsn = ?, applied_event_serial_no = ?,
                           changes_applied = changes_applied + ?
                    WHERE job_id = ? AND lower(src_schema) = lower(?) AND lower(src_table) = lower(?)""")) {
                for (Map.Entry<String, Lsn.Position> e : lastByTable.entrySet()) {
                    ps.setString(1, e.getValue().commitLsn());
                    ps.setString(2, e.getValue().changeLsn());
                    ps.setLong(3, e.getValue().eventSerialNo());
                    ps.setLong(4, countByTable.get(e.getKey()));
                    ps.setLong(5, jobId);
                    ps.setString(6, names.get(e.getKey())[0]);
                    ps.setString(7, names.get(e.getKey())[1]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = tgt.prepareStatement("""
                    UPDATE kdms.watermark SET applied_commit_lsn = ?, applied_change_lsn = ?, applied_event_serial_no = ?,
                           applied_src_commit_at = ?, applied_at = now(), changes_applied = changes_applied + ?
                    WHERE job_id = ?""")) {
                ps.setString(1, last.commitLsn());
                ps.setString(2, last.changeLsn());
                ps.setLong(3, last.eventSerialNo());
                ps.setObject(4, lastCommitAt);
                ps.setLong(5, ids.size());
                ps.setLong(6, jobId);
                ps.executeUpdate();
            }
            tgt.commit();
            return new Batch(ids.size(), last);
        } catch (SQLException | Failed | RuntimeException e) {
            try {
                tgt.rollback();
            } catch (SQLException ignored) {
                // 연결이 끊긴 경우. 원래 오류를 알린다
            }
            throw e;
        } finally {
            tgt.setAutoCommit(true);
        }
    }

    private void apply(Table t, String op, JsonNode payload, Lsn.Position pos) throws SQLException, Failed {
        String where = t.sql.table.srcQualified() + " 위치 " + pos;
        try {
            JsonNode before = payload.get("before");
            JsonNode after = payload.get("after");
            switch (op) {
                case "d" -> {
                    if (before == null || before.isNull()) {
                        throw new Failed(where + ": 변경 전 값이 없는 삭제 이벤트");
                    }
                    exec(t, t.sql.delete, t.sql.key, before, where);
                }
                case "c", "u" -> {
                    if (after == null || after.isNull()) {
                        throw new Failed(where + ": 변경 후 값이 없는 " + op + " 이벤트");
                    }
                    // PK 를 바꾸는 UPDATE 가 한 이벤트로 오면 옛 PK 를 먼저 지운다(SQL Server CDC 는 보통 삭제 + 입력 두 이벤트로 준다, T-C07)
                    if ("u".equals(op) && before != null && !before.isNull() && keyChanged(t, before, after)) {
                        exec(t, t.sql.delete, t.sql.key, before, where);
                    }
                    List<ColumnPlan> missing = t.sql.columns.stream().filter(c -> CdcValues.unavailable(after.get(c.srcName()))).toList();
                    if (missing.isEmpty()) {
                        exec(t, t.sql.upsert, t.sql.columns, after, where);
                    } else {
                        // LOB 이 바뀌지 않아 값이 없다(R5, T-C06): 그 컬럼은 대상 값을 그대로 둔다
                        List<ColumnPlan> set = t.sql.columns.stream().filter(c -> !missing.contains(c) && !t.sql.key.contains(c)).toList();
                        List<ColumnPlan> params = new ArrayList<>(set);
                        params.addAll(t.sql.key);
                        int n = exec(t, t.sql.update(set), params, after, where);
                        if (n == 0) {
                            throw new Failed(where + ": 값이 없는 LOB 컬럼(" + missing.stream().map(ColumnPlan::srcName).toList()
                                    + ")이 있는 수정인데 대상에 행이 없다. 이 테이블을 다시 적재한다(kdms load --reset -t " + t.sql.table.srcQualified() + ")");
                        }
                    }
                }
                default -> throw new Failed(where + ": 처리하지 않는 연산 " + op);
            }
        } catch (SQLException e) {
            throw new Failed(where + ": " + SafeMessage.of(e));
        }
    }

    private boolean keyChanged(Table t, JsonNode before, JsonNode after) {
        for (ColumnPlan c : t.sql.key) {
            if (!before.path(c.srcName()).equals(after.path(c.srcName()))) {
                return true;
            }
        }
        return false;
    }

    private int exec(Table t, String sql, List<ColumnPlan> params, JsonNode row, String where) throws SQLException, Failed {
        PreparedStatement ps = t.statements.get(sql);
        if (ps == null) {
            ps = tgt.prepareStatement(sql);
            t.statements.put(sql, ps);
        }
        for (int i = 0; i < params.size(); i++) {
            ColumnPlan c = params.get(i);
            CopyValues.Column conv = t.conv.get(c.srcName());
            long nulBefore = conv.nulRows();
            String v = CopyValues.value(conv, CdcValues.decode(c.source().typeName(), row.get(c.srcName())));
            if (conv.nulRows() != nulBefore) {
                throw new Failed(where + ": NUL 문자(A02)가 든 값: " + t.sql.table.srcQualified() + "." + c.srcName()
                        + ". 규칙 text.nul_char: fail 이라 반영하지 않았다. 컬럼별로 nul_char: strip | replace 를 정하고 이 테이블을 다시 적재한다(docs/load-verify.md §4)");
            }
            ps.setString(i + 1, v);
        }
        return ps.executeUpdate();
    }

    /** 준비한 문장을 닫는다(연결은 부른 쪽이 닫는다) */
    public void close() {
        for (Table t : tables.values()) {
            for (PreparedStatement ps : t.statements.values()) {
                try {
                    ps.close();
                } catch (SQLException ignored) {
                    // 닫기 실패는 무시
                }
            }
        }
    }
}
