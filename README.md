# PaiSwitch

面向 AI Coding Agent 的模型控制台。

PaiSwitch 不只是一个“改配置文件”的小工具，而是一套本地优先的 Agent Model Control Plane。它把 Claude Code、Codex 等 Agent 的底层模型选择、API Key 管理、协议适配、配置备份、自然语言切换和 Skills 管理收拢到同一个工作台里，让开发者可以在官方模型、国产大模型、多模型网关和自定义供应商之间稳定切换。

当前已支持 Claude Code 与 Codex 两条链路，覆盖 DeepSeek V4、智谱 GLM-5/GLM-5.1、Kimi、Qwen、OpenRouter、SkyClaw、讯飞星辰 MaaS 等模型或网关，并保留 OpenAI / Anthropic 官方登录路径。

---

## 核心价值

| 能力 | 说明 |
|------|------|
| Agent 级切换 | 面向 Claude Code、Codex 这类 Coding Agent，而不是普通聊天窗口 |
| 双配置写入 | 自动维护 `~/.claude/settings.json`、`~/.codex/config.toml`、`~/.codex/auth.json` |
| 协议转换代理 | 将 Codex Responses API、Claude Code Anthropic Messages 请求转换到 OpenAI Chat Completions 兼容上游 |
| 多模型供应商 | 内置官方模型、DeepSeek、智谱、Kimi、Qwen、OpenRouter、SkyClaw、讯飞 MaaS，并支持自定义 Provider |
| Key 安全管理 | Web 侧 AES 加密存储，macOS 侧 Keychain 存储，切换时按目标 Agent 写入 |
| 自然语言操作 | 支持“切换到 DeepSeek”这类自然语言指令，并沉淀会话记录 |
| 本机 Skills 管理 | 可视化管理 `~/.claude/skills`，支持预览、重命名、软删除与恢复 |
| 配置回滚 | 切换前自动备份关键配置，降低反复试模型的风险 |

---

## 架构图

