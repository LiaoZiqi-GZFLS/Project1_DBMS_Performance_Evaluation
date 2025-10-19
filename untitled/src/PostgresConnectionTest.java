import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class PostgresConnectionTest {
    // PostgreSQL默认数据库连接参数（请根据实际环境修改）
    private static final String DB_URL = "jdbc:postgresql://127.0.0.1:5430/postgres"; // 默认数据库名为postgres
    private static final String USER = "test"; // 默认用户名
    private static final String PASS = "123456"; // 替换为你的数据库密码

    public static void main(String[] args) {
        Connection conn = null;
        Statement stmt = null;
        try {
            // 1. 加载PostgreSQL JDBC驱动（新版JDBC可自动加载，此行可省略，但保留更兼容）
            Class.forName("org.postgresql.Driver");

            // 2. 尝试建立连接
            System.out.println("正在连接PostgreSQL默认数据库...");
            conn = DriverManager.getConnection(DB_URL, USER, PASS);

            // 3. 连接成功后，执行一个简单查询验证（例如查询数据库版本）
            stmt = conn.createStatement();
            String sql = "SELECT version();"; // 查询PostgreSQL版本的SQL
            ResultSet rs = stmt.executeQuery(sql);

            // 4. 输出查询结果（验证连接有效性）
            if (rs.next()) {
                System.out.println("连接成功！数据库版本：" + rs.getString(1));
            }

            // 关闭资源
            rs.close();
            stmt.close();
            conn.close();

        } catch (Exception e) {
            // 连接失败时输出错误信息
            System.err.println("连接失败！错误原因：" + e.getMessage());
            e.printStackTrace();
        } finally {
            // 确保资源被关闭
            try {
                if (stmt != null) stmt.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                if (conn != null) conn.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}