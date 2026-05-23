# interview-rag-system

基于 Spring Boot 4 + Spring AI + React 的智能面试平台，包含简历分析、模拟面试（文字 + 语音）、面试日程和 RAG 知识库。

---

## 一、技术栈

### 后端

| 技术                  | 版本      | 说明                               |
| --------------------- | --------- | ---------------------------------- |
| Spring Boot           | 4.0.1     | 应用框架                           |
| Java                  | 21        | 开发语言（虚拟线程）               |
| Spring AI             | 2.0.0-M4  | AI 集成框架（OpenAI 兼容模型接入） |
| Spring AI Agent Utils | 0.7.0     | Skill 资源加载、Advisor 能力扩展   |
| PostgreSQL + pgvector | 16        | 关系数据库 + 向量存储              |
| Redis + Redisson      | 7 / 4.0.0 | 缓存 + 消息队列（Redis Stream）    |
| Apache Tika           | 2.9.2     | 文档解析（PDF/DOCX/MD）            |
| iText 8               | 8.0.5     | PDF 导出                           |
| MapStruct             | 1.6.3     | 对象映射                           |
| SpringDoc OpenAPI     | 3.0.2     | API 接口文档                       |
| DashScope SDK         | 2.22.7    | 语音识别/合成（Qwen3 ASR/TTS）     |
| AWS S3 SDK            | 2.29.51   | S3 兼容对象存储（MinIO/RustFS）    |
| WebSocket             | -         | 语音面试实时双向通信               |
| Gradle                | 8.14      | 构建工具                           |

### 前端

| 技术               | 版本  | 说明             |
| ------------------ | ----- | ---------------- |
| React              | 18.3  | UI 框架          |
| TypeScript         | 5.6   | 开发语言         |
| Vite               | 5.4   | 构建工具         |
| Tailwind CSS       | 4.1   | 样式框架         |
| React Router       | 7.11  | 路由管理         |
| Framer Motion      | 12.23 | 动画库           |
| Recharts           | 3.6   | 图表库           |
| Lucide React       | 0.468 | 图标库           |
| React Big Calendar | 1.19  | 面试日历组件     |
| React Virtuoso     | 4.18  | RAG 聊天虚拟列表 |
| pnpm               | 10.26 | 前端包管理器     |

### 中间件

- **PostgreSQL 16 + pgvector**：业务数据 + 向量存储（1024 维 COSINE）
- **Redis 7**：缓存 + Stream 异步任务队列
- **MinIO / RustFS**：S3 兼容对象存储

---

## 二、Docker 启动

### 1. 环境要求

| 依赖    | 版本 | 必需       | 说明         |
| ------- | ---- | ---------- | ------------ |
| Docker  | 20+  | 是         | 运行容器     |
| JDK     | 21+  | 仅本地开发 | 后端开发调试 |
| Node.js | 18+  | 仅本地开发 | 前端开发调试 |
| pnpm    | 10+  | 仅本地开发 | 前端包管理器 |

### 2. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env`，至少填写 `AI_BAILIAN_API_KEY`（[阿里云百炼](https://bailian.console.aliyun.com/) 申请）：

```env
AI_BAILIAN_API_KEY=your_dashscope_api_key
AI_MODEL=qwen3.5-flash
```

### 3. 拉取镜像

提前拉取镜像，避免 `up` 阶段卡住：

```bash
docker compose pull
```

如遇到拉取失败（`net/http: TLS handshake timeout`、`context deadline exceeded`、`connection reset`），需要配置镜像加速或代理，见下文。

### 4. 镜像拉取失败：配置代理

#### 方案一：配置 Docker Desktop 代理（推荐）

打开 Docker Desktop → Settings → Resources → Proxies，启用 Manual proxy configuration：

```
HTTP Proxy:  http://127.0.0.1:7890
HTTPS Proxy: http://127.0.0.1:7890
No Proxy:    localhost,127.0.0.1
```

