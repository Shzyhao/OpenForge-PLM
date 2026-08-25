# OpenForge 权限体系完善方案

> 权限专项（用户与角色模块深化）｜ 基于 v1.0.1 现状
> 版本：v1.0 ｜ 状态：评审稿 ｜ 更新日期：2026-08-25
> 关联：《OpenForge-开发文档》10.2、《OpenForge-架构文档》3.3

---

## 1. 需求梳理（原始需求结构化）

| # | 需求 | 要点 |
|---|------|------|
| R1 | 固定管理员账号 | 系统内置唯一 `admin` 账号，拥有系统全部权限管控能力；不可删除 |
| R2 | 角色自定义 | 角色增删改；每个角色单独配置**界面权限**；**每个界面具备独立的增删改查权限** |
| R3 | ADMINS 角色 | 拥有该角色的非管理员用户享有与管理员一样的权限配置能力，**但不能修改 admin 账号的任何信息**；只有 admin 能修改 admin |
| R4 | 用户手动管理 | 用户由管理员手动增加（默认启用）；账号密码**半年过期**：临期弹窗提醒；已过期登录时提醒并**强制弹出重置密码弹窗** |
| R5 | 扩写 | 以 R1~R4 为基础，补全用户与角色模块尚未实现的功能清单 |

## 2. 现状盘点（v1.0.1 差距）

| 能力 | 现状 | 差距 |
|------|------|------|
| 账号体系 | 开放注册 + 首用户自动 ADMIN | 无固定 admin；注册不受控；需移除首用户引导 |
| 角色 | 列表/创建/覆盖式分配 | **无删除、无编辑**；无角色描述；删除保护缺失 |
| 权限点 | 操作级（part:create 等散置各版本迁移） | **无菜单/界面权限概念**；命名不规范；无删除/编辑；无权限树 |
| 权限校验 | `@RequirePermission` 方法级 + ADMIN 角色免检 | 免检绑定在 ADMIN **角色**上（应绑定 admin **账号**）；前端不做权限联动（菜单全部可见，按钮不隐藏） |
| 用户管理 | /users/me、组织挂接 | **无用户列表/创建/编辑/启停/删除/重置密码**；无管理界面 |
| 密码 | BCrypt 存储 | **无过期机制**、无修改密码入口、无强度策略、无登录锁定 |
| 审计 | 无 | 无登录日志、无权限变更审计 |

## 3. 目标模型设计

### 3.1 账号与特权分层

```
admin 账号（唯一，user_type=SUPER）
  └─ 全部权限（免检）；admin 的信息只有 admin 自己能改
ADMIINS 角色（次级管理员，可多用户）
  └─ 绑定全部权限点（含用户/角色/权限管理）
  └─ 硬约束：任何针对 admin 账号的读写操作一律拒绝（服务层强制）
业务/自定义角色（ENGINEER/VIEWER/自定义）
  └─ 菜单权限（界面可见）+ 操作权限（界面内增删改查）
普通用户
  └─ 权限 = 所持角色权限并集；admin 账号除外无任何免检
```

关键决策（含迁移）：

1. **现有内置 `ADMIN` 角色演进为 `ADMINS`**（迁移脚本改名并更新绑定），语义从"免检超管"调整为"次级管理员"；拦截器免检逻辑从 `roles.contains("ADMIN")` 改为 `userType == SUPER`（即 admin 账号）；
2. **移除"首用户自动 ADMIN"引导**（v1.0.1 的临时方案），由固定 admin 取代：seed 迁移创建 `admin`（初始密码随机生成并打印在启动日志，首登强制改密）；
3. **关闭开放注册**（配置开关 `openforge.security.open-registration=false` 默认关闭），用户一律由管理员创建。

### 3.2 权限双层模型

