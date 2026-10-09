package kdms.load;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;

import kdms.config.KdmsConfig;
import kdms.ddl.KdmsMockCatalog;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlanner;
import kdms.rules.Rules;
import kdms.rules.RulesLoader;

/** 시험 공통: KDMS_MOCK 계획(시험 고정 카탈로그 + 저장소 규칙)과 값 하나짜리 가짜 ResultSet */
public final class MockPlans {

    public static final Rules RULES = RulesLoader.load(Path.of("config/kdms-rules.yml"));

    private MockPlans() {
    }

    public static SchemaPlan plan() {
        return SchemaPlanner.plan(KdmsMockCatalog.catalog(), new KdmsConfig.TableSelection(List.of("dbo.*"), List.of()), RULES, null);
    }

    public static SchemaPlan.TablePlan table(String name) {
        return plan().tables().stream().filter(t -> t.srcName().equals(name)).findFirst().orElseThrow();
    }

    public static SchemaPlan.ColumnPlan column(String table, String column) {
        return table(table).columns().stream().filter(c -> c.srcName().equals(column)).findFirst().orElseThrow();
    }

    /** 1번 열에 value 하나만 있는 ResultSet(getString·getObject·getLong·getBoolean·getBigDecimal·getBytes·wasNull) */
    static ResultSet one(Object value) {
        boolean[] lastNull = {false};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (p, m, args) -> {
            lastNull[0] = value == null;
            return switch (m.getName()) {
                case "wasNull" -> value == null;
                case "getString" -> value == null ? null : value.toString();
                case "getObject", "getBigDecimal", "getBytes" -> value;
                case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                case "getBoolean" -> value != null && (Boolean) value;
                case "getDouble" -> value == null ? 0d : ((Number) value).doubleValue();
                case "getFloat" -> value == null ? 0f : ((Number) value).floatValue();
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }
}
