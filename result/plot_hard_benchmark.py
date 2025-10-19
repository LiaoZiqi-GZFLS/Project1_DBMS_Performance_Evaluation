# plot_qps_tps_results.py
import os
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
from matplotlib import font_manager

# ================== 配置 ==================
SUMMARY_CSV = "qps_tps_summary.csv"
THREADS_CSV = "qps_tps_threads.csv"
OUT_DIR = "result"
# ==========================================

def setup_chinese_font():
    # 尝试几种常见中文字体，按优先级选择
    candidates = ["SimHei", "Noto Sans CJK SC", "Microsoft YaHei", "WenQuanYi Zen Hei", "AR PL UKai CN"]
    for name in candidates:
        try:
            f = font_manager.FontProperties(fname=font_manager.findfont(name, fallback_to_default=False))
            plt.rcParams['font.family'] = f.get_name()
            plt.rcParams['axes.unicode_minus'] = False
            print(f"使用中文字体: {f.get_name()}")
            return
        except Exception:
            pass
    # 尝试通过 common font files (Linux / Windows)
    fallback_files = [
        "/usr/share/fonts/truetype/arphic/ukai.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "C:\\Windows\\Fonts\\simhei.ttf",
        "C:\\Windows\\Fonts\\msyh.ttc"
    ]
    for fp in fallback_files:
        if os.path.exists(fp):
            font_manager.fontManager.addfont(fp)
            prop = font_manager.FontProperties(fname=fp)
            plt.rcParams['font.family'] = prop.get_name()
            plt.rcParams['axes.unicode_minus'] = False
            print(f"使用中文字体文件: {fp}")
            return
    # 最后退回到默认（可能无中文）
    plt.rcParams['axes.unicode_minus'] = False
    print("未找到常见中文字体，使用默认字体（中文可能无法正常显示）")

def ensure_outdir():
    if not os.path.exists(OUT_DIR):
        os.makedirs(OUT_DIR)

def dataframe_table_image(df, out_png, title=None):
    # 保存表格为图片
    fig_w = 12
    fig_h = max(2, 0.4 * (len(df) + 3))
    fig, ax = plt.subplots(figsize=(fig_w, fig_h))
    ax.axis('off')
    table = ax.table(cellText=df.values, colLabels=df.columns, cellLoc='center', loc='center')
    table.auto_set_font_size(False)
    table.set_fontsize(10)
    table.scale(1, 1.2)
    if title:
        plt.title(title, fontsize=14, pad=12)
    plt.tight_layout()
    plt.savefig(out_png, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"已保存表格图片：{out_png}")

def plot_qps_and_latency(summary_df):
    # summary_df columns expected:
    # DBMS,Mode,Threads,TotalOps,TotalTime_ms,QPS,Avg_ms,P95_ms,P99_ms,Max_ms
    summary_df['Threads'] = summary_df['Threads'].astype(int)
    # 我们绘制两张折线图：QPS vs Threads（每个 DBMS/Mode），AvgLatency vs Threads
    modes = summary_df['Mode'].unique()
    for mode in modes:
        dfm = summary_df[summary_df['Mode'] == mode]
        pivot_qps = dfm.pivot(index='Threads', columns='DBMS', values='QPS').sort_index()
        pivot_avg = dfm.pivot(index='Threads', columns='DBMS', values='Avg_ms').sort_index()

        # QPS 折线图
        plt.figure(figsize=(8,5))
        for col in pivot_qps.columns:
            plt.plot(pivot_qps.index, pivot_qps[col], marker='o', label=col)
        plt.xlabel("并发线程数")
        plt.ylabel("QPS (每秒查询数)")
        plt.title(f"模式：{mode} — QPS 随线程数变化")
        plt.grid(alpha=0.3)
        plt.legend()
        out_qps = os.path.join(OUT_DIR, f"qps_qps_line_{mode}.png")
        plt.tight_layout()
        plt.savefig(out_qps, dpi=300)
        plt.close()
        print(f"已保存 QPS 折线图：{out_qps}")

        # Avg Latency 折线图
        plt.figure(figsize=(8,5))
        for col in pivot_avg.columns:
            plt.plot(pivot_avg.index, pivot_avg[col], marker='o', label=col)
        plt.xlabel("并发线程数")
        plt.ylabel("平均延迟 (ms)")
        plt.title(f"模式：{mode} — 平均延迟 随线程数变化")
        plt.grid(alpha=0.3)
        plt.legend()
        out_lat = os.path.join(OUT_DIR, f"avg_latency_line_{mode}.png")
        plt.tight_layout()
        plt.savefig(out_lat, dpi=300)
        plt.close()
        print(f"已保存平均延迟折线图：{out_lat}")

        # 分组柱状图（QPS）
        # grouped bar: x = Threads, groups = DBMS
        labels = pivot_qps.index.astype(str)
        x = np.arange(len(labels))
        width = 0.25
        dbms_list = pivot_qps.columns.tolist()
        plt.figure(figsize=(10,5))
        for idx, db in enumerate(dbms_list):
            vals = pivot_qps[db].values
            plt.bar(x + (idx - len(dbms_list)/2) * width + width/2, vals, width=width, label=db)
        plt.xticks(x, labels)
        plt.xlabel("并发线程数")
        plt.ylabel("QPS")
        plt.title(f"模式：{mode} — QPS 分组柱状图")
        plt.legend()
        plt.grid(axis='y', alpha=0.2)
        out_bar = os.path.join(OUT_DIR, f"qps_grouped_bar_{mode}.png")
        plt.tight_layout()
        plt.savefig(out_bar, dpi=300)
        plt.close()
        print(f"已保存 QPS 分组柱状图：{out_bar}")

