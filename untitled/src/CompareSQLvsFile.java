import java.sql.*;
import java.io.*;
import java.time.*;
import java.util.*;

public class CompareSQLvsFile {

    // === PostgreSQL 连接配置 ===
    static final String PG_DRIVER = "org.postgresql.Driver";
    static final String PG_URL = "jdbc:postgresql://127.0.0.1:5430/postgres";
    static final String PG_USER = "test";
    static final String PG_PASS = "123456";

    // === openGauss 连接配置 ===
    static final String OG_DRIVER = "org.opengauss.Driver";
    static final String OG_URL = "jdbc:opengauss://127.0.0.1:5431/postgres";
    static final String OG_USER = "gaussdb";
    static final String OG_PASS = "123456Aa@";

    // === 文件路径 ===
    static final String MOVIE_FILE = "SQL\\movies.txt";
    static final String PEOPLE_FILE = "SQL\\people.txt";

    // === CSV 文件名 ===
    static final String SELECT_CSV = "result\\select_results.csv";
    static final String UPDATE_CSV = "result\\update_results.csv";

    public static void main(String[] args) {

        System.out.println("=== DBMS vs File Performance Comparison ===");
        System.out.println("Dataset: movies.txt (movieid;title;country;year_released;runtime)\n");
        System.out.println("Dataset: people.txt (peopleid;first_name;surname;born;died;gender)\n");

        String keyword = "war";
        String oldStr = " To ";
        String newStr = " TTOO ";

        // CSV 结果容器
        List<Double> pgSelect = new ArrayList<>();
        List<Double> ogSelect = new ArrayList<>();
        List<Double> fileSelect = new ArrayList<>();

        List<Double> pgUpdate = new ArrayList<>();
        List<Double> ogUpdate = new ArrayList<>();
        List<Double> fileUpdate = new ArrayList<>();

        // ===== 检索对比 =====
        pgSelect.addAll(testSelect("PostgreSQL", PG_DRIVER, PG_URL, PG_USER, PG_PASS, keyword));
        ogSelect.addAll(testSelect("openGauss", OG_DRIVER, OG_URL, OG_USER, OG_PASS, keyword));
        fileSelect.addAll(testFileSelect(keyword));

        writeCsv(SELECT_CSV, pgSelect, ogSelect, fileSelect);

        // ===== 更新对比 =====
        pgUpdate.addAll(testUpdate("PostgreSQL", PG_DRIVER, PG_URL, PG_USER, PG_PASS, oldStr, newStr));
        ogUpdate.addAll(testUpdate("openGauss", OG_DRIVER, OG_URL, OG_USER, OG_PASS, oldStr, newStr));
        fileUpdate.addAll(testFileUpdate(oldStr, newStr));

        writeCsv(UPDATE_CSV, pgUpdate, ogUpdate, fileUpdate);
    }

    // ======================== 检索测试 ==========================
    static List<Double> testSelect(String name, String driver, String url, String user, String pass, String keyword) {
        System.out.println(">>> [" + name + "] SELECT test (10 runs, title LIKE '%" + keyword + "%')");

        List<Double> times = new ArrayList<>();
        int totalCount = 0;

        try {
            Class.forName(driver);
            try (Connection conn = DriverManager.getConnection(url, user, pass);
                 Statement stmt = conn.createStatement()) {

                String safeKeyword = keyword.replace("'", "''");
                String sql = "SELECT * FROM movies WHERE LOWER(title) LIKE LOWER('%" + safeKeyword + "%')";

                for (int i = 1; i <= 10; i++) {
                    Instant start = Instant.now();
                    ResultSet rs = stmt.executeQuery(sql);

                    int count = 0;
                    while (rs.next()) count++;
                    Instant end = Instant.now();

                    double elapsed = (double) Duration.between(start, end).toMillis();
                    times.add(elapsed);
                    totalCount = count;

                    System.out.printf("Run %2d: %8.2f ms (%d results)%n", i, elapsed, count);
                }
            }
        } catch (Exception e) {
            System.err.println(name + " SELECT failed: " + e.getMessage());
            return times;
        }

        double avg = times.stream().mapToDouble(d -> d).average().orElse(0);
        System.out.printf("Average time for %-10s : %.2f ms%n%n", name, avg);
        return times;
    }

