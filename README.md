# Prenotazione Scarico · 卸货预约平台

快递员/承运商在线预约卸货时段，仓库人工确认并按看板收货。设计方案见 Claude 文档《Prenotazione Scarico — 卸货预约平台设计方案》。

## 当前进度（第一期骨架）

| 已完成 | 说明 |
| --- | --- |
| 数据库结构 | `drizzle/0000_init.sql`：主体、地点、卸货口、营业时间、关闭日、时长规则、预约规则、承运商、预约、状态日志、异常、用户、通知日志 |
| 防重复预约 | 数据库排他约束 `booking_no_overlap`：同一卸货口时间不可重叠；"待确认"也占位 |
| 基础数据 | 6 个主体 → 5 个地点（0 BMS 与 3 SAN FILIPPO 共用一个），每地 1 个卸货口，周一至周五 9:00–18:00，意大利法定节假日 |
| 预约引擎 | `src/lib/booking/service.ts`：按货量算时长、查空闲时段、创建预约（并发安全）、状态流转、取消截止时间 |
| 邮件 | 阿里云企业邮箱 SMTP；意/英模板，不含司机个人信息 |
| ERP 接口 | `src/lib/orders/validator.ts`：预留订单校验接口，当前默认放行 |
| 接口 | `GET /api/health`、`/api/entities`、`/api/booking-days`、`/api/availability`；`POST /api/bookings`；`GET/DELETE /api/manage/<token>` |
| 部署 | Dockerfile + docker-compose（应用、PostgreSQL、Caddy 自动 HTTPS、每日备份） |
| 快递端预约向导 | 首页即向导：选主体 → 车型与货量 → 日期与时段 → 联系信息 → 确认提交；意/英双语，手机优先 |
| 快递端自助管理 | 邮件中的专属链接 `/b/<token>`：查看状态、在截止时间前取消 |
| 隐私告知 | `/privacy` 草稿，待补充公司信息 |
| 测试 | 31 个测试，含真实 PostgreSQL 上的并发抢同一时段 |

| 仓库端 `/magazzino` | 员工登录；待确认队列（确认/驳回并邮件通知，驳回须填原因，超过 4 小时未处理标红）；当日看板（到场 → 开始卸货 → 完成，迟到 30 分钟可记爽约）；按仓库隔离权限；每 30 秒自动刷新 |

| 管理端 `/magazzino/admin` | 管理员：各仓库名称、地址、收货时间（可分段，如午休）、预约规则；关闭日；各车型最大货量和卸货时长；收货主体的公司全称与启用状态；员工账号（临时密码、重置密码、重置二次验证）；操作记录。仓库主管：管理自己仓库的关闭日 |
| 二次验证 | 所有员工首次登录时用 Google/Microsoft Authenticator 绑定，之后每次登录需输入 6 位动态码 |

下一步：报表（准时率、爽约率、卸货时长）。

## 目录

```
drizzle/              SQL 迁移（数据库结构的唯一来源）
src/db/               Drizzle 类型定义、连接、迁移、基础数据
src/lib/scheduling/   时长规则、空闲时段计算（纯函数）
src/lib/booking/      预约创建与状态流转
src/lib/mail/         邮件模板与发送
src/app/              Next.js 页面与 API
tests/                单元测试与数据库集成测试
```

## 本地开发

需要 Node 22 和 PostgreSQL 16。

```bash
npm install
cp .env.example .env          # 改 DATABASE_URL 指向本地数据库
npm run db:setup              # 建表 + 基础数据
npm run dev                   # http://localhost:3000
```

测试（集成测试会清空 `TEST_DATABASE_URL` 指向的库，请用单独的测试库）：

```bash
TEST_DATABASE_URL=postgres://postgres@localhost:5432/scarico_test npm test
```

## 部署到 Aruba 服务器

前提：Aruba 云服务器（Cloud Server / VPS，Ubuntu），能 SSH 登录。普通网站空间 (hosting) 运行不了。

