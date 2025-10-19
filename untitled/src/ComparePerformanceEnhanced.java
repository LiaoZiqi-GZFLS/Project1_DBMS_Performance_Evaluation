import java.sql.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * ComparePerformanceEnhanced
 *
 * Purpose: run multi-thread SELECT workload across PostgreSQL, openGauss and local file,
 * and produce per-thread latency distributions + summary statistics (avg, p95, p99, max).
 *
 * Outputs:
 *   result/select_multi_summary.csv   -> per (DBMS, threads) summary
 *   result/select_multi_threads.csv   -> per-thread detailed stats
 *
 * Adjust DB URL / user / pass and file paths at top.
 */
public class ComparePerformanceEnhanced {

    // === DB config (note DB name is "postgres" per your environment) ===
    static final String PG_DRIVER = "org.postgresql.Driver";
    static final String PG_URL = "jdbc:postgresql://127.0.0.1:5430/postgres";
    static final String PG_USER = "test";
    static final String PG_PASS = "123456";

    static final String OG_DRIVER = "org.opengauss.Driver";
    static final String OG_URL = "jdbc:opengauss://127.0.0.1:5431/postgres";
    static final String OG_USER = "gaussdb";
    static final String OG_PASS = "123456Aa@";

    // === file paths and result dir ===
    static final String MOVIE_FILE = "SQL/movies.txt";
    static final File RESULT_DIR = new File("result");
    static final String SUMMARY_CSV = "result/select_multi_summary.csv";
    static final String THREADS_CSV = "result/select_multi_threads.csv";

    // === test settings (tune as needed) ===
    static final int[] THREADS = {1, 8, 16, 32};
    static final int QUERIES_PER_THREAD = 200; // per-thread query count (reduce if needed)
    static final String KEYWORD = "war";

    public static void main(String[] args) throws Exception {
        ensureResultDir();

        System.out.println("=== ComparePerformanceEnhanced ===");
        System.out.println("Targets: PostgreSQL (port 5430), openGauss (port 5431), File");
        System.out.println("DB name: postgres");
        System.out.println();

        List<String[]> summaryRows = new ArrayList<>();
        List<String[]> threadRows  = new ArrayList<>();

        // Order: PostgreSQL, openGauss, File
        String[] targets = {"PostgreSQL", "openGauss", "File"};
        for (String target : targets) {
            for (int t : THREADS) {
                System.out.printf("Running target=%s threads=%d queriesPerThread=%d%n", target, t, QUERIES_PER_THREAD);
                // run test
                MultiRunResult res = runMultiSelect(target, t, QUERIES_PER_THREAD, KEYWORD);
                // add summary row
                summaryRows.add(new String[]{
                        target,
                        Integer.toString(t),
                        Long.toString(res.totalQueries),
                        String.format("%.2f", res.totalTimeMs),
                        String.format("%.2f", res.qps),
                        String.format("%.3f", res.avgMs),
                        String.format("%.3f", res.p95Ms),
                        String.format("%.3f", res.p99Ms),
                        String.format("%.3f", res.maxMs)
                });
                // add per-thread rows
                for (ThreadStat ts : res.threadStats) {
                    threadRows.add(new String[]{
                            target,
                            Integer.toString(t),
                            Integer.toString(ts.threadId),
                            Long.toString(ts.queries),
                            String.format("%.3f", ts.totalMs),
                            String.format("%.3f", ts.avgMs),
                            String.format("%.3f", ts.p95Ms),
                            String.format("%.3f", ts.p99Ms),
                            String.format("%.3f", ts.maxMs)
                    });
                }
            }
        }

        writeCsvSummary(SUMMARY_CSV, summaryRows);
        writeCsvThreads(THREADS_CSV, threadRows);

        System.out.println("Done. CSV files written to " + RESULT_DIR.getAbsolutePath());
        System.out.println("  - " + SUMMARY_CSV);
        System.out.println("  - " + THREADS_CSV);
    }

    // ensure result dir
    static void ensureResultDir() {
        if (!RESULT_DIR.exists()) {
            if (!RESULT_DIR.mkdirs()) {
                System.err.println("Failed to create result dir: " + RESULT_DIR.getAbsolutePath());
            }
        }
    }

    // MultiRunResult container
    static class MultiRunResult {
        long totalQueries;
        double totalTimeMs;
        double qps;
        double avgMs;
        double p95Ms;
        double p99Ms;
        double maxMs;
        List<ThreadStat> threadStats = new ArrayList<>();
    }

