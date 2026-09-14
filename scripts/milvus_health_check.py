"""
Milvus collection 加载状态巡检脚本（只读 + 自动补加载）。

背景：
    Milvus 服务存活（/healthz 正常）不代表 collection 已加载到内存。
    collection 处于 NotLoaded 状态时直接做向量检索会报错——
    本脚本列出所有 collection 的加载状态，并对 NotLoaded 的自动执行一次 load。

执行：
    python scripts/milvus_health_check.py

说明：
    日常管理优先使用 Attu Web UI（compose 中已提供）；本脚本适合无界面环境
    或需要在 CI/运维流程中做一步到位的加载状态检查时使用。
"""
from pymilvus import MilvusClient

MILVUS_URI = "http://localhost:19530"

# MilvusClient 是 PyMilvus 2.4+ 推荐的新式 API（旧的 connections/utility ORM 风格已弃用）
client = MilvusClient(uri=MILVUS_URI)

collections = client.list_collections()
print("当前所有 collection:", collections)

for col_name in collections:
    # get_load_state 返回值随版本不同：可能是 LoadState 枚举，也可能是 {'state': LoadState} 字典
    state = client.get_load_state(col_name)
    if isinstance(state, dict):
        state = state.get("state")
    state_name = getattr(state, "name", str(state))  # Loaded / Loading / NotLoaded
    print(f"collection [{col_name}] 的状态: {state_name}")

    # NotLoaded 时补一次 load；正在加载（Loading）则跳过，避免重复触发
    if state_name == "NotLoaded":
        print(f"正在加载 {col_name} ...")
        client.load_collection(col_name)
        print(f"{col_name} 加载完成")

client.close()
