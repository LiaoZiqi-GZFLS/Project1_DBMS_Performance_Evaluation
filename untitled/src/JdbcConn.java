import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class JdbcConn {
    public static void main(String[] args) {
        getConnect();
    }
    public static void getConnect() {
        String driver = "org.opengauss.Driver";
        String sourceURL = "jdbc:opengauss://127.0.0.1:5431/postgres";
        String username="gaussdb";
        String passwd="123456Aa@";
        Connection conn = null;
        Statement stmt = null;
        try {
            // 1. 加载openGaussSQL JDBC驱动（新版JDBC可自动加载，此行可省略，但保留更兼容）
            Class.forName(driver);
        } catch (Exception var9) {
            var9.printStackTrace();
        }
        try {
            // 2. 尝试建立连接
            conn = DriverManager.getConnection(sourceURL, username, passwd);
            System.out.println("连接成功！");

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
        } catch (Exception var8) {
            var8.printStackTrace();
        }
        return;
    }
}
