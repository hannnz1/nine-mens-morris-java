# 数据库初始化简化：单份 SQL、显式初始化、Hibernate 校验

## 设计决定

当前项目只有棋局和幂等记录两张业务表，主要用于个人开发与演示。为减少需要维护的依赖和版本迁移约定，移除 Flyway，使用一份最终结构的 `backend/src/main/resources/db/init.sql` 初始化新数据库。

应用启动后只校验基本映射，不创建表、不修改表，不自动删除或重建已有数据。以后字段、约束或索引发生变化，需要先明确执行对应的升级 SQL，再发布依赖新结构的代码。

这是一项维护方式的取舍，不代表 Flyway 只适合大型系统。若未来需要跨环境持续升级并保留长期业务数据，可重新评估迁移工具。

## 实际改动

- 删除 Flyway 的两个 Maven 依赖及主配置、测试配置中的启用项。
- 原 V1、V2 迁移 SQL 合并为一份最终结构：`black_player` 创建时就允许为空。
- 保留主键、幂等唯一约束、外键、级联删除和约束必需的索引。
- 应用设置 `spring.sql.init.mode: never`，继续使用 `spring.jpa.hibernate.ddl-auto: validate`。
- Docker 挂载同一份 SQL，由 PostgreSQL 镜像在空数据目录首次初始化时执行。
- H2 的独立测试配置在创建临时库后执行同一份 SQL。PostgreSQL 测试和 CI 在启动应用之前显式初始化数据库。
- 新增 PostgreSQL 约束测试，确认移除 Flyway 后，唯一性、外键和删除关联棋局时的级联行为仍生效。

没有使用 `ddl-auto: update`、`create` 或 `create-drop`，也没有用自制迁移框架替代 Flyway。

## 新数据库如何启动

### Docker Compose

项目根目录执行：

```powershell
Copy-Item .env.example .env
# 按本机环境设置 .env 中的数据库密码等参数。
docker compose up --build -d
```

首次创建空数据目录时，PostgreSQL 镜像执行挂载到 `/docker-entrypoint-initdb.d/01-init.sql` 的脚本。数据库完成初始化、开始接受 TCP 连接后，后端启动并校验表结构。

普通重启、重新构建后端镜像不会重新初始化已有数据卷。健康检查使用 TCP，避免把镜像初始化阶段的临时 Unix socket 服务误认为最终服务已就绪。

### 本机 PostgreSQL

先创建应用使用的数据库和角色，再在项目根目录执行一次：

```powershell
psql -h localhost -U morris -d morris --set=ON_ERROR_STOP=1 --single-transaction --file=backend/src/main/resources/db/init.sql
```

