import pandas as pd
import matplotlib.pyplot as plt

# 读取 CSV 文件
def read_data(filename):
    try:
        df = pd.read_csv(filename)
        # 去掉最后一行平均值（可选）
        df = df[df['Run'] != 'Average']
        df['Run'] = df['Run'].astype(int)
        return df
    except Exception as e:
        print(f"❌ 无法读取 {filename}: {e}")
        return None


# 绘制性能折线图
def plot_performance(df, title, save_name):
    plt.figure(figsize=(8, 5))
    plt.plot(df['Run'], df['PostgreSQL(ms)'], 'o-', label='PostgreSQL')
    plt.plot(df['Run'], df['openGauss(ms)'], 's-', label='openGauss')
    plt.plot(df['Run'], df['File(ms)'], '^-', label='File (local)')
    plt.xlabel("Run #", fontsize=12)
    plt.ylabel("Time (ms)", fontsize=12)
    plt.title(title, fontsize=14, fontweight='bold')
    plt.legend()
    plt.grid(True, linestyle='--', alpha=0.6)
    plt.tight_layout()
    plt.savefig(save_name, dpi=200)
    print(f"✅ 已保存图像: {save_name}")
    plt.show()


def main():
    print("=== 绘制数据库 vs 文件 性能对比图 ===\n")

    select_df = read_data("select_results.csv")
    update_df = read_data("update_results.csv")

    if select_df is not None:
        plot_performance(select_df, "SELECT Performance (PostgreSQL vs openGauss vs File)", "select_performance.png")

    if update_df is not None:
        plot_performance(update_df, "UPDATE Performance (PostgreSQL vs openGauss vs File)", "update_performance.png")


if __name__ == "__main__":
    main()