```
sys_permission
├── MENU 类型（界面权限）：树形，对应前端菜单/页面
│     例：material（物料管理）→ material-list（物料列表）→ bom（BOM 管理）
└── OPERATION 类型（操作权限）：挂靠菜单，命名规范 模块:操作
      每个界面统一五件套：view / create / update / delete / export
      例：material:view, material:create, material:update, material:delete, material:export
```

- 角色配置界面 = 菜单树勾选 + 每个菜单下的操作项勾选（权限矩阵页）；
- 后端：菜单权限用于 `/users/me` 返回（前端动态渲染菜单），操作权限用于 `@RequirePermission`；
- **约定 `view` 是硬门槛**：接口层默认校验模块 view 权限（无 view 时该模块全部接口 403），防止"无界面但有接口"的绕过。

### 3.3 admin 保护规则矩阵

| 操作 | admin 自己 | ADMINS 用户对 admin | 普通管理员对 admin |
|------|-----------|--------------------:|-------------------:|
| 查看用户列表 | ✅ | ✅（列表可见，操作列禁用） | 同左 |
| 修改 admin 资料/角色/组织 | ✅ | ❌ 403 | ❌ 403 |
| 重置 admin 密码 | ✅ | ❌ 403 | ❌ 403 |
| 停用/删除 admin | ❌（不可停用删除） | ❌ | ❌ |
| admin 修改他人 | ✅ | ✅ | ✅ |

实现：`UserService` 统一入口 `assertCanOperateTarget(operator, target)`——目标为 admin 且操作者非 admin 本人即拒绝（含启用/停用/删除/改密/改角色/改组织/编辑全部阻断）。

## 4. 功能清单（R1~R4 落地 + 扩写）

### A. 固定管理员 admin【R1】

| # | 功能 | 说明 | 优先级 |
|---|------|------|--------|
| A1 | admin 内置 seed | 迁移创建：username=admin、user_type=SUPER、随机初始密码打印启动日志、first_login_change=1 | P0 |
| A2 | admin 不可变约束 | 不可删除/停用/移出 SUPER；用户名不可改；服务层 + 删除保护双校验 | P0 |
| A3 | admin 专属操作 | 只有 admin 可改 admin（资料/密码/角色） | P0 |
| A4 | 免检逻辑迁移 | 拦截器/网关免检从 ADMIN 角色改为 SUPER 账号（三处：auth 直查版、security 模块 HTTP 版、前端提示） | P0 |

### B. 角色管理【R2】

| # | 功能 | 说明 | 优先级 |
|---|------|------|--------|
| B1 | 角色列表/详情 | 含成员数、描述、内置标记、权限摘要 | P0 |
| B2 | 角色创建 | 编码唯一、名称、描述 | P0 |
| B3 | 角色编辑 | 名称/描述可改，编码不可改 | P0 |
| B4 | 角色删除 | 内置角色（ADMINS/ENGINEER/VIEWER）不可删；仍绑定用户的角色需先解绑或选择成员迁移 | P0 |
| B5 | 角色成员管理 | 查看/添加/移除成员（单个、批量） | P0 |
| B6 | 角色权限配置 | 菜单树 + 操作项矩阵勾选，保存为角色-权限绑定 | P0 |
| B7 | 角色复制（扩写） | 以现有角色为模板快速建新角色 | P2 |
| B8 | 角色启用/停用（扩写） | 停用后权限即时失效但绑定保留，恢复即生效（比删除安全） | P2 |

### C. 权限点与菜单管理【R2】

| # | 功能 | 说明 | 优先级 |
|---|------|------|--------|
| C1 | 权限模型升级 | sys_permission 增加 type(MENU/OPERATION)/parent_id/description/sort_order | P0 |
| C2 | 全量权限 seed | 按命名规范补齐 8 大模块 ×（菜单 + 五件套操作）：工作台/物料/BOM/文档/变更/流程/知识库/项目/系统管理 | P0 |
| C3 | 权限树查询 | 前端角色配置页数据源（菜单树 + 挂靠操作） | P0 |
| C4 | 自定义权限点 | 增删改（供二开场景）；被角色引用的权限点不可删 | P2 |
| C5 | view 硬门槛 | 各业务接口按模块 view 权限前置校验 | P1 |
| C6 | 权限点-代码联动检查（扩写） | CI 或启动时扫描 @RequirePermission 引用值是否存在于权限表，防拼写漂移 | P2 |