> 端口替换为你本地代理的实际端口（Clash 默认 7890，v2rayN 默认 10809）。

#### 方案二：配置镜像加速器

Docker Desktop → Settings → Docker Engine，添加：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com",
    "https://hub-mirror.c.163.com"
  ]
}
```

保存后点击 Apply & restart。

### 5. 完整部署（前后端 + 中间件）

`docker-compose.yml` 编排了 6 个服务：PostgreSQL（pgvector）、Redis、MinIO、MinIO Bucket 初始化、Spring Boot 后端、React 前端（Nginx）。

```bash
# 构建并启动所有服务
docker compose up -d --build

# 查看服务状态
docker compose ps

# 查看后端日志
docker compose logs -f app

# 停止并移除（数据保留）
docker compose down

# 停止并清除数据卷（慎用）
docker compose down -v
```

访问地址：

| 服务         | 地址                                                                        | 默认账号       | 默认密码       |
| ------------ | --------------------------------------------------------------------------- | -------------- | -------------- |
| 前端应用     | [http://localhost](http://localhost)                                           | -              | -              |
| 后端 API     | [http://localhost:8080](http://localhost:8080)                                 | -              | -              |
| 接口文档     | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | -              | -              |
| MinIO 控制台 | [http://localhost:9001](http://localhost:9001)                                 | `minioadmin` | `minioadmin` |
| PostgreSQL   | `localhost:5432`                                                          | `postgres`   | `password`   |
| Redis        | `localhost:6379`                                                          | -              | -              |

### 6. 本地开发模式（仅启动依赖）

只用 Docker 跑中间件，后端和前端在本地运行，方便热更新调试：

```bash
# 仅启动 PostgreSQL + Redis + RustFS
docker compose -f docker-compose.dev.yml up -d
```

依赖账号（与 `docker-compose.dev.yml` 对齐）：

| 服务          | 地址               | 账号            | 密码            |
| ------------- | ------------------ | --------------- | --------------- |
| PostgreSQL    | `localhost:5432` | `postgres`    | `123456`      |
| Redis         | `localhost:6379` | -               | -               |
| RustFS 控制台 | `localhost:9001` | `rustfsadmin` | `rustfsadmin` |

> 首次启动后，浏览器访问 [http://localhost:9001](http://localhost:9001) 登录 RustFS 控制台，手动创建名为 `interview-rag-system` 的 Bucket。`.env` 中的 `APP_STORAGE_ACCESS_KEY` / `APP_STORAGE_SECRET_KEY` 需要与 RustFS 账号一致（都设为 `rustfsadmin`）。

#### 启动后端

```bash
./gradlew :app:bootRun
```

后端服务：[http://localhost:8080](http://localhost:8080)

> Windows PowerShell 用户使用 `.\gradlew.bat :app:bootRun`。

#### 启动前端

```bash
cd frontend
corepack enable
pnpm install
pnpm dev
```

前端服务：[http://localhost:5173](http://localhost:5173)

### 7. 常见问题

**Q：`docker compose up` 卡在 `pulling` 阶段？**
代理或镜像加速没配好，参考上面「镜像拉取失败：配置代理」。

**Q：后端起不来，连不上 Postgres？**
确认 `.env` 里的 `POSTGRES_PASSWORD` 与 `docker-compose.yml` 一致。如果之前用过不同密码，需要 `docker compose down -v` 清掉旧数据卷再重建。

**Q：前端 `pnpm install` 卡住？**
配置 pnpm 镜像源：

```bash
pnpm config set registry https://registry.npmmirror.com
```

**Q：知识库向量化失败？**
检查 `application.yml` 中 `spring.ai.vectorstore.pgvector.initialize-schema` 是否为 `true`（开发环境推荐 true，自动创建 `vector_store` 表）。

**Q：设置页切换模型后不生效？**
运行时 Provider 配置写在 `~/.interview-rag-system/llm-providers.yml`，Docker 部署时建议挂载该目录持久化。可调用 `/api/llm-provider/reload` 重新加载。