    static List<Double> testFileSelect(String keyword) {
        System.out.println(">>> [File] SELECT test (10 runs, title contains \"" + keyword + "\")");

        List<Double> times = new ArrayList<>();
        int totalCount = 0;

        try {
            for (int i = 1; i <= 10; i++) {
                Instant start = Instant.now();
                int count = 0;

                try (BufferedReader br = new BufferedReader(new FileReader(MOVIE_FILE))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.split(";");
                        if (parts.length > 1 && parts[1].toLowerCase().contains(keyword.toLowerCase())) {
                            count++;
                        }
                    }
                }

                Instant end = Instant.now();
                double elapsed = (double) Duration.between(start, end).toMillis();
                times.add(elapsed);
                totalCount = count;

                System.out.printf("Run %2d: %8.2f ms (%d results)%n", i, elapsed, count);
            }
        } catch (IOException e) {
            System.err.println("File SELECT failed: " + e.getMessage());
        }

        double avg = times.stream().mapToDouble(d -> d).average().orElse(0);
        System.out.printf("Average time for File      : %.2f ms%n%n", avg);
        return times;
    }

    // ======================== 更新测试 ==========================
    static List<Double> testUpdate(String name, String driver, String url, String user, String pass,
                                   String oldStr, String newStr) {
        System.out.println(">>> [" + name + "] UPDATE test (10 runs, replace '" + oldStr + "' -> '" + newStr + "')");

        List<Double> times = new ArrayList<>();
        int affectedRows = 0;

        try {
            Class.forName(driver);
            for (int i = 1; i <= 10; i++) {
                try (Connection conn = DriverManager.getConnection(url, user, pass);
                     PreparedStatement ps = conn.prepareStatement(
                             "UPDATE people SET first_name = REPLACE(first_name, ?, ?)")) {

                    conn.setAutoCommit(false);
                    ps.setString(1, oldStr.trim());
                    ps.setString(2, newStr.trim());

                    Instant start = Instant.now();
                    affectedRows = ps.executeUpdate();
                    Instant end = Instant.now();

                    double elapsed = (double) Duration.between(start, end).toMillis();
                    times.add(elapsed);

                    conn.rollback(); // 自动回滚

                    System.out.printf("Run %2d: %8.2f ms (%d rows)%n", i, elapsed, affectedRows);
                }
            }
        } catch (Exception e) {
            System.err.println(name + " UPDATE failed: " + e.getMessage());
        }

        double avg = times.stream().mapToDouble(d -> d).average().orElse(0);
        System.out.printf("Average time for %-10s : %.2f ms%n", name, avg);
        System.out.println("    ↳ All changes rolled back, database restored.\n");
        return times;
    }

    static List<Double> testFileUpdate(String oldStr, String newStr) {
        System.out.println(">>> [File] UPDATE test (10 runs, replace \"" + oldStr + "\" -> \"" + newStr + "\")");

        List<Double> times = new ArrayList<>();
        int lines = 0;

        try {
            for (int i = 1; i <= 10; i++) {
                File inputFile = new File(PEOPLE_FILE);
                File outputFile = new File("people_updated.txt");

                Instant start = Instant.now();

                try (BufferedReader br = new BufferedReader(new FileReader(inputFile));
                     BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile))) {
                    String line;
                    lines = 0;
                    while ((line = br.readLine()) != null) {
                        String newLine = line.replace(oldStr, newStr);
                        bw.write(newLine);
                        bw.newLine();
                        lines++;
                    }
                }

                Instant end = Instant.now();
                double elapsed = (double) Duration.between(start, end).toMillis();
                times.add(elapsed);

                System.out.printf("Run %2d: %8.2f ms (%d lines)%n", i, elapsed, lines);
            }
        } catch (IOException e) {
            System.err.println("File UPDATE failed: " + e.getMessage());
        }

        double avg = times.stream().mapToDouble(d -> d).average().orElse(0);
        System.out.printf("Average time for File      : %.2f ms%n%n", avg);
        return times;
    }

    // ======================== CSV 导出功能 ==========================
    static void writeCsv(String filename, List<Double> pg, List<Double> og, List<Double> file) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("Run,PostgreSQL(ms),openGauss(ms),File(ms)");
            int runs = Math.max(pg.size(), Math.max(og.size(), file.size()));
            for (int i = 0; i < runs; i++) {
                String line = String.format("%d,%.2f,%.2f,%.2f",
                        i + 1,
                        i < pg.size() ? pg.get(i) : 0,
                        i < og.size() ? og.get(i) : 0,
                        i < file.size() ? file.get(i) : 0);
                pw.println(line);
            }

            // 平均值行
            double avgPg = pg.stream().mapToDouble(d -> d).average().orElse(0);
            double avgOg = og.stream().mapToDouble(d -> d).average().orElse(0);
            double avgFile = file.stream().mapToDouble(d -> d).average().orElse(0);
            pw.printf("Average,%.2f,%.2f,%.2f%n", avgPg, avgOg, avgFile);
        } catch (IOException e) {
            System.err.println("CSV write failed: " + e.getMessage());
        }
        System.out.println("📊 Results saved to " + filename + "\n");
    }
}