### D. 用户管理【R4 + 扩写】

| # | 功能 | 说明 | 优先级 |
|---|------|------|--------|
| D1 | 用户列表 | 分页 + 筛选（用户名/姓名/角色/组织/状态/账号类型）；admin 行操作列按保护矩阵禁用 | P0 |
| D2 | 管理员创建用户 | 用户名/姓名/邮箱/组织/角色/初始密码（手输或随机）；默认 ACTIVE；首登强制改密（可勾选） | P0 |
| D3 | 用户编辑 | 姓名/邮箱/组织 | P0 |
| D4 | 启用/停用 | 停用立即无法登录；已有 JWT 由网关在登录校验环节拒绝（status != ACTIVE → 401） | P0 |
| D5 | 管理员重置密码 | 生成随机密码（页面一次性展示）+ 强制下次登录改密；admin 目标受 A3 保护 | P0 |
| D6 | 删除用户 | 软删；admin 与当前登录者自身不可删 | P0 |
| D7 | 角色分配界面 | 现有接口的可视化（覆盖式分配保留） | P0 |
| D8 | 注册管控 | open-registration 配置开关，默认关闭自助注册 | P0 |
| D9 | 批量操作（扩写） | 批量启停/批量分配角色/批量移出组织 | P2 |
| D10 | 用户详情聚合（扩写） | 基本信息 + 角色 + 组织 + 最近登录 + 在办任务数 | P2 |

### E. 密码安全【R4 + 扩写】

| # | 功能 | 说明 | 优先级 |
|---|------|------|--------|
| E1 | 密码时效字段 | sys_user + password_updated_at；存量迁移回填 created_at | P0 |
| E2 | 过期策略 | `openforge.security.password-expiry-days`（默认 180）；登录响应返回 password_status：OK / EXPIRING_SOON(≤7天) / EXPIRED | P0 |
| E3 | 临期提醒 | 前端登录后弹窗"密码将于 X 天后过期"，可跳过；工作台持续提示条 | P0 |
| E4 | 过期强制重置 | 登录成功但 EXPIRED：前端强制弹出重置密码弹窗，不完成重置不放行（路由守卫拦截全部页面） | P0 |
| E5 | 修改密码接口 | /users/me/password：旧密码验证 + 新密码策略校验（≥8 位，含字母与数字）+ 更新 password_updated_at | P0 |
| E6 | 首登/重置后强制改密 | first_login_change 标志：登录时状态返回 FORCE_CHANGE，同 E4 交互 | P0 |
| E7 | 密码强度策略（扩写） | 长度/复杂度可配置；前后端双重校验 | P1 |
| E8 | 密码历史（扩写） | 最近 3 次密码不可重复（sys_password_history） | P1 |
| E9 | 登录失败锁定（扩写） | 连续 5 次失败锁定 15 分钟（failed_count/locked_until），admin 可提前解锁 | P1 |
| E10 | 会话与令牌（扩写） | Access+Refresh 双令牌；停用/改密后 Refresh 失效强制重新登录 | P2 |

### F. 前端权限联动与审计（扩写）【支撑 R2/R3 体验】

