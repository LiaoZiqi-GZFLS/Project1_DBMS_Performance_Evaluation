import pandas as pd
import matplotlib.pyplot as plt
import os

# 通用绘图函数
def plot_bar_chart(df, x_col, y_cols, title, output_file):
    plt.figure(figsize=(10, 6))
    x = df[x_col]
    for col in y_cols:
        plt.bar(x, df[col], alpha=0.6, label=col)
    plt.title(title)
    plt.xlabel(x_col)
    plt.ylabel("Value")
    plt.legend()
    plt.tight_layout()
    plt.savefig(output_file, dpi=300)
    plt.close()
    print(f"✅ 柱状图已保存：{output_file}")

def plot_line_chart(df, x_col, y_cols, title, output_file):
    plt.figure(figsize=(10, 6))
    x = df[x_col]
    for col in y_cols:
        plt.plot(x, df[col], marker='o', label=col)
    plt.title(title)
    plt.xlabel(x_col)
    plt.ylabel("Value")
    plt.legend()
    plt.tight_layout()
    plt.savefig(output_file, dpi=300)
    plt.close()
    print(f"✅ 折线图已保存：{output_file}")

# 生成表格图片
def csv_to_table_image(csv_file, output_image, title="Performance Table"):
    df = pd.read_csv(csv_file)
    fig, ax = plt.subplots(figsize=(12, 4 + len(df) * 0.4))
    ax.axis('off')
    table = ax.table(cellText=df.values, colLabels=df.columns, cellLoc='center', loc='center')
    table.auto_set_font_size(False)
    table.set_fontsize(10)
    table.scale(1, 1.2)
    plt.title(title, fontsize=14, pad=20)
    plt.savefig(output_image, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"✅ 表格图片已保存：{output_image}")

# 主流程
def generate_visual_reports():
    input_csv = "select_multi_summary.csv"  # 你的 CSV 文件名
    output_table = "select_summary_table.png"
    bar_chart = "select_qps_bar.png"
    line_chart = "select_time_line.png"

    if not os.path.exists(input_csv):
        print(f"⚠️ 未找到 CSV 文件：{input_csv}")
        return

    df = pd.read_csv(input_csv)
    df.columns = df.columns.str.strip()  # 去掉列名空格

    # 1️⃣ 保存表格为图片
    csv_to_table_image(input_csv, output_table, "SELECT Performance Summary")

    # 2️⃣ 柱状图对比 QPS
    if "QPS" in df.columns:
        qps_pivot = df.pivot(index="Threads", columns="DBMS", values="QPS").reset_index()
        dbms_list = [col for col in qps_pivot.columns if col != "Threads"]
        plot_bar_chart(qps_pivot, "Threads", dbms_list, "QPS Comparison (Higher is Better)", bar_chart)
    else:
        print("⚠️ CSV 中没有 QPS 字段，跳过柱状图")

    # 3️⃣ 折线图对比总耗时
    if "TotalTime(ms)" in df.columns:
        time_pivot = df.pivot(index="Threads", columns="DBMS", values="TotalTime(ms)").reset_index()
        dbms_list = [col for col in time_pivot.columns if col != "Threads"]
        plot_line_chart(time_pivot, "Threads", dbms_list, "Total Time vs Threads", line_chart)
    else:
        print("⚠️ CSV 中没有 TotalTime(ms) 字段，跳过折线图")

if __name__ == "__main__":
    generate_visual_reports()
