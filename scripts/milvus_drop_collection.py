"""
删除 Milvus 中的 aiops_knowledge collection（一次性迁移工具）。

用途：
    切换 embedding 模型后向量维度变化（如 nomic-embed-text 768 维 → bge-m3 1024 维），
    旧 collection 必须删除——Spring AI 的 initialize-schema 只在 collection 不存在时
    自动建表，带着旧维度会导致新数据写入报维度不匹配。

执行：
    python scripts/milvus_drop_collection.py            # 只打印将执行的操作，不删除（防误触）
    python scripts/milvus_drop_collection.py --yes      # 确认后真正执行删除
执行后重启 Spring Boot 应用，会自动以新维度重建 collection，再调 POST /api/ai/ingest 灌库。

注意：--yes 会物理删除知识数据（数据可由 knowledge.txt 重新 ingest 恢复）。
"""
import argparse

from pymilvus import MilvusClient

MILVUS_URI = "http://localhost:19530"
COLLECTION_NAME = "aiops_knowledge"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="删除 Milvus 中的 aiops_knowledge collection（换 embedding 模型的迁移工具）")
    parser.add_argument("--yes", action="store_true",
                        help="确认执行删除；不带此参数只打印将执行的操作")
    args = parser.parse_args()

    if not args.yes:
        print("未带 --yes，本次不执行删除。将执行的操作：")
        print(f"  1. 删除 collection: {COLLECTION_NAME}（{MILVUS_URI}）")
        print("  2. 之后重启 Spring Boot 应用自动按新维度重建，再 POST /api/ai/ingest 灌库")
        print("确认无误请加 --yes 重新执行。")
        return

    # MilvusClient 是 PyMilvus 2.4+ 推荐的新式 API（旧的 connections/utility ORM 风格已弃用）
    client = MilvusClient(uri=MILVUS_URI)

    if client.has_collection(COLLECTION_NAME):
        client.drop_collection(COLLECTION_NAME)
        print(f"已删除 collection: {COLLECTION_NAME}")
    else:
        print(f"collection {COLLECTION_NAME} 不存在，无需删除")

    print("当前所有 collection:", client.list_collections())
    client.close()


if __name__ == "__main__":
    main()
