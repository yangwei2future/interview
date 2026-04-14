# 摘要

> 由于总忘记本地数据库密码，重装或重置密码总是浪费时间，导致使用本地数据库的成本较高，于是利用Docker的特性，进行本地数据库安装。

# 安装 MySQL

> 目标：用于 Agent 中记忆的持久化存储模块。

## 拉取镜像

```shell
# 拉取镜像
docker pull mysql:latest
```

## 运行镜像

> 注意：后续全部 MySQL 密码，统一设置成 `root123456`

```shell
# 运行 MySQL 容器
docker run -d \
  --name spring-ai-alibaba-mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root123456 \
  -e MYSQL_DATABASE=test \
  -e TZ=Asia/Shanghai \
  -v mysql_data:/var/lib/mysql \
  --restart unless-stopped \
  mysql:latest \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci
```

## 连接测试

```shell
# 连接到容器内的 MySQL
docker exec -it mysql-server mysql -u root -p
```

## 数据库连接工具验证

通过工具连接时，需要加上数据库连接参数：

```
?allowPublicKeyRetrieval=true
```

# 安装 pgvector

> 目标：用于 Agent 中 RAG 的向量存储模块。

## 拉取镜像

```shell
# 拉取镜像
docker pull ankane/pgvector
```

## 运行镜像

> 注意：后续全部 pgvector 密码，统一设置成 `pg123456`

```shell
# 使用 Docker 部署带有 pgvector 的 PostgreSQL
docker run -d \
  --name postgres-pgvector \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=pg123456 \
  -e POSTGRES_DB=test \
  -p 5432:5432 \
  -v pgvector-data:/var/lib/postgresql/data \
  ankane/pgvector
```
测试新加视频
[img.mp4](img.mp4)