| # | 功能 | 说明 | 优先级 |
|---|------|------|--------|
| F1 | /users/me 扩展 | 返回 permissions 编码集合 + menus 树 + userType + passwordStatus | P0 |
| F2 | 动态菜单 | 侧边导航按菜单权限渲染；无权模块不可见 | P0 |
| F3 | 按钮级控制 | `v-permission` 指令 + `<HasPerm>` 组件：无操作权限的按钮隐藏 | P0 |
| F4 | 路由守卫 | 直接输 URL 访问无权页面 → 403 页 | P0 |
| F5 | 系统管理界面 | 用户管理页、角色管理页、角色权限矩阵页（本次新增三个页面） | P0 |
| F6 | 登录日志（扩写） | sys_login_log：成功/失败/锁定的用户/IP/时间/UA；管理页可查 | P1 |
| F7 | 权限变更审计（扩写） | 角色分配、权限矩阵变更、用户启停/重置密码全部落审计（操作人/对象/前后值） | P1 |
| F8 | 安全看板（扩写） | 近期失败登录、锁定账号、临期/过期密码统计 | P2 |

## 5. 数据模型变更

```sql
-- sys_user 扩展
ALTER TABLE sys_user ADD COLUMN user_type VARCHAR(10) NOT NULL DEFAULT 'NORMAL';      -- SUPER/NORMAL
ALTER TABLE sys_user ADD COLUMN password_updated_at TIMESTAMP;
ALTER TABLE sys_user ADD COLUMN first_login_change SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0;            -- E9
ALTER TABLE sys_user ADD COLUMN locked_until TIMESTAMP;                                -- E9

-- sys_role 扩展
ALTER TABLE sys_role ADD COLUMN description VARCHAR(255);
ALTER TABLE sys_role ADD COLUMN enabled SMALLINT NOT NULL DEFAULT 1;                   -- B8

-- sys_permission 扩展（C1）
ALTER TABLE sys_permission ADD COLUMN perm_type VARCHAR(10) NOT NULL DEFAULT 'OPERATION'; -- MENU/OPERATION
ALTER TABLE sys_permission ADD COLUMN parent_id BIGINT;
ALTER TABLE sys_permission ADD COLUMN description VARCHAR(255);
ALTER TABLE sys_permission ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

-- 新表：密码历史（E8）
CREATE TABLE sys_password_history (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 新表：登录日志（F6）
CREATE TABLE sys_login_log (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username VARCHAR(64), success SMALLINT NOT NULL, reason VARCHAR(64),
    ip VARCHAR(45), user_agent VARCHAR(255), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_login_log_username ON sys_login_log (username, created_at DESC);

-- seed 迁移（一次性）
-- 1) ADMIN 角色改名 ADMINS 并更新免检语义；2) 创建固定 admin 账号(user_type=SUPER, 随机密码打印日志, first_login_change=1)
--    3) 全量菜单+操作权限点；4) ADMINS 绑定全部权限点；5) 移除首用户引导代码
-- 6) 存量用户 password_updated_at 回填 created_at, user_type=NORMAL
```

## 6. 接口清单（新增/变更）

```
# 用户管理（D 组）
POST   /api/v1/users                          创建用户（user:manage，注册接口受开关控制后由本接口承担）
GET    /api/v1/users?username=&roleId=&orgId=&status=&page=  分页列表
GET    /api/v1/users/{id}                     详情（含角色/组织/密码状态）
PUT    /api/v1/users/{id}                     编辑资料（A3 保护）
POST   /api/v1/users/{id}/enable|disable      启停（A3 保护；admin 永不可停）
POST   /api/v1/users/{id}/reset-password      重置密码（A3 保护）
DELETE /api/v1/users/{id}                     删除（A3 保护；admin/自身不可删）
PUT    /api/v1/users/me/password              修改自己的密码（E5）

# 角色管理（B 组）
POST   /api/v1/roles                          创建（已有）
PUT    /api/v1/roles/{id}                     编辑（名称/描述）
DELETE /api/v1/roles/{id}                     删除（内置/有成员拒绝）
GET    /api/v1/roles/{id}/members             成员列表
POST   /api/v1/roles/{id}/members             添加成员（批量）
DELETE /api/v1/roles/{id}/members/{userId}    移除成员
POST   /api/v1/roles/{id}/copy                复制（B7）

# 权限（C 组）
GET    /api/v1/permissions/tree               权限树（菜单+操作）
PUT    /api/v1/roles/{id}/permissions         角色权限矩阵保存（覆盖式）
GET    /api/v1/roles/{id}/permissions         查看角色权限

# 登录扩展（E 组）
POST /api/v1/auth/login 响应新增: passwordStatus: OK|EXPIRING_SOON|EXPIRED|FORCE_CHANGE, daysToExpiry
GET  /api/v1/auth/login-logs                  登录日志（F6）
```

