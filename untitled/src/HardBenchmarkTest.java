/* HardBenchmarkTest.java
   Java multi-thread benchmark for QPS / TPS on PostgreSQL and openGauss.

   Usage:
     - put JDBC jars on classpath
     - compile:
         javac -cp ".:postgresql-42.7.3.jar:opengauss-jdbc.jar" HardBenchmarkTest.java
     - run:
         java -cp ".:postgresql-42.7.3.jar:opengauss-jdbc.jar" HardBenchmarkTest

   Outputs CSV into result/
*/

import java.sql.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class HardBenchmarkTest {

    // === DB configs (change to your credentials) ===
    static final String PG_DRIVER = "org.postgresql.Driver";
    static final String PG_URL = "jdbc:postgresql://127.0.0.1:5430/postgres";
    static final String PG_USER = "test";
    static final String PG_PASS = "123456";

    static final String OG_DRIVER = "org.opengauss.Driver";
    static final String OG_URL = "jdbc:opengauss://127.0.0.1:5431/postgres";
    static final String OG_USER = "gaussdb";
    static final String OG_PASS = "123456Aa@";

    // output
    static final File RESULT_DIR = new File("result");
    static final String SUMMARY_CSV = "result/qps_tps_summary.csv";
    static final String THREADS_CSV = "result/qps_tps_threads.csv";

    // test params (tune)
    static final int[] THREADS = {1, 8, 16, 32};
    static final int OPS_PER_THREAD = 200; // number of ops per thread (reduce for small datasets)
    static final String POINT_SELECT_SQL = "SELECT movieid FROM movies WHERE movieid = ?"; // index-based point select recommended
    static final String SAMPLE_UPDATE_SQL = "UPDATE people SET first_name = first_name WHERE peopleid = ?"; // trivial update to measure tx cost

    public static void main(String[] args) throws Exception {
        if (!RESULT_DIR.exists()) RESULT_DIR.mkdirs();

        List<String[]> summaryRows = new ArrayList<>();
        List<String[]> threadRows  = new ArrayList<>();

        // 1) run for both DBs and both modes
        String[] dbs = {"PostgreSQL", "openGauss"};
        for (String db : dbs) {
            for (int t : THREADS) {
                System.out.printf("Running %s point_select threads=%d ...%n", db, t);
                MultiResult mr = runWorkload(db, "point_select", t, OPS_PER_THREAD);
                summaryRows.add(mr.summaryRow());
                threadRows.addAll(mr.threadRows);

                System.out.printf("Running %s simple_tx threads=%d ...%n", db, t);
                mr = runWorkload(db, "simple_tx", t, OPS_PER_THREAD);
                summaryRows.add(mr.summaryRow());
                threadRows.addAll(mr.threadRows);
            }
        }

        // 2) write CSVs
        writeSummaryCsv(SUMMARY_CSV, summaryRows);
        writeThreadsCsv(THREADS_CSV, threadRows);

        System.out.println("Done. Results in " + RESULT_DIR.getAbsolutePath());
    }

    // MultiResult container
    static class MultiResult {
        String db;
        String mode;
        int threads;
        long totalOps;
        double totalMs;
        double qps; // ops/sec
        double avgMs, p95, p99, max;
        List<String[]> threadRows = new ArrayList<>();

        String[] summaryRow() {
            return new String[]{db, mode, Integer.toString(threads), Long.toString(totalOps),
                    String.format("%.2f", totalMs), String.format("%.2f", qps),
                    String.format("%.3f", avgMs), String.format("%.3f", p95), String.format("%.3f", p99), String.format("%.3f", max)};
        }
    }

    // run workload wrapper
    static MultiResult runWorkload(String db, String mode, int threads, int opsPerThread) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ThreadStat>> futures = new ArrayList<>();

        for (int i=0;i<threads;i++) {
            final int threadId = i+1;
            if (db.equals("PostgreSQL")) {
                if (mode.equals("point_select"))
                    futures.add(pool.submit(() -> dbPointSelectWorker(threadId, PG_DRIVER, PG_URL, PG_USER, PG_PASS, opsPerThread)));
                else
                    futures.add(pool.submit(() -> dbSimpleTxWorker(threadId, PG_DRIVER, PG_URL, PG_USER, PG_PASS, opsPerThread)));
            } else {
                if (mode.equals("point_select"))
                    futures.add(pool.submit(() -> dbPointSelectWorker(threadId, OG_DRIVER, OG_URL, OG_USER, OG_PASS, opsPerThread)));
                else
                    futures.add(pool.submit(() -> dbSimpleTxWorker(threadId, OG_DRIVER, OG_URL, OG_USER, OG_PASS, opsPerThread)));
            }
        }

        long started = System.nanoTime();
        List<ThreadStat> stats = new ArrayList<>();
        for (Future<ThreadStat> f : futures) {
            try { stats.add(f.get()); } catch (Exception e) { e.printStackTrace(); }
        }
        long ended = System.nanoTime();
        pool.shutdown();

        // aggregate
        MultiResult mr = new MultiResult();
        mr.db = db; mr.mode = mode; mr.threads = threads;
        mr.totalOps = stats.stream().mapToLong(s->s.ops).sum();
        mr.totalMs = (ended - started) / 1_000_000.0;
        mr.qps = mr.totalOps / (mr.totalMs / 1000.0);
        // weighted avg
        double weightedSum=0; long totalOps=0; double globalMax=0;
        List<Double> p95s = new ArrayList<>(), p99s = new ArrayList<>();
        for (ThreadStat ts: stats) {
            weightedSum += ts.avgMs * ts.ops;
            totalOps += ts.ops;
            if (ts.maxMs > globalMax) globalMax = ts.maxMs;
            p95s.add(ts.p95);
            p99s.add(ts.p99);
            // thread row
            mr.threadRows.add(new String[]{db, mode, Integer.toString(threads), Integer.toString(ts.threadId),
                    Long.toString(ts.ops), String.format("%.3f", ts.totalMs), String.format("%.3f", ts.avgMs),
                    String.format("%.3f", ts.p95), String.format("%.3f", ts.p99), String.format("%.3f", ts.maxMs)});
        }
        mr.avgMs = totalOps>0 ? weightedSum/totalOps : 0;
        // conservative global p95/p99: take max of per-thread percentiles
        mr.p95 = p95s.stream().mapToDouble(d->d).max().orElse(0.0);
        mr.p99 = p99s.stream().mapToDouble(d->d).max().orElse(0.0);
        mr.max = globalMax;

        System.out.printf("Summary %s %s threads=%d ops=%d totalMs=%.2f qps=%.2f avgMs=%.3f p95≈%.3f p99≈%.3f max=%.3f%n",
                db, mode, threads, mr.totalOps, mr.totalMs, mr.qps, mr.avgMs, mr.p95, mr.p99, mr.max);

        return mr;
    }

    // ThreadStat
    static class ThreadStat {
        int threadId;
        long ops;
        double totalMs;
        double avgMs;
        double p95;
        double p99;
        double maxMs;
    }

    // db point select worker: uses POINT_SELECT_SQL with random id
    static ThreadStat dbPointSelectWorker(int threadId, String driver, String url, String user, String pass, int ops) {
        ThreadStat ts = new ThreadStat(); ts.threadId=threadId; ts.ops=0;
        List<Double> lat = new ArrayList<>();
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) { System.err.println("Driver not found: "+e.getMessage()); return ts; }
        try (Connection conn = DriverManager.getConnection(url,user,pass);
             PreparedStatement ps = conn.prepareStatement(POINT_SELECT_SQL)) {
            Random rnd = new Random();
            long t0 = System.nanoTime();
            for (int i=0;i<ops;i++) {
                int id = Math.max(1, rnd.nextInt(10000)); // adjust ID range to your dataset
                ps.setInt(1, id);
                long s = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    while(rs.next()){}
                } catch (SQLException ee){ System.err.println("Query error: "+ee.getMessage()); }
                long e = System.nanoTime();
                double ms = (e-s)/1_000_000.0;
                lat.add(ms);
                ts.ops++;
            }
            long t1 = System.nanoTime();
            ts.totalMs = (t1-t0)/1_000_000.0;
            computeStats(ts, lat);
        } catch (SQLException e) { System.err.println("DB worker error: "+e.getMessage()); }
        return ts;
    }

    // db simple transaction worker (BEGIN; UPDATE; COMMIT)
    static ThreadStat dbSimpleTxWorker(int threadId, String driver, String url, String user, String pass, int ops) {
        ThreadStat ts = new ThreadStat(); ts.threadId=threadId; ts.ops=0;
        List<Double> lat = new ArrayList<>();
        try { Class.forName(driver); } catch (ClassNotFoundException e){ System.err.println(e.getMessage()); return ts; }
        try (Connection conn = DriverManager.getConnection(url,user,pass);
             PreparedStatement ps = conn.prepareStatement(SAMPLE_UPDATE_SQL)) {
            conn.setAutoCommit(false);
            Random rnd = new Random();
            long t0 = System.nanoTime();
            for (int i=0;i<ops;i++) {
                int id = Math.max(1, rnd.nextInt(10000));
                ps.setInt(1, id);
                long s = System.nanoTime();
                try {
                    ps.executeUpdate();
                    conn.commit();
                } catch (SQLException ee) {
                    System.err.println("Tx error: "+ee.getMessage());
                    try { conn.rollback(); } catch(SQLException ex){ }
                }
                long e = System.nanoTime();
                double ms = (e-s)/1_000_000.0;
                lat.add(ms);
                ts.ops++;
            }
            long t1 = System.nanoTime();
            ts.totalMs = (t1-t0)/1_000_000.0;
            computeStats(ts, lat);
        } catch (SQLException e) { System.err.println("DB tx worker error: "+e.getMessage()); }
        return ts;
    }

    static void computeStats(ThreadStat ts, List<Double> lat) {
        ts.avgMs = lat.stream().mapToDouble(d->d).average().orElse(0.0);
        ts.maxMs = lat.stream().mapToDouble(d->d).max().orElse(0.0);
        ts.p95 = percentile(lat,95);
        ts.p99 = percentile(lat,99);
    }

    static double percentile(List<Double> arr, double p){
        if (arr == null || arr.isEmpty()) return 0.0;
        double[] a = arr.stream().mapToDouble(d->d).sorted().toArray();
        double rank = p/100.0*(a.length-1);
        int lo=(int)Math.floor(rank), hi=(int)Math.ceil(rank);
        if (hi==lo) return a[lo];
        double w = rank-lo;
        return a[lo]*(1-w)+a[hi]*w;
    }

    // CSV writers
    static void writeSummaryCsv(String file, List<String[]> rows) {
        try (PrintWriter pw=new PrintWriter(new FileWriter(file))) {
            pw.println("DBMS,Mode,Threads,TotalOps,TotalTime_ms,QPS,Avg_ms,P95_ms,P99_ms,Max_ms");
            for (String[] r: rows) {
                pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n", r[0],r[1],r[2],r[3],r[4],r[5],r[6],r[7],r[8],r[9]);
            }
            System.out.println("Saved summary CSV: "+file);
        } catch (IOException e){ System.err.println("CSV write error: "+e.getMessage()); }
    }

    static void writeThreadsCsv(String file, List<String[]> rows) {
        try (PrintWriter pw=new PrintWriter(new FileWriter(file))) {
            pw.println("DBMS,Mode,Threads,ThreadId,Ops,TotalMs,AvgMs,P95ms,P99ms,MaxMs");
            for (String[] r: rows) pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n", r[0],r[1],r[2],r[3],r[4],r[5],r[6],r[7],r[8],r[9]);
            System.out.println("Saved threads CSV: "+file);
        } catch (IOException e){ System.err.println("CSV write error: "+e.getMessage()); }
    }
}
