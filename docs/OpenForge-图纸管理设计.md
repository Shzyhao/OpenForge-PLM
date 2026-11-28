# OpenForge 图纸管理设计（v1.20.0）

> 状态：已交付（v1.20.0）。物料/BOM 就绪后的图纸域补齐——CAD 文件（DWG/DXF/PDF/图片）的
> 档案、版本、审签流转、物料关联与预览。服务 `openforge-drawing`（:8095，BUSINESS，
> connector 接入范本的全家桶模式）。

## 1. 背景与决策

- **为什么独立域服务**：图纸有独立生命周期（设计→审签→发布→作废，随 ECO 升版）与独立事件
  （drawing.released/obsolete），符合平台"一域一服务一 topic"哲学；doc 域虽有 docType=DRAWING
  枚举但无状态机/快照/下载/关联，扩 doc 会让文档与图纸的版本语义纠缠。
- **预览方案：预览文件**——上传时可附 PDF/图片副本（kind=PREVIEW），前端内嵌查看；
  DWG/DXF 主文件提供信息+下载。服务端零 CAD 转换依赖（CADConverter 服务仍留规划：
  ODA/Teigha 授权成本高，等真实使用密度再评估）。

## 2. 数据模型（V1 迁移，表前缀 drw_，四表均带 tenant_id 走租户拦截器自动过滤）

| 表 | 说明 |
|----|------|
| `drw_drawing` | 档案主表：drawing_number（编号引擎 ruleKey=drawing，DW-日期-流水，日重置）/ version_major(A/B/C)/version_minor / lifecycle_state / 检出锁 |
| `drw_drawing_file` | 文件：kind = MAIN（主文件）/ PREVIEW（预览副本）/ ATTACHMENT（附件）；sha256 + storage_key（`tenant/{tenantId}/{yyyyMMdd}/{uuid}{ext}`，本地磁盘与 doc 同约定，MinIO 同 key 切换） |
| `drw_drawing_version` | 发布快照（对齐 material PartVersion 语义）：RELEASED 时固化档案+文件清单 JSON |
| `drw_drawing_part` | 图纸↔物料多对多：part_number 冗余快照（物料删档不影响追溯）+ role（PART_DRAWING 零件图 / ASSEMBLY 装配图 / REFERENCE 参考） |

## 3. 生命周期与语义

```
DRAFT ──submit(需 MAIN 文件、未检出)──▶ REVIEWING ──approve──▶ RELEASED ──obsolete──▶ OBSOLETE
  ▲                                        │
  └──────────────reject────────────────────┘
RELEASED ──revise──▶ 新大版本 DRAFT（major+1，minor 归零，文件延续；对齐 BOM revise 先例）
DRAFT：可检出/检入（检入 minor+1）、可传文件、可逻辑删除
```

- 事件：RELEASED/OBSOLETE 后 afterCommit 发 `drawing.released` / `drawing.obsolete`
  （topic `openforge-drawing`）；connector EVENT 触发白名单已同步加入该主题——
  "图纸发布 → 自动推 ERP/MES"链路的入口就此打通（v1.16.0 material 事件化同模式）。
- 关联语义：partNumber 由前端物料选择器带入，服务端不做跨域存在性校验（编码为冗余快照）；
  按物料反查 `GET /by-part/{partId}` 返回图纸主档摘要，供物料详情侧跳转。

## 4. API（/api/v1/drawings，写操作 @RequirePermission("drawing:manage")）

建档 CRUD / `POST {id}/files`（multipart，kind 必选）/ `GET {id}/files/{fileId}/download`
（**流式下载，平台首个流式端点**：Content-Disposition RFC 5987 UTF-8 文件名）/ 检入检出 /
submit·approve·reject·obsolete·revise / versions / parts 双向关联 / by-part 反查。

## 5. 前端（DrawingPage，产品数据分组「图纸」入口）

列表（编号/标题/版本/状态/检出）+ 详情抽屉三 Tab：文件（分类上传 + 预览区：PREVIEW 文件
PDF iframe / 图片 img 内嵌，fetch+blob 鉴权取流）/ 版本历史（快照展开）/ 关联物料
（选择器调既有 `GET /api/v1/parts` + 角色选择）。状态机动作按钮随 lifecycleState 显隐。

## 6. 实施实纱（v1.20.0）

1. **mono bean 名冲突**：doc/drawing 同名 `LocalDiskStorage` 在单 classpath 冲突——
   显式 bean 名 `drawingLocalDiskStorage`（先例同 docNumberClient）。
2. **spring-boot-maven-plugin 需显式 repackage execution**（父 pom 未绑定 goal，
   漏写则只出普通 jar 无 -exec.jar，dev-up 无法启动）。
3. **Git Bash curl -F 路径实纱**：`-F "file=@/tmp/x"` 复合参数不做 MSYS 路径转换
   （`--data-binary @path` 纯路径形态可以）——smoke 上传统一 `cygpath -w` 转换。
4. **dev-up 性能护栏**（启动期 OOM 闪退 6 次 JVM 的根因治理）：
   - AppCDS 默认仅 gateway 开启——业务服务 A/B 收益仅 2-4%（画像 §8.3），
     但每服务 onRefresh 训练跑瞬时 +1 全量 JVM，11 服务并发训练 = 启动峰值 +10 JVM；
   - 构建护栏：检测到运行中服务即拒绝重打包（jar 锁 + 内存争用双风险）。
   - `SERVICES` 清单加入 drawing（:8095）。

## 7. 验证与边界

- 单测/集成：DrawingIntegrationTest 4（草稿守卫/全链路+快照+升版/关联双向+删除守卫/下载）；
  mono MonoSmoke 10 模块；connector 全量回归（白名单扩展）；冒烟 35 断言（图纸域 6 项）。
- 浏览器级巡检（约定 #9）：菜单入口/列表/详情三 Tab/PDF 内嵌预览/关联交互/状态流转。
- **边界（本期不做）**：DWG/DXF 服务端转换预览（CADConverter 留规划）、审签接流程引擎
  （REVIEWING 为简化态，接 workflow 后续评估）、MinIO 切换（与 doc 同欠账）、
  ECO 自动升版联动（drawing.released 事件口已留）。
