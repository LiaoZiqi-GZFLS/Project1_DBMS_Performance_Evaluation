import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class ExportTableToCsv {

    // 沿用你已有的连接参数
    private static final String DB_URL = "jdbc:postgresql://127.0.0.1:5430/postgres";
    private static final String USER   = "test";
    private static final String PASS   = "123456";

    // 本地保存路径
    private static final String LOCAL_FILE = "SQL\\people.txt";

    public static void main(String[] args) {
        String sql = "SELECT * from people;";   // 查询整张表
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql);
             BufferedWriter bw = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(LOCAL_FILE), StandardCharsets.UTF_8))) {

            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();

            /* ---------- 1. 写表头 ---------- */
            for (int i = 1; i <= cols; i++) {
                bw.write(meta.getColumnLabel(i));
                if (i < cols) bw.write(';');
            }
            bw.newLine();

            /* ---------- 2. 写数据 ---------- */
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    String val = rs.getString(i);
                    if (val == null) val = "";          // 处理 NULL
                    // 如果值里本来就有分号或引号，可按 CSV 规则再包一层引号，这里简单示例省略
                    bw.write(val);
                    if (i < cols) bw.write(';');
                }
                bw.newLine();
            }

            System.out.println("导出完成！文件已保存到：" + LOCAL_FILE);

        } catch (Exception e) {
            System.err.println("导出失败：" + e.getMessage());
            e.printStackTrace();
        }
    }
}