![](https://cdn.tobebetterjavaer.com/paicoding/README-3b4ef8952cb448f798c7e85c46994f50.png)


### 切换链路

![](https://cdn.tobebetterjavaer.com/paicoding/README-5ff262bca3214dfb8a61f7675766fbf5.png)

![](https://cdn.tobebetterjavaer.com/paicoding/README-bfefe858cf2e433a899baf747a4bde41.png)


---

## 支持矩阵

### Agent 目标

| Agent | 配置文件 | PaiSwitch 行为 |
|-------|----------|----------------|
| Claude Code | `~/.claude/settings.json` | 写入 `ANTHROPIC_BASE_URL`、`ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_MODEL`、`ANTHROPIC_SMALL_FAST_MODEL` |
| Codex | `~/.codex/config.toml`、`~/.codex/auth.json` | 写入 `model`、`model_provider`、本地代理 Provider 块与 `OPENAI_API_KEY` |

### 内置模型与网关

| 范围 | Provider | 典型模型 |
|------|----------|----------|
| 官方链路 | Anthropic Claude、OpenAI Official | Claude Sonnet、GPT / Codex 官方模型 |
| DeepSeek | DeepSeek | `deepseek-v4-pro`、`deepseek-v4-flash`、`deepseek-chat` |
| 智谱 GLM | Zhipu GLM | `glm-5`、`glm-5.1`、`glm-4.5`、`glm-4.5-air` |
| Moonshot | Kimi | `kimi-k2.6` 等 |
| 阿里云百炼 | Qwen | `qwen3-coder-plus`、`qwen3-max` |
| 多模型网关 | OpenRouter | Claude / OpenAI / 开源模型聚合路由 |
| Agent 专用模型 | SkyClaw | `skywork-ai/skyclaw-v1`、`skywork-ai/skyclaw-v1-lite` |
| 企业 MaaS | iFlytek Spark MaaS | 讯飞星辰 MaaS 服务 ID / Resource ID |
| 自定义 | Custom Provider | 任意 OpenAI / Anthropic 兼容服务 |

说明：具体模型名以你的供应商账号权限和当前 Provider 配置为准。PaiSwitch 支持在模型管理页覆盖 `base_url`、主模型、小模型与 API Key。

---

## 项目结构

```text
PaiSwitch/
├── ClaudeModelSwitcher/        # macOS 原生菜单栏应用，负责本机快速切换与 Keychain 存储
├── paiswitch-backend/          # Spring Boot API、配置写入器、代理转换器、Flyway 迁移
├── paiswitch-web/              # Vue 3 管理台，模型管理、切换、AI 助手、Skills 管理
├── docker-compose.yml          # MySQL + Backend + Frontend 一体化部署
└── README.md                   # 项目总览
```

---

## 快速开始

### Docker Compose

```bash
cp .env.example .env
docker compose up -d --build
```

启动后访问：

| 服务 | 地址 |
|------|------|
| Web Console | `http://localhost` |
| Backend API | `http://localhost:8086` |
| Swagger UI | `http://localhost:8086/swagger-ui.html` |

默认账号：

```text
admin / admin123
```

### 本地开发

先启动 MySQL，并创建数据库：

```sql
CREATE DATABASE paiswitch CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

启动后端：

```bash
cd paiswitch-backend
mvn spring-boot:run
```

启动前端：

```bash
cd paiswitch-web
npm install
npm run dev
```

访问：

```text
http://localhost:3000
```

### macOS 本地应用

```bash
open ClaudeModelSwitcher.xcodeproj
```

在 Xcode 中选择 `PaiSwitch` 目标并运行。macOS 应用支持本地切换、Keychain 存储、在线登录、AI 助手与 Claude Code Skills 管理。

### macOS 桌面发行包

桌面发行包使用 Tauri 打包，会内置 Spring Boot 后端 Jar 和精简 Java Runtime，不要求用户单独安装 Java。

```bash
cd paiswitch-web
npm install
npm run desktop:build
```

构建产物：

```text
src-tauri/target/release/bundle/dmg/*.dmg
src-tauri/target/release/bundle/macos/*.app
```

对外发布优先上传 `.dmg`；`.app` 适合作为 zip 备用包。

---

## 使用方式

### 1. 选择目标 Agent

Web Console 顶部支持在 `Claude Code` 与 `Codex` 之间切换。不同 Agent 会维护独立的当前 Provider、模型参数和 API Key 状态。

### 2. 配置 Provider

进入“模型管理”：

| 字段 | 说明 |
|------|------|
| Base URL | 上游模型或本地代理的基础地址 |
| Model | Agent 使用的主模型 |
| Small Model | Claude Code 小模型或轻量模型 |
| API Key | 上游供应商密钥，后端会加密存储 |
| Wire API | 标记上游协议，Codex 第三方链路会通过本地代理适配 |

### 3. 一键切换

进入“仪表盘”或“模型切换”页选择 Provider。PaiSwitch 会：

1. 读取当前用户、目标 Agent 和 Provider。
2. 查询对应 API Key 并解密。
3. 创建配置备份。
4. 写入 Claude Code 或 Codex 的本机配置。
5. 记录切换历史。

### 4. 自然语言切换

在 AI 助手中输入：

```text
切换到 DeepSeek
帮我换成智谱
用 OpenRouter
```

PaiSwitch 会解析意图并触发切换。当前自然语言切换优先服务 Claude Code 链路，Codex 链路建议直接使用工具页切换。

---

## 协议适配

很多国产模型和 Agent 专用模型提供 OpenAI Chat Completions 接口，但 Codex 和 Claude Code 的请求形态并不相同。PaiSwitch 在本地提供两个代理入口：

| 代理 | 入口 | 转换 |
|------|------|------|
| Claude Proxy | `/claude-proxy/{provider}` | Anthropic Messages -> OpenAI Chat Completions |
| Codex Proxy | `/codex-proxy/{provider}/v1` | OpenAI Responses API -> OpenAI Chat Completions |

代理会处理常见兼容性问题：

- 将工具调用结构映射到 Chat Completions。
- 合并或清理部分上游不接受的 system / stream 参数。
- 将上游 usage 字段重组为 Agent 可识别的格式。
- 对 OpenAI 官方链路保留 Codex OAuth 登录状态。
- 对第三方 Codex Provider 统一写入 `wire_api = "responses"`，由 PaiSwitch 代理完成转换。

---

## 配置文件落点

### Claude Code

```json
{
  "env": {
    "ANTHROPIC_BASE_URL": "http://127.0.0.1:8086/claude-proxy/skyclaw",
    "ANTHROPIC_AUTH_TOKEN": "your-provider-key",
    "ANTHROPIC_MODEL": "skywork-ai/skyclaw-v1",
    "ANTHROPIC_SMALL_FAST_MODEL": "skywork-ai/skyclaw-v1-lite",
    "API_TIMEOUT_MS": 600000
  }
}
```

官方 Anthropic 模式会清理第三方 Provider 变量，避免旧配置污染。

### Codex

```toml
model_provider = "deepseek"
model = "deepseek-v4-pro"
model_reasoning_effort = "high"
disable_response_storage = true

[model_providers.deepseek]
name = "DeepSeek"
base_url = "http://127.0.0.1:8086/codex-proxy/deepseek/v1"
wire_api = "responses"
requires_openai_auth = true
```

OpenAI Official 模式会尽量保留 `codex login` 产生的 OAuth token；如果你在 PaiSwitch 中配置了 OpenAI API Key，则会改走 API Key 模式。

---

## 技术栈

| 模块 | 技术 |
|------|------|
| Backend | Java 17、Spring Boot 3.2、Spring Security、Spring AI、Flyway、MySQL |
| Web | Vue 3、TypeScript、Vite、Pinia、Vue Router、Tailwind CSS |
| macOS | Swift、SwiftUI、MenuBarExtra、Keychain |
| Protocol Proxy | Java HTTP Client、SSE、Responses / Messages / Chat Completions 转换 |
| Storage | MySQL、AES 加密、Keychain、本机 Agent 配置文件 |

---

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/auth/login` | 登录 |
| `GET` | `/api/v1/providers/my?tool=codex` | 查询当前用户可用 Provider |
| `POST` | `/api/v1/api-keys?tool=claude_code` | 设置 Provider API Key |
| `PUT` | `/api/v1/providers/{code}/config?tool=codex` | 更新 Provider 配置 |
| `POST` | `/api/v1/providers/{code}/test?tool=codex` | 测试 Provider 连通性 |
| `POST` | `/api/v1/switch?tool=codex` | 切换目标 Agent 模型 |
| `POST` | `/api/v1/ai/switch-by-nl` | 自然语言切换 |
| `GET` | `/api/v1/skills` | 列出本机 Claude Code Skills |
| `POST` | `/api/v1/skills/{folderName}/trash` | 移动 Skill 到回收站 |

完整接口见：

```text
http://localhost:8086/swagger-ui.html
```

---

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DB_PASSWORD` | MySQL 密码 | `123456` / Docker 中为 `root123456` |
| `JWT_SECRET` | JWT 签名密钥，建议至少 256 bit | 内置开发值 |
| `AES_ENCRYPTION_KEY` | API Key 加密密钥，建议使用 32 字符强随机值 | 内置开发值 |
| `ANTHROPIC_API_KEY` | Spring AI / AI 助手可用的 Anthropic Key | 空 |

生产环境请务必覆盖 `JWT_SECRET` 与 `AES_ENCRYPTION_KEY`。

---

## 开发与验证

后端测试：

```bash
cd paiswitch-backend
mvn test
```

后端打包：

```bash
cd paiswitch-backend
mvn clean package
```

前端构建：

```bash
cd paiswitch-web
npm run build
```

macOS 桌面包构建：

```bash
cd paiswitch-web
npm run desktop:build
```

发布到 GitHub Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

在 GitHub 创建并发布同名 Release 后，`.github/workflows/release-desktop.yml` 会在 macOS runner 上构建 `.dmg` 和 `.app.zip`，并上传 `SHA256SUMS.txt`。

当前 workflow 使用未签名包，首次打开可能触发 macOS Gatekeeper 提示。面向更大范围用户分发时，建议接入 Apple Developer ID 签名和 notarization。

---

## 常见问题

### 切换后 Agent 没有立刻生效？

Claude Code / Codex 可能已经在当前会话中读取了旧配置。重新打开终端或重启对应 Agent 后再试。

### 为什么 Codex 第三方模型要走本地代理？

Codex 对配置中的 `wire_api` 有严格限制，而 DeepSeek、GLM、Kimi、Qwen 等供应商通常提供 Chat Completions。PaiSwitch 将 Codex 请求先引到本地 `/codex-proxy/{provider}/v1`，再转换为上游可识别的请求。

### Claude Code 能使用 OpenAI Chat Completions 兼容模型吗？

可以。Provider 的 `wire_api` 标记为 `openai` 时，PaiSwitch 会让 Claude Code 访问本地 `/claude-proxy/{provider}`，由代理完成 Anthropic Messages 与 Chat Completions 的双向转换。

### API Key 存在哪里？

Web / 后端模式下存储在 MySQL，并通过 AES 加密；macOS 本地模式下存储在系统 Keychain。写入 Agent 配置时，PaiSwitch 只写入当前切换所需的 Key。

### 如何增加新的模型供应商？

在 Web Console 的“模型管理”中创建自定义 Provider，填写 `base_url`、模型名和 API Key。若需要内置到系统，可新增 Flyway 迁移并补充 `DefaultUserInitializer`。

---

## 文档入口

- [Backend README](paiswitch-backend/README.md)
- [Web README](paiswitch-web/README.md)

---

## License

MIT
