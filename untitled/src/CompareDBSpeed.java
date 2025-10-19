import java.sql.*;
import java.time.*;

public class CompareDBSpeed {

    // PostgreSQL配置
    static final String PG_DRIVER = "org.postgresql.Driver";
    static final String PG_URL = "jdbc:postgresql://127.0.0.1:5430/postgres";
    static final String PG_USER = "test";
    static final String PG_PASS = "123456";

    // openGauss配置
    static final String OG_DRIVER = "org.opengauss.Driver";
    static final String OG_URL = "jdbc:opengauss://127.0.0.1:5431/postgres";
    static final String OG_USER = "gaussdb";
    static final String OG_PASS = "123456Aa@";

    // 测试SQL（可修改）
    static final String SQL = """
        SELECT * FROM movies WHERE title LIKE ('%war%');
""";

    public static void main(String[] args) {
        System.out.println("=== PostgreSQL vs openGauss Performance Test ===\n");

        testFixedTime("PostgreSQL", PG_DRIVER, PG_URL, PG_USER, PG_PASS, 10);
        testFixedTime("openGauss", OG_DRIVER, OG_URL, OG_USER, OG_PASS, 10);
    }

    /** 固定时间测试：在duration秒内执行尽可能多的查询 */
    static void testFixedTime(String name, String driver, String url,
                              String user, String pass, int durationSec) {
        System.out.println(">>> Testing " + name + " for " + durationSec + " seconds...");

        try {
            // 加载驱动
            Class.forName(driver);

            try (Connection conn = DriverManager.getConnection(url, user, pass);
                 Statement stmt = conn.createStatement()) {

                int count = 0;
                Instant start = Instant.now();

                // 持续执行查询直到时间到
                while (Duration.between(start, Instant.now()).toSeconds() < durationSec) {
                    try (ResultSet rs = stmt.executeQuery(SQL)) {
                        while (rs.next()) {} // 遍历结果集，不输出
                    }
                    count++;
                }

                Instant end = Instant.now();
                double elapsed = Duration.between(start, end).toMillis() / 1000.0;

                System.out.printf("%-12s: %5d queries in %.1f s => %.2f QPS%n%n",
                        name, count, elapsed, count / elapsed);

            }
        } catch (Exception e) {
            System.err.println(name + " failed: " + e.getMessage());
        }
    }
}
