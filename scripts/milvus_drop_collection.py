"""
删除 Milvus 中旧的 aiops_knowledge collection。
用途：切换 embedding 模型后维度变化（768 → 1024），旧 collection 必须删除，
      否则新维度数据写入会报维度不匹配错误。
执行后 Spring AI 启动时会自动以新维度重新创建 collection。
"""
from pymilvus import connections, utility

COLLECTION_NAME = "aiops_knowledge"

# 1. 连接 Milvus
connections.connect("default", host="localhost", port="19530")

# 2. 检查 collection 是否存在
if utility.has_collection(COLLECTION_NAME):
    utility.drop_collection(COLLECTION_NAME)
    print(f"已删除 collection: {COLLECTION_NAME}")
else:
    print(f"collection {COLLECTION_NAME} 不存在，无需删除")

# 3. 确认
print("当前所有 collection:", utility.list_collections())