def plot_threads_boxplots(threads_df):
    # threads_df columns expected:
    # DBMS,Mode,Threads,ThreadId,Ops,TotalMs,AvgMs,P95ms,P99ms,MaxMs
    threads_df['Threads'] = threads_df['Threads'].astype(int)
    # 对每个 DBMS & Mode 绘制箱型图：不同 Threads 下线程级 AvgMs 的分布
    groups = threads_df.groupby(['DBMS','Mode'])
    for (dbms, mode), group in groups:
        pivot = group.pivot_table(index='ThreadId', columns='Threads', values='AvgMs', aggfunc=list)
        # We need boxplots per Threads: collect lists
        thread_counts = sorted(group['Threads'].unique())
        data = []
        labels = []
        for t in thread_counts:
            vals = group[group['Threads']==t]['AvgMs'].values
            if len(vals) == 0:
                data.append([0.0])
            else:
                data.append(vals)
            labels.append(str(t))
        plt.figure(figsize=(10,6))
        b = plt.boxplot(data, labels=labels, patch_artist=True, medianprops=dict(color='black'))
        plt.xlabel("并发线程数")
        plt.ylabel("线程级平均延迟 AvgMs")
        plt.title(f"{dbms} — 模式：{mode} 的线程级 AvgMs 箱型图")
        # 着色
        for patch in b['boxes']:
            patch.set(facecolor='#AED6F1')
        out_box = os.path.join(OUT_DIR, f"qps_threads_boxplot_{dbms}_{mode}.png")
        plt.grid(axis='y', alpha=0.3)
        plt.tight_layout()
        plt.savefig(out_box, dpi=300)
        plt.close()
        print(f"已保存线程箱型图：{out_box}")

def main():
    setup_chinese_font()
    ensure_outdir()

    # 读取 CSV
    if not os.path.exists(SUMMARY_CSV):
        print(f"找不到 {SUMMARY_CSV}，请先运行 HardBenchmarkTest.java 生成该文件。")
        return
    if not os.path.exists(THREADS_CSV):
        print(f"找不到 {THREADS_CSV}，请先运行 HardBenchmarkTest.java 生成该文件。")
        return

    summary_df = pd.read_csv(SUMMARY_CSV)
    threads_df = pd.read_csv(THREADS_CSV)

    # 保存表格图片
    dataframe_table_image(summary_df, os.path.join(OUT_DIR, "qps_tps_summary_table.png"), title="QPS/TPS 总览（Summary）")
    dataframe_table_image(threads_df, os.path.join(OUT_DIR, "qps_tps_threads_table.png"), title="QPS/TPS 线程级详情（Threads）")

    # 绘图：折线/柱状等
    plot_qps_and_latency(summary_df)
    plot_threads_boxplots(threads_df)

if __name__ == "__main__":
    main()