    static class ThreadStat {
        int threadId;
        long queries;
        double totalMs;
        double avgMs;
        double p95Ms;
        double p99Ms;
        double maxMs;
    }

    // run multi-thread test for a given target
    static MultiRunResult runMultiSelect(String target, int threads, int queriesPerThread, String keyword) {
        MultiRunResult result = new MultiRunResult();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ThreadStat>> futures = new ArrayList<>();

        long totalQueries = (long) threads * queriesPerThread;
        long globalStart = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            final int threadId = i + 1;
            if ("PostgreSQL".equals(target)) {
                futures.add(pool.submit(() -> dbWorker(threadId, PG_DRIVER, PG_URL, PG_USER, PG_PASS, keyword, queriesPerThread)));
            } else if ("openGauss".equals(target)) {
                futures.add(pool.submit(() -> dbWorker(threadId, OG_DRIVER, OG_URL, OG_USER, OG_PASS, keyword, queriesPerThread)));
            } else {
                futures.add(pool.submit(() -> fileWorker(threadId, MOVIE_FILE, keyword, queriesPerThread)));
            }
        }

        List<Double> allLatencies = new ArrayList<>(threads * queriesPerThread);
        double worstThreadMs = 0.0;

        for (Future<ThreadStat> f : futures) {
            try {
                ThreadStat ts = f.get();
                result.threadStats.add(ts);
                // accumulate latencies: we'll not collect all individual latencies to avoid memory blow up,
                // but we can approximate global stats by combining thread stats via per-thread samples.
                // Here for p95/p99 we recompute from per-thread samples by merging per-thread synthetic lists is heavy;
                // instead we collect per-thread medians? To be accurate, we will collect per-thread lists inside dbWorker/fileWorker
                // and return them — but to avoid too much memory, the worker returns only per-thread stats.
                // We'll approximate global avg by weighted average of thread avgs.
                long q = ts.queries;
                for (int k = 0; k < 0; k++) { } // placeholder
                if (ts.totalMs > worstThreadMs) worstThreadMs = ts.totalMs;
                // For global latencies list, we will not reconstruct per-query list to save memory.
                // Instead compute weighted average and approximate p95/p99 by concatenating per-thread percentiles (approximation).
                // But here we will approximate global avg exactly, and global p95/p99 take max of per-thread p95/p99 (conservative).
                // Collect per-thread avg * count contributions
                for (int j = 0; j < (int) q; j++) {
                    // avoid actually adding thousands of entries -> skip
                }
            } catch (Exception e) {
                System.err.println("Worker future failed: " + e.getMessage());
            }
        }

        long globalEnd = System.nanoTime();
        pool.shutdown();

        // Compute aggregated metrics:
        long totalMsSum = 0;
        long totalQueriesExecuted = 0;
        double weightedSum = 0.0;
        double globalMax = 0.0;
        List<Double> p95candidates = new ArrayList<>();
        List<Double> p99candidates = new ArrayList<>();

        for (ThreadStat ts : result.threadStats) {
            totalQueriesExecuted += ts.queries;
            weightedSum += ts.avgMs * ts.queries;
            if (ts.maxMs > globalMax) globalMax = ts.maxMs;
            p95candidates.add(ts.p95Ms);
            p99candidates.add(ts.p99Ms);
        }

        double totalTimeMs = (globalEnd - globalStart) / 1_000_000.0;
        double qps = totalQueriesExecuted / (totalTimeMs / 1000.0);
        double avgMs = totalQueriesExecuted > 0 ? (weightedSum / totalQueriesExecuted) : 0.0;

        // Conservative approximations for p95/p99: take 95th/99th percentile among per-thread p95/p99 candidates (i.e., high tail)
        double approxP95 = p95candidates.stream().mapToDouble(d->d).max().orElse(0.0);
        double approxP99 = p99candidates.stream().mapToDouble(d->d).max().orElse(0.0);

        result.totalQueries = totalQueriesExecuted;
        result.totalTimeMs = totalTimeMs;
        result.qps = qps;
        result.avgMs = avgMs;
        result.p95Ms = approxP95;
        result.p99Ms = approxP99;
        result.maxMs = globalMax;

