import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

plt.rcParams['font.sans-serif'] = ['SimHei']  # 支持中文
plt.rcParams['axes.unicode_minus'] = False

# === 读取 CSV ===
def read_data(filename):
    try:
        df = pd.read_csv(filename)
        df = df[df['Run'] != 'Average']  # 排除平均行
        df['Run'] = df['Run'].astype(int)
        return df
    except Exception as e:
        print(f"❌ 无法读取 {filename}: {e}")
        return None


# === 绘制带均值与误差条的性能图 ===
def plot_performance(df, title, save_name):
    systems = ['PostgreSQL(ms)', 'openGauss(ms)', 'File(ms)']
    colors = ['#1f77b4', '#2ca02c', '#ff7f0e']  # 蓝、绿、橙
    markers = ['o', 's', '^']

    plt.figure(figsize=(8, 5))
    x = df['Run']

    # --- 绘制折线 ---
    for sys, color, marker in zip(systems, colors, markers):
        plt.plot(x, df[sys], marker=marker, linestyle='-', label=sys.replace('(ms)', ''), color=color, linewidth=1.8)

    # --- 计算统计量 ---
    means = [df[col].mean() for col in systems]
    stds = [df[col].std() for col in systems]

    # --- 绘制平均值条形图 + 误差线 ---
    plt.figure(figsize=(7, 4))
    bars = plt.bar(['PostgreSQL', 'openGauss', 'File'],
                   means, yerr=stds, capsize=6, color=colors, alpha=0.8)
    for bar, mean in zip(bars, means):
        plt.text(bar.get_x() + bar.get_width() / 2, mean + 2, f"{mean:.2f} ms", ha='center', va='bottom', fontsize=10)

    plt.title(title + "\n(Mean ± Std. Dev.)", fontsize=13, fontweight='bold')
    plt.ylabel("Execution Time (ms)", fontsize=12)
    plt.grid(axis='y', linestyle='--', alpha=0.6)
    plt.tight_layout()

    # --- 保存图像 ---
    plt.savefig(save_name.replace('.png', '_bar.png'), dpi=200)
    print(f"✅ 已保存均值图: {save_name.replace('.png', '_bar.png')}")

    # --- 绘制折线图 ---
    plt.figure(figsize=(8, 5))
    for sys, color, marker in zip(systems, colors, markers):
        plt.plot(x, df[sys], marker=marker, linestyle='-', label=sys.replace('(ms)', ''), color=color, linewidth=1.8)

    plt.xlabel("Run #", fontsize=12)
    plt.ylabel("Execution Time (ms)", fontsize=12)
    plt.title(title + "\n(10 consecutive runs)", fontsize=13, fontweight='bold')
    plt.legend()
    plt.grid(True, linestyle='--', alpha=0.6)
    plt.tight_layout()
    plt.savefig(save_name, dpi=200)
    print(f"✅ 已保存折线图: {save_name}")
    plt.show()


# === 主程序 ===
def main():
    print("=== 数据库 vs 文件 性能分析图 ===\n")

    select_df = read_data("select_results.csv")
    update_df = read_data("update_results.csv")

    if select_df is not None:
        plot_performance(select_df, "SELECT 性能比较（PostgreSQL vs openGauss vs File）", "select_performance.png")

    if update_df is not None:
        plot_performance(update_df, "UPDATE 性能比较（PostgreSQL vs openGauss vs File）", "update_performance.png")


if __name__ == "__main__":
    main()