1. **DNS**：在 casa-te.com 的 DNS 里加 A 记录 `prenota` → 服务器公网 IP。
2. **安装 Docker**：`curl -fsSL https://get.docker.com | sh`
3. **获取代码**：`git clone https://github.com/TEA-CoderR/Prenotazione_Scarico.git && cd Prenotazione_Scarico`
4. **配置**：`cp .env.example .env`，填写 `POSTGRES_PASSWORD`（长随机串）、`SMTP_PASS` 和两个 Turnstile 密钥（见下）。
5. **启动**：`docker compose up -d --build`。首次启动自动建表并写入基础数据，Caddy 自动申请 HTTPS 证书。
6. **检查**：打开 `https://prenota.casa-te.com/api/health`，应返回 `{"ok":true}`。

更新版本：`git pull && docker compose up -d --build`（新的迁移会自动执行）。

### 创建第一个管理员

部署后先用命令行建一个管理员，其他账号之后在管理端网页上建：

```bash
docker compose exec app node dist-scripts/create-user.js \
  --email it@casa-te.com --name "Nome Cognome" --role admin
```

首次登录时会要求用手机上的 Google Authenticator 或 Microsoft Authenticator 扫码绑定。手机丢失时，由另一位管理员在「Utenti」里重置；如果唯一的管理员丢了手机，就再执行一次上面的命令（会同时重置密码和二次验证）。

### 用命令行创建仓库员工账号（可选）

```bash
# 仓库主管，负责共用仓库和 FOIANO
docker compose exec app node dist-scripts/create-user.js \
  --email giulia.bianchi@casa-te.com --name "Giulia Bianchi" \
  --role warehouse_manager --sites BMS-SFILIPPO,FOIANO
```

- 角色：`warehouse_clerk`（收货员）、`warehouse_manager`（主管，可取消预约）、`admin`（全部仓库）。
- 仓库代码：`BMS-SFILIPPO`、`FOIANO`、`MARLIA`、`SANMARCO`、`SANCONCORDIO`。
- 不指定密码时会生成一个临时密码并只显示一次；也可以用 `-e NEW_USER_PASSWORD=...` 指定（至少 10 位）。
- 对已有邮箱再执行一次即重置密码、角色和仓库，并让该账号在所有设备上退出登录。

员工登录地址：`https://prenota.casa-te.com/magazzino`

### 邮件（阿里云企业邮箱）

- 在企业邮箱后台新建 `noreply@casa-te.com`，用它的密码（或客户端专用密码）填 `SMTP_PASS`。
- 在 casa-te.com 的 DNS 中按阿里云说明配置 **SPF** 和 **DKIM**，否则邮件容易进垃圾箱。
- 确认企业邮箱的每日外发上限；预估每天最多约 300–400 封。

### 人机验证（Cloudflare Turnstile）

预约表单最后一步有人机验证，防止程序批量占位。免费，需要一个 Cloudflare 账号（域名不必托管在 Cloudflare）：

1. Cloudflare 控制台 → Turnstile → 添加站点，域名填 `prenota.casa-te.com`，模式选 Managed。
2. 把 Site Key 填到 `.env` 的 `TURNSTILE_SITE_KEY`，Secret Key 填到 `TURNSTILE_SECRET_KEY`，然后 `docker compose up -d`。

两个都留空则不启用验证（仅用于本地开发）。Cloudflare 无法连接时，系统会拒绝提交（宁可拒绝也不放行）。

### 备份

`backup` 容器每天把数据库导出到服务器的 `./backups/`，保留 30 天。请再定期把这个目录复制到服务器以外的位置（如 Aruba 备份服务或公司 NAS），否则服务器损坏时备份也会一起丢失。

恢复：`docker compose exec -T db pg_restore -U scarico -d scarico --clean < backups/scarico-YYYY-MM-DD.dump`