        // print summary
        System.out.printf("Summary: target=%s threads=%d totalQueries=%d totalMs=%.2f QPS=%.2f avgMs=%.3f p95≈%.3f p99≈%.3f maxMs=%.3f%n%n",
                target, threads, result.totalQueries, result.totalTimeMs, result.qps, result.avgMs, result.p95Ms, result.p99Ms, result.maxMs);

        return result;
    }

    // DB worker: executes 'queries' SELECTs sequentially, records per-query latencies and compute per-thread stats.
    static ThreadStat dbWorker(int threadId, String driver, String url, String user, String pass, String keyword, int queries) {
        ThreadStat ts = new ThreadStat();
        ts.threadId = threadId;
        ts.queries = queries;
        List<Double> latencies = new ArrayList<>(queries);

        String safeKeyword = keyword.replace("'", "''");
        String sql = "SELECT * FROM movies WHERE LOWER(title) LIKE LOWER('%" + safeKeyword + "%')";

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            System.err.println("Driver not found for worker: " + e.getMessage());
            ts.totalMs = 0; ts.avgMs = ts.p95Ms = ts.p99Ms = ts.maxMs = 0; return ts;
        }

        long t0 = System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {

            for (int i = 0; i < queries; i++) {
                long s = System.nanoTime();
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) { /* iterate */ }
                } catch (SQLException e) {
                    System.err.println("Worker DB query failed: " + e.getMessage());
                }
                long eTime = System.nanoTime();
                double ms = (eTime - s) / 1_000_000.0;
                latencies.add(ms);
            }

        } catch (SQLException e) {
            System.err.println("DB worker connection/query error: " + e.getMessage());
        }
        long t1 = System.nanoTime();

        computeThreadStatsFromLatencies(ts, latencies, (t1 - t0) / 1_000_000.0);
        return ts;
    }

    // File worker: each thread scans the file 'queries' times and records per-query latency
    static ThreadStat fileWorker(int threadId, String movieFile, String keyword, int queries) {
        ThreadStat ts = new ThreadStat();
        ts.threadId = threadId;
        ts.queries = queries;
        List<Double> latencies = new ArrayList<>(queries);

        for (int i = 0; i < queries; i++) {
            long s = System.nanoTime();
            int count = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(movieFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(";");
                    if (parts.length > 1 && parts[1].toLowerCase().contains(keyword.toLowerCase())) count++;
                }
            } catch (IOException e) {
                System.err.println("File worker IO error: " + e.getMessage());
            }
            long e = System.nanoTime();
            double ms = (e - s) / 1_000_000.0;
            latencies.add(ms);
        }
        long totalMs = (long)latencies.stream().mapToDouble(d->d).sum();
        computeThreadStatsFromLatencies(ts, latencies, totalMs);
        return ts;
    }

    // compute per-thread aggregated stats from per-query latencies
    static void computeThreadStatsFromLatencies(ThreadStat ts, List<Double> latencies, double totalMs) {
        ts.totalMs = totalMs;
        ts.avgMs = latencies.stream().mapToDouble(d->d).average().orElse(0.0);
        ts.maxMs = latencies.stream().mapToDouble(d->d).max().orElse(0.0);
        ts.p95Ms = percentile(latencies, 95);
        ts.p99Ms = percentile(latencies, 99);
    }

    // percentile calculation (list of doubles)
    static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) return 0.0;
        int n = values.size();
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = values.get(i);
        Arrays.sort(a);
        double rank = p / 100.0 * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (upper == lower) return a[lower];
        double weight = rank - lower;
        return a[lower] * (1 - weight) + a[upper] * weight;
    }

    // write summary CSV
    static void writeCsvSummary(String filename, List<String[]> rows) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("DBMS,Threads,TotalQueries,TotalTime(ms),QPS,AvgLatency(ms),P95(ms),P99(ms),Max(ms)");
            for (String[] r : rows) {
                pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
            }
            System.out.println("Saved summary CSV: " + filename);
        } catch (IOException e) {
            System.err.println("Failed to write summary CSV: " + e.getMessage());
        }
    }

    // write per-thread CSV
    static void writeCsvThreads(String filename, List<String[]> rows) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("DBMS,Threads,ThreadId,Queries,TotalMs,AvgMs,P95ms,P99ms,MaxMs");
            for (String[] r : rows) {
                pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
            }
            System.out.println("Saved threads CSV: " + filename);
        } catch (IOException e) {
            System.err.println("Failed to write threads CSV: " + e.getMessage());
        }
    }
}
