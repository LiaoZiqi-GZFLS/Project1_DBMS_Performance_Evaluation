import java.sql.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * ComparePerformance
 *
 * Tests:
 *  1) Single-thread SELECT LIKE '%keyword%' (10 runs) for PostgreSQL/openGauss/File
 *  2) Multi-thread SELECT (threads configurable) -- each thread performs queriesPerThread queries
 *  3) Batch INSERT (numRows) using transaction + rollback (measures execution time), File append test
 *
 * Outputs CSV files in "result/" folder:
 *  - select_single.csv
 *  - select_multi.csv
 *  - insert_batch.csv
 *
 * Adjust DB configs and file paths at top of file.
 */
public class ComparePerformance {

    // === PostgreSQL 连接配置 ===
    static final String PG_DRIVER = "org.postgresql.Driver";
    static final String PG_URL = "jdbc:postgresql://127.0.0.1:5430/postgres"; // 改为你的DB
    static final String PG_USER = "test";
    static final String PG_PASS = "123456";

    // === openGauss 连接配置 ===
    static final String OG_DRIVER = "org.opengauss.Driver";
    static final String OG_URL = "jdbc:opengauss://127.0.0.1:5431/postgres"; // 改为你的DB
    static final String OG_USER = "gaussdb";
    static final String OG_PASS = "123456Aa@";

    // === 本地文件路径（用于 File 比较） ===
    static final String MOVIE_FILE = "SQL/movies.txt";    // 请保证文件存在
    static final String INSERT_FILE = "result/file_insert_append.txt"; // 临时文件用于 append 测试

    // === 结果目录与文件 ===
    static final File RESULT_DIR = new File("result");
    static final String SELECT_SINGLE_CSV = "result/select_single.csv";
    static final String SELECT_MULTI_CSV  = "result/select_multi.csv";
    static final String INSERT_BATCH_CSV  = "result/insert_batch.csv";

    // default settings
    static final int SINGLE_RUNS = 10;
    static final int[] THREADS = {1, 8, 16, 32}; // 可按需修改
    static final int QUERIES_PER_THREAD = 1000;  // 每线程执行的查询次数（multi-thread 测试）
    static final int INSERT_NUM_ROWS = 1000;     // 批量插入条数
    static final int INSERT_BATCH_SIZE = 100;    // JDBC batch size

    public static void main(String[] args) throws Exception {
        ensureResultDir();

        String keyword = "war";       // SELECT 使用的关键字
        System.out.println("=== ComparePerformance ===");
        System.out.println("Make sure JDBC drivers are on classpath (Postgres + openGauss).");
        System.out.println();

        // 1) Single-thread SELECT (10 runs)
        System.out.println("-> Running single-thread SELECT tests...");
        List<Double> pgSingle = runSelectSingle("PostgreSQL", PG_DRIVER, PG_URL, PG_USER, PG_PASS, keyword, SINGLE_RUNS);
        List<Double> ogSingle = runSelectSingle("openGauss", OG_DRIVER, OG_URL, OG_USER, OG_PASS, keyword, SINGLE_RUNS);
        List<Double> fileSingle = runSelectSingleFile(keyword, SINGLE_RUNS);
        writeSelectSingleCsv(SELECT_SINGLE_CSV, pgSingle, ogSingle, fileSingle);

        // 2) Multi-thread SELECT
        System.out.println("-> Running multi-thread SELECT tests...");
        List<String[]> multiRows = runSelectMulti(THREADS, QUERIES_PER_THREAD, keyword);
        writeSelectMultiCsv(SELECT_MULTI_CSV, multiRows);

        // 3) Batch INSERT
        System.out.println("-> Running batch INSERT tests (measured then rolled back)...");
        String insertTable = "perf_test_insert"; // 临时表，建议先在两个DB创建相同结构
        prepareInsertTable(PG_URL, PG_USER, PG_PASS, "PostgreSQL", insertTable);
        prepareInsertTable(OG_URL, OG_USER, OG_PASS, "openGauss", insertTable);

        // run insert tests
        Map<String, Double> pgInsert = runInsertBatch("PostgreSQL", PG_DRIVER, PG_URL, PG_USER, PG_PASS, insertTable, INSERT_NUM_ROWS, INSERT_BATCH_SIZE);
        Map<String, Double> ogInsert = runInsertBatch("openGauss", OG_DRIVER, OG_URL, OG_USER, OG_PASS, insertTable, INSERT_NUM_ROWS, INSERT_BATCH_SIZE);
        double fileInsertMs = runFileAppendInsert(INSERT_NUM_ROWS);

        writeInsertCsv(INSERT_BATCH_CSV, pgInsert, ogInsert, fileInsertMs);

        System.out.println("\nAll tests complete. CSV results in: " + RESULT_DIR.getAbsolutePath());
        System.out.println("Files: " + SELECT_SINGLE_CSV + ", " + SELECT_MULTI_CSV + ", " + INSERT_BATCH_CSV);
    }

