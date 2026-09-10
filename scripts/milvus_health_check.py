from pymilvus import Collection, connections, utility

# 1. 连接到 Milvus (根据你的实际IP和端口修改)
connections.connect("default", host="localhost", port="19530")

# 2. 查看所有的 Collection 名字
collections = utility.list_collections()
print("当前所有的 Collection:", collections)

# 3. 检查某个 Collection 的加载状态
for col_name in collections:
  # 获取加载状态
  state = utility.load_state(col_name)
  print(f"Collection [{col_name}] 的状态: {state}")

  # 如果没加载 (NotLoaded)，尝试手动加载一次
  if state.name == "NotLoaded":
    print(f"正在尝试加载 {col_name}...")
    col = Collection(col_name)
    col.load()
    print(f"{col_name} 加载成功！")