## 7. 关键流程设计

### 7.1 密码过期登录流程

```
登录请求 → 校验用户名密码
  ├─ 失败 → 失败计数+1（5 次锁定 15 分钟）→ 2002
  ├─ 锁定中 → 2005 账号已锁定
  └─ 成功 → 计算 passwordStatus
       ├─ FORCE_CHANGE（首登/被重置）→ 返回 token + status；前端强制改密弹窗（唯一可操作页面）
       ├─ EXPIRED（超 180 天）     → 同上强制弹窗，重置后方可进入
       ├─ EXPIRING_SOON（≤7 天）   → 正常进入 + 提醒弹窗（可跳过）+ 工作台提示条
       └─ OK → 正常进入
重置成功 → password_updated_at=now, first_login_change=0, 清除失败计数
```

### 7.2 前端权限渲染流程

```
登录 → /users/me → { menus[], permissions[], userType }
  ├─ menus → 动态生成侧边导航（admin/SUPER 返回全量）
  ├─ permissions → 全局权限 Set：v-permission 指令控制按钮显隐
  └─ 路由守卫：目标路由不在 menus → 403 页
变更角色权限后：用户重新登录或前端拉新 /users/me（60s 缓存失效）
```

## 8. 实施切分（PR 计划）

| PR | 内容 | 规模预估 |
|----|------|----------|
| P-1 | 数据模型扩展 + seed（admin 固化/ADMINS 迁移/全量权限点/密码字段）+ 免检逻辑迁移 + 注册开关 + 移除首用户引导 | 中（含迁移测试） |
| P-2 | 用户管理后端（D1~D6/D8）+ admin 保护矩阵 + 登录扩展（E1/E2 + 锁定 E9） | 大 |
| P-3 | 角色/权限管理后端（B1~B6 + C1~C3/C5）+ 审计埋点（F7 最小集） | 大 |
| P-4 | 前端：登录密码状态流程（E3/E4/E6 弹窗）+ 修改密码页 | 中 |
| P-5 | 前端：系统管理三页面（用户/角色/权限矩阵）+ 动态菜单/按钮控制/路由守卫（F1~F5） | 大 |
| P-6 | 扩写项：密码历史 E7/E8、登录日志页 F6、审计查询、批量操作 D9 | 中（可后置迭代） |

## 9. 兼容与迁移注意

1. `ADMIN → ADMINS` 改名迁移须与免检逻辑切换**同一 PR**（否则免检失效窗口）；
2. 存量 v1.0.1 用户密码时效回填 `created_at`——等价于"从注册日起算半年"，符合直觉；
3. 现有前端硬编码角色 Tag（ADMIN/ENGINEER 颜色）改为读取 userType/角色列表渲染；
4. `open-registration=false` 后原注册接口返回 403（保留接口以便内测环境打开）；
5. 网关信任头体系不变；本方案全部在 auth 服务 + 前端落地，其余服务仅受惠于 view 门槛与权限点补齐（seed 统一在 auth 库）。

---

**评审要点**：① ADMIN→ADMINS 迁移语义是否接受；② 权限五件套（含 export）颗粒度是否合适；③ E9 登录锁定与 E10 双令牌是否纳入本期；④ P-6 扩写项排期。
