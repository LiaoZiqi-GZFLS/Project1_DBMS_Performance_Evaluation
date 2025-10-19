import pandas as pd
import matplotlib.pyplot as plt
import os

def csv_to_table_image(csv_file, output_image, title="Performance Comparison Table"):
    """
    将 CSV 表格转换为美观的 PNG 图片。
    """
    # 读取数据
    df = pd.read_csv(csv_file)

    # 创建画布
    fig, ax = plt.subplots(figsize=(12, 4 + len(df) * 0.4))  # 自动适应行数
    ax.axis('off')  # 关闭坐标轴

    # 创建表格
    table = ax.table(
        cellText=df.values,
        colLabels=df.columns,
        cellLoc='center',
        loc='center'
    )

    # 设置表格风格
    table.auto_set_font_size(False)
    table.set_fontsize(10)
    table.scale(1, 1.2)  # 宽度，高度缩放

    # 加标题
    plt.title(title, fontsize=14, pad=20)

    # 保存图片
    plt.savefig(output_image, dpi=300, bbox_inches='tight')
    plt.show()
    plt.close()
    print(f"✅ 表格图片已保存：{output_image}")

def main():
    # 要处理的 CSV 列表（可扩展）
    tasks = [
        {
            "csv": "select_multi_summary.csv",
            "img": "select_multi_summary.png",
            "title": "Multi-thread SELECT Performance Summary"
        },
        {
            "csv": "select_multi_threads.csv",
            "img": "select_multi_threads.png",
            "title": "Per-thread SELECT Details"
        }
    ]

    # 确保 result 目录存在
    if not os.path.exists("result"):
        os.mkdir("result")

    # 批量处理
    for task in tasks:
        if os.path.exists(task["csv"]):
            csv_to_table_image(task["csv"], task["img"], task["title"])
        else:
            print(f"⚠️ 找不到 CSV 文件：{task['csv']}")

if __name__ == "__main__":
    main()
