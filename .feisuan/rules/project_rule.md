
# 开发规范指南

为保证代码质量、可维护性、安全性与可扩展性，请在开发过程中严格遵循以下规范。

## 一、项目与环境配置

- **作者**：itwanger
- **工作目录**：`/Users/itwanger/Documents/GitHub/PaiSwitch`
- **操作系统**：Mac OS X
- **当前时间**：2026-05-18 17:58:50

### 技术栈要求

- **主框架**：Spring Boot 3.2.2
- **开发语言**：Java 17.0.15
- **构建工具**：Maven
- **数据库**：MySQL (使用 HikariCP 连接池)
- **ORM 框架**：Spring Data JPA + Hibernate
- **数据库迁移**：Flyway
- **安全框架**：Spring Security + JWT (jjwt 0.12.3)

### 核心依赖说明

- **Spring AI**：版本 1.0.0-M4，用于接入 Anthropic API
- **API 文档**：SpringDoc OpenAPI (Swagger UI)
- **工具库**：Lombok (简化实体类开发)
- **加密**：Bouncy Castle (AES 加密支持)

### 目录结构

```text
PaiSwitch
├── ClaudeModelSwitcher          # 客户端项目
│   ├── App, Models, Views, ViewModels...
│   └── Services/Network
├── paiswitch-backend            # 后端服务项目
│   ├── docker
│   ├── src
│   │   ├── main
│   │   │   ├── java
│   │   │   │   └── com/paicoding/paiswitch
│   │   │   │       ├── common          # 公共组件
│   │   │   │       │   ├── config       # 配置类
│   │   │   │       │   ├── exception    # 异常处理
│   │   │   │       │   ├── response     # 统一响应
│   │   │   │       │   └── security     # 安全配置
│   │   │   │       ├── controller       # 控制器层
│   │   │   │       ├── domain           # 领域层
│   │   │   │       │   ├── dto           # 数据传输对象
│   │   │   │       │   ├── entity        # 数据库实体
│   │   │   │       │   └── enums         # 枚举类
│   │   │   │       ├── repository       # 数据访问层
│   │   │   │       └── service          # 业务层
│   │   │   │           └── ai           # AI 相关服务
│   │   │   └── resources
│   │   │       ├── META-INF
│   │   │       └── db/migration        # Flyway SQL 迁移脚本
│   │   └── test                        # 测试代码
│   └── pom.xml
└── paiswitch-web                 # Web 前端项目
    ├── api, assets, components...
    └── router, stores, types...
```

## 二、分层架构规范

| 层级        | 职责说明                         | 开发约束与注意事项                                               |
|-------------|----------------------------------|----------------------------------------------------------------|
| **Controller** | 处理 HTTP 请求与响应，定义 API 接口 | 不得直接访问数据库，必须通过 Service 层调用；使用 `@Valid` 校验参数 |
| **Service**    | 实现业务逻辑、事务管理与数据校验   | 必须通过 Repository 层访问数据库；返回 DTO 而非 Entity（除非必要） |
| **Repository** | 数据库访问与持久化操作             | 继承 `JpaRepository`；使用 `@EntityGraph` 避免 N+1 查询问题     |
| **Entity**     | 映射数据库表结构                   | 不得直接返回给前端（需转换为 DTO）；包名统一为 `domain.entity`         |

## 三、安全与性能规范

### 输入校验

- 使用 `@Valid` 与 JSR-303 校验注解（如 `@NotBlank`, `@Size` 等）
  - 注意：Spring Boot 3.x 中校验注解位于 `jakarta.validation.constraints.*`

- 禁止手动拼接 SQL 字符串，防止 SQL 注入攻击。
- 敏感信息（如数据库密码、JWT Secret）必须通过环境变量注入：
  - `DB_PASSWORD`
  - `JWT_SECRET`
  - `ANTHROPIC_API_KEY`
  - `AES_ENCRYPTION_KEY`

### 事务管理

- `@Transactional` 注解仅用于 **Service 层**方法。
- 避免在循环中频繁提交事务，影响性能。

### 数据库配置

- 使用 **Flyway** 进行数据库版本控制，所有迁移脚本位于 `src/main/resources/db/migration`。
- 生产环境需关闭 `spring.jpa.hibernate.ddl-auto` (当前为 none)。
- SQL 日志默认关闭 (`show-sql: false`)，调试时可开启。

## 四、代码风格规范

### 命名规范

| 类型       | 命名方式             | 示例                  |
|------------|----------------------|-----------------------|
| 类名       | UpperCamelCase       | `UserServiceImpl`     |
| 方法/变量  | lowerCamelCase       | `saveUser()`          |
| 常量       | UPPER_SNAKE_CASE     | `MAX_LOGIN_ATTEMPTS`  |
| 包名       | 全小写，点分隔       | `com.paicoding.xxx`   |

### 注释规范

- 所有类、方法、字段需添加 **Javadoc** 注释。
- **注释语言**：中文。

### 类型命名规范

| 后缀 | 用途说明                     | 示例         |
|------|------------------------------|--------------|
| DTO  | 数据传输对象                 | `UserDTO`    |
| DO   | 数据库实体对象               | `UserDO`     |
| BO   | 业务逻辑封装对象             | `UserBO`     |
| VO   | 视图展示对象                 | `UserVO`     |
| Query| 查询参数封装对象             | `UserQuery`  |

### 实体类简化工具

- 使用 Lombok 注解替代手动编写 getter/setter/构造方法：
  - `@Data`
  - `@NoArgsConstructor`
  - `@AllArgsConstructor`

## 五、扩展性与日志规范

### 接口优先原则

- 所有业务逻辑通过接口定义（如 `UserService`），具体实现放在 `impl` 包中（如 `UserServiceImpl`）。

### 日志记录

- 使用 `@Slf4j` 注解代替 `System.out.println`

### API 文档

- 项目集成了 **SpringDoc OpenAPI**。
- Swagger UI 访问地址：`http://localhost:{自动端口}/swagger-ui.html`
- API 文档地址：`http://localhost:{自动端口}/api-docs`

## 六、编码原则总结

| 原则       | 说明                                       |
|------------|--------------------------------------------|
| **SOLID**  | 高内聚、低耦合，增强可维护性与可扩展性     |
| **DRY**    | 避免重复代码，提高复用性                   |
| **KISS**   | 保持代码简洁易懂                           |
| **YAGNI**  | 不实现当前不需要的功能                     |
| **OWASP**  | 防范常见安全漏洞，如 SQL 注入、XSS 等      |