然后启动后端：

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/morris'
$env:DB_USERNAME = 'morris'
$env:DB_PASSWORD = 'your-password'
mvn -pl backend -am clean package
java -jar .\backend\target\backend-1.0.0-SNAPSHOT.jar
```

`psql` 没有加入 PATH 时，可以使用 PostgreSQL 安装目录中的 `bin/psql.exe`。`--set=ON_ERROR_STOP=1` 让 SQL 错误立即停止执行，`--single-transaction` 让这次显式初始化整体提交或回滚。

`init.sql` 只适用于新的空业务库，故意使用普通 `CREATE TABLE`，已有表时会报错。这样不会用 `IF NOT EXISTS` 掩盖表结构不匹配，也不会把“建表成功跳过”误认为“数据库已升级”。

## 已有数据库如何切换

### 原 Flyway 已执行到 V2

这次调整没有改变原 V2 的业务表结构，正常情况下可直接保留数据库，重新构建后启动新版本应用。使用 `mvn -pl backend -am clean package` 清理旧构建产物，或重新构建 Docker 镜像。不要重新执行 `init.sql`，也不要删除 Docker 数据卷。

原 `flyway_schema_history` 表可以留着作为历史记录；新应用不会访问它。删除依赖与源码中的迁移脚本不等于删除数据库表。

### 原数据库只执行了 V1

先确认实际字段定义及部署版本。在已备份、确认需要升级的数据库中显式执行：

```sql
ALTER TABLE game_sessions ALTER COLUMN black_player DROP NOT NULL;
```

这是原 V2 的结构变化，支持创建等待黑方加入的棋局。本次修改不会自动代替操作者升级旧数据库。Hibernate 的 `validate` 不是完整审计器，不能依赖它检查所有 nullable、索引和外键差异。

### 已有 Docker 数据卷，但业务表尚未创建

PostgreSQL 镜像不会再次运行首次初始化脚本。确认数据库是空的之后，在容器内显式执行已挂载的同一份 SQL：

```powershell
docker compose up -d postgres
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1 --single-transaction --file=/docker-entrypoint-initdb.d/01-init.sql'
docker compose up --build -d backend
```

不要通过 `docker compose down -v` 解决普通结构或启动问题，该命令会删除持久化数据卷。

## 测试与 CI

普通 `mvn clean verify`：原有 22 个测试运行，H2 测试通过测试 profile 初始化临时数据库；未配置 `PG_TEST_URL` 时，八个 PostgreSQL 测试显式跳过。

真实 PostgreSQL 测试：先创建独立、可清空的数据库和角色，并显式初始化一次。已初始化的同一个测试库后续运行时无需重复执行 SQL：

```powershell
psql -h localhost -U morris_test -d morris_concurrency_test --set=ON_ERROR_STOP=1 --single-transaction --file=backend/src/main/resources/db/init.sql
$env:PG_TEST_URL = 'jdbc:postgresql://localhost:5432/morris_concurrency_test'
$env:PG_TEST_USERNAME = 'morris_test'
$env:PG_TEST_PASSWORD = 'your-test-password'
mvn clean verify
```

测试会清理指定测试库的棋局和幂等记录，不应指向需要保留的数据库。CI 使用独立 PostgreSQL 16 服务，在 Maven 之前通过容器内 `psql` 显式执行同一份 SQL。

## 面试回答

> 原先项目用 Flyway 管理建表和一次字段可空性的变更。考虑到目前只有两张核心表，主要用于个人开发与演示，没有维护多个历史数据库版本的需求，我把它简化为一份最终结构的初始化 SQL。新环境先显式初始化，应用启动时只做 Hibernate 基本结构校验。
>
> 简化过程中，我保留了外键、唯一约束和有查询用途的索引，没有为了减少依赖而削弱数据一致性。Docker、H2 测试和 PostgreSQL 测试复用同一份 SQL，避免维护多套不同结构。对于已有数据库，不重新建表；需要升级时明确执行 ALTER SQL。
>
> 这个方案减少了迁移工具和版本约定的维护成本，但代价是后续升级需要人工安排。如果将来需要频繁跨环境发布、保留长期业务数据，再引入 Flyway 会更合适。

### 为什么不让 Hibernate 自动建表？

当前幂等实体把 `gameId` 映射成普通 UUID，没有声明关联实体。只依赖实体自动建新库不能完整还原 SQL 中的外键、级联删除和索引。保留 SQL 可以直接表达这些约束，并通过数据库测试验证。

### 不用 Flyway，就不需要 schema 了吗？

schema 指数据库的结构或命名空间，不是一项额外的管理工具。这里移除的是自动版本迁移机制，表结构仍然存在，也必须有可重复执行的初始化流程和可读的定义文件。

### 以后怎么加字段？

修改初始化 SQL，供未来新库使用；对已有库，先明确执行适用的 ALTER SQL，再发布应用。修改 `init.sql` 不会自动升级已部署的数据库。如果这种人工流程开始频繁或容易出错，就是重新评估迁移工具的时机。

## 本次验证记录（2026-09-16）

使用独立临时数据库完成验证，没有修改用户原有业务数据库：

| 验证内容 | 结果 |
| --- | --- |
| 显式执行同一份 init.sql 后运行 Maven 全量构建 | 30 项通过，零失败、零错误、零跳过 |
| H2 与真实 PostgreSQL | 均使用同一份建表 SQL，映射校验及对应测试通过 |
| 幂等唯一约束、外键、级联删除 | 真实 PostgreSQL 测试通过 |
| 未初始化的空 PostgreSQL 库 | 打包后的应用因 Hibernate 缺表校验失败而退出，未创建任何表 |
| 原 V1+V2 结构的 PostgreSQL 库 | 新应用正常启动，创建棋局后重启仍可读取原棋局及版本 |
| 真实 HTTP 双人对局脚本 | 创建、加入、落子、成三连、移除、幂等重试、旧版本拒绝和最终棋盘校验全部通过 |
| 在已有表的库中重复执行初始化 | 按预期报错，原有棋局数量不变 |
| 打包产物 | 包含 init.sql；不含 Flyway 库和旧迁移脚本 |
| Docker Compose | 配置校验通过；本机 Docker 引擎不可用，未验证容器实际首次初始化 |

验证环境：Maven 3.9.11、JDK 21（项目主体编译目标 Java 17）、PostgreSQL 16.2。远端 CI 尚未运行，本次结果不是生产压测或双浏览器 WebSocket 端到端验证。

## 官方依据

- [Spring Boot 3.4：数据库初始化与单一建表机制](https://docs.spring.io/spring-boot/3.4/how-to/data-initialization.html)
- [PostgreSQL 官方镜像：初始化脚本仅用于空数据目录](https://hub.docker.com/_/postgres)

本次后续精简移除了两个无调用方查询的非约束索引；已有库的手动调整见 [恢复设计文档](recovery-design.zh-CN.md)。原验证记录仅代表当时版本。