    // ---------- Helpers ----------

    static void ensureResultDir() {
        if (!RESULT_DIR.exists()) {
            if (!RESULT_DIR.mkdirs()) {
                System.err.println("Cannot create result directory: " + RESULT_DIR.getAbsolutePath());
            }
        }
    }

    // --------------- SELECT single-thread (DB) ---------------
    static List<Double> runSelectSingle(String name, String driver, String url, String user, String pass, String keyword, int runs) {
        System.out.println("[" + name + "] single select: keyword='" + keyword + "', runs=" + runs);
        List<Double> times = new ArrayList<>();
        String safeKeyword = keyword.replace("'", "''");
        String sql = "SELECT * FROM movies WHERE LOWER(title) LIKE LOWER('%" + safeKeyword + "%')";

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            System.err.println(name + " driver class not found: " + e.getMessage());
            return times;
        }

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {

            for (int i = 1; i <= runs; i++) {
                long t0 = System.nanoTime();
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    int count = 0;
                    while (rs.next()) count++;
                    long t1 = System.nanoTime();
                    double ms = (t1 - t0) / 1_000_000.0;
                    times.add(ms);
                    System.out.printf("%s Run %2d: %.2f ms (%d rows)%n", name, i, ms, count);
                }
            }
            double avg = times.stream().mapToDouble(d->d).average().orElse(0.0);
            System.out.printf("Average %s single select: %.2f ms%n%n", name, avg);
        } catch (SQLException e) {
            System.err.println(name + " SELECT failed: " + e.getMessage());
        }
        return times;
    }

    // --------------- SELECT single-thread (File) ---------------
    static List<Double> runSelectSingleFile(String keyword, int runs) {
        System.out.println("[File] single select: keyword='" + keyword + "', runs=" + runs);
        List<Double> times = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            long t0 = System.nanoTime();
            int count = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(MOVIE_FILE))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(";");
                    if (parts.length > 1 && parts[1].toLowerCase().contains(keyword.toLowerCase())) count++;
                }
            } catch (IOException e) {
                System.err.println("File SELECT failed: " + e.getMessage());
                break;
            }
            long t1 = System.nanoTime();
            double ms = (t1 - t0) / 1_000_000.0;
            times.add(ms);
            System.out.printf("File  Run %2d: %.2f ms (%d rows)%n", i, ms, count);
        }
        double avg = times.stream().mapToDouble(d->d).average().orElse(0.0);
        System.out.printf("Average File single select: %.2f ms%n%n", avg);
        return times;
    }

    // ---------- write single-select CSV ----------
    static void writeSelectSingleCsv(String filename, List<Double> pg, List<Double> og, List<Double> file) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("Run,PostgreSQL(ms),openGauss(ms),File(ms)");
            int runs = Math.max(pg.size(), Math.max(og.size(), file.size()));
            for (int i = 0; i < runs; i++) {
                pw.printf("%d,%.2f,%.2f,%.2f%n",
                        i + 1,
                        i < pg.size() ? pg.get(i) : 0.0,
                        i < og.size() ? og.get(i) : 0.0,
                        i < file.size() ? file.get(i) : 0.0);
            }
            pw.printf("Average,%.2f,%.2f,%.2f%n",
                    pg.stream().mapToDouble(d->d).average().orElse(0.0),
                    og.stream().mapToDouble(d->d).average().orElse(0.0),
                    file.stream().mapToDouble(d->d).average().orElse(0.0));
            System.out.println("Saved CSV: " + filename);
        } catch (IOException e) {
            System.err.println("CSV write failed: " + e.getMessage());
        }
    }

    // --------------- Multi-thread SELECT ---------------
    /**
     * For each DBMS and file, runs tests for thread counts provided.
     * Each thread will execute queriesPerThread SELECT queries.
     * Returns CSV rows as list of String[]: [DBMS, threads, totalQueries, totalMs, QPS]
     */
    static List<String[]> runSelectMulti(int[] threadsArray, int queriesPerThread, String keyword) {
        List<String[]> rows = new ArrayList<>();
        // For each DBMS and file
        String[] targets = {"PostgreSQL", "openGauss", "File"};
        for (String target : targets) {
            for (int t : threadsArray) {
                System.out.printf("Multi SELECT: target=%s threads=%d queries/thread=%d%n", target, t, queriesPerThread);
                long totalQueries = (long) t * queriesPerThread;
                long startAll = System.nanoTime();

                // thread pool
                ExecutorService pool = Executors.newFixedThreadPool(t);
                List<Future<Long>> futures = new ArrayList<>();

                for (int i = 0; i < t; i++) {
                    if ("File".equals(target)) {
                        futures.add(pool.submit(() -> runSelectFileWorker(keyword, queriesPerThread)));
                    } else if ("PostgreSQL".equals(target)) {
                        futures.add(pool.submit(() -> runSelectDbWorker(PG_DRIVER, PG_URL, PG_USER, PG_PASS, keyword, queriesPerThread)));
                    } else {
                        futures.add(pool.submit(() -> runSelectDbWorker(OG_DRIVER, OG_URL, OG_USER, OG_PASS, keyword, queriesPerThread)));
                    }
                }

                long completedQueries = 0;
                long worstMs = 0;
                for (Future<Long> f : futures) {
                    try {
                        long ms = f.get(); // returns elapsed ms of that worker
                        completedQueries += queriesPerThread;
                        if (ms > worstMs) worstMs = ms;
                    } catch (Exception e) {
                        System.err.println("Worker failed: " + e.getMessage());
                    }
                }
                pool.shutdown();

                long endAll = System.nanoTime();
                double totalMs = (endAll - startAll) / 1_000_000.0;
                double qps = totalQueries / (totalMs / 1000.0);
                System.out.printf("Result %s threads=%d: totalQueries=%d totalMs=%.2f QPS=%.2f%n%n", target, t, totalQueries, totalMs, qps);

                rows.add(new String[]{target, Integer.toString(t), Long.toString(totalQueries), String.format("%.2f", totalMs), String.format("%.2f", qps)});
            }
        }
        return rows;
    }

    // Worker for DB SELECT: opens its own connection and executes queries sequentially
    static long runSelectDbWorker(String driver, String url, String user, String pass, String keyword, int queries) {
        String safeKeyword = keyword.replace("'", "''");
        String sql = "SELECT * FROM movies WHERE LOWER(title) LIKE LOWER('%" + safeKeyword + "%')";
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            System.err.println("Driver not found: " + e.getMessage());
            return 0L;
        }
        long t0 = System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            for (int i = 0; i < queries; i++) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) { /* iterate but do nothing */ }
                }
            }
        } catch (SQLException e) {
            System.err.println("DB worker select failed: " + e.getMessage());
        }
        long t1 = System.nanoTime();
        return (t1 - t0) / 1_000_000; // ms
    }

    // Worker for File SELECT: each worker scans the file 'queries' times (inefficient but comparable)
    static long runSelectFileWorker(String keyword, int queries) {
        long t0 = System.nanoTime();
        for (int i = 0; i < queries; i++) {
            try (BufferedReader br = new BufferedReader(new FileReader(MOVIE_FILE))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(";");
                    if (parts.length > 1 && parts[1].toLowerCase().contains(keyword.toLowerCase())) {
                        // match found; do nothing
                    }
                }
            } catch (IOException e) {
                System.err.println("File worker read failed: " + e.getMessage());
            }
        }
        long t1 = System.nanoTime();
        return (t1 - t0) / 1_000_000; // ms
    }

    static void writeSelectMultiCsv(String filename, List<String[]> rows) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("DBMS,Threads,TotalQueries,TotalTime(ms),QPS");
            for (String[] r : rows) {
                pw.printf("%s,%s,%s,%s,%s%n", r[0], r[1], r[2], r[3], r[4]);
            }
            System.out.println("Saved CSV: " + filename);
        } catch (IOException e) {
            System.err.println("CSV write failed: " + e.getMessage());
        }
    }

    // --------------- Batch INSERT (DB) ---------------
    /**
     * Performs numRows inserts using JDBC batch (with batchSize),
     * in a single transaction and then rolls back (so DB is unchanged).
     * Returns a map with "totalMs" and "avgMsPerRow".
     */
    static Map<String, Double> runInsertBatch(String name, String driver, String url, String user, String pass,
                                              String table, int numRows, int batchSize) {
        Map<String, Double> result = new HashMap<>();
        System.out.printf("[%s] INSERT batch test: rows=%d batchSize=%d (transaction + rollback)%n", name, numRows, batchSize);

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            System.err.println(name + " driver not found: " + e.getMessage());
            return result;
        }

        String insertSql = "INSERT INTO " + table + " (col1, col2) VALUES (?, ?)"; // table needs two columns (varchar/int) or adapt
        long t0 = System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             PreparedStatement ps = conn.prepareStatement(insertSql)) {

            conn.setAutoCommit(false);
            for (int i = 1; i <= numRows; i++) {
                ps.setString(1, "name_" + i);
                ps.setInt(2, i);
                ps.addBatch();
                if (i % batchSize == 0) {
                    ps.executeBatch();
                }
            }
            // flush remaining
            ps.executeBatch();
            long t1 = System.nanoTime();
            double totalMs = (t1 - t0) / 1_000_000.0;
            double perRow = totalMs / numRows;
            result.put("totalMs", totalMs);
            result.put("perRowMs", perRow);

            // rollback to restore DB
            conn.rollback();
            System.out.printf("%s: inserted %d rows in %.2f ms (rolled back)%n%n", name, numRows, totalMs);

        } catch (SQLException e) {
            System.err.println(name + " INSERT failed: " + e.getMessage());
        }
        return result;
    }

    // prepare insert table if not exists (simple two-column table)
    static void prepareInsertTable(String url, String user, String pass, String name, String table) {
        String createSql = "CREATE TABLE IF NOT EXISTS " + table + " (col1 VARCHAR(100), col2 INT)";
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException ignore) {}
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement st = conn.createStatement()) {
            st.execute(createSql);
            System.out.println(name + ": ensured table exists: " + table);
        } catch (SQLException e) {
            System.err.println(name + " prepare table failed: " + e.getMessage());
        }
    }

    // --------------- File append "insert" ---------------
    static double runFileAppendInsert(int numRows) {
        System.out.printf("[File] append test: rows=%d (append to %s then delete)%n", numRows, INSERT_FILE);
        File f = new File(INSERT_FILE);
        long t0 = System.nanoTime();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f, true))) {
            for (int i = 1; i <= numRows; i++) {
                bw.write("name_" + i + "," + i);
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("File append failed: " + e.getMessage());
        }
        long t1 = System.nanoTime();
        double ms = (t1 - t0) / 1_000_000.0;
        // delete appended file to clean up
        try {
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
        System.out.printf("File append: %d rows, %.2f ms%n%n", numRows, ms);
        return ms;
    }

    // --------------- write insert CSV ---------------
    static void writeInsertCsv(String filename, Map<String, Double> pg, Map<String, Double> og, double fileMs) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("DBMS,TotalTime(ms),PerRow(ms)");
            if (pg != null && !pg.isEmpty()) {
                pw.printf("PostgreSQL,%.2f,%.6f%n", pg.getOrDefault("totalMs", 0.0), pg.getOrDefault("perRowMs", 0.0));
            }
            if (og != null && !og.isEmpty()) {
                pw.printf("openGauss,%.2f,%.6f%n", og.getOrDefault("totalMs", 0.0), og.getOrDefault("perRowMs", 0.0));
            }
            pw.printf("File,%.2f,%.6f%n", fileMs, fileMs / Math.max(1, INSERT_NUM_ROWS));
            System.out.println("Saved CSV: " + filename);
        } catch (IOException e) {
            System.err.println("CSV write failed: " + e.getMessage());
        }
    }
}
