"""AI 中台网关配置（环境变量注入；LLM 未配置时自动进入离线降级模式）。"""
import os


class Settings:
    def __init__(self) -> None:
        # LLM（OpenAI 兼容协议；兼容 GLM/Qwen/vLLM 等）
        self.llm_base_url = os.getenv("OPENFORGE_LLM_BASE_URL", "").rstrip("/")
        self.llm_api_key = os.getenv("OPENFORGE_LLM_API_KEY", "")
        self.llm_model = os.getenv("OPENFORGE_LLM_MODEL", "glm-4-flash")
        self.llm_timeout_seconds = float(os.getenv("OPENFORGE_LLM_TIMEOUT", "60"))

        # SQL 安全网关（架构文档 4.7.2：表白名单默认全关，按环境渐进开放）
        self.sql_allowed_tables = set(
            t.strip() for t in os.getenv(
                "OPENFORGE_SQL_ALLOWED_TABLES", "part,part_category,bom,bom_line,sys_user,sys_role"
            ).split(",") if t.strip()
        )
        self.sql_max_limit = int(os.getenv("OPENFORGE_SQL_MAX_LIMIT", "200"))
        # 只读数据源（AI 查询走独立只读连接；未配置时 data/query 返回明确错误）
        self.sql_readonly_url = os.getenv("OPENFORGE_SQL_READONLY_URL", "")

        # 表业务描述（nl2sql 注入 Prompt 的 Schema 知识；列级 schema 随 M6 元数据中心的
        # Schema 同步管道接入——开发文档 5.3.3「表结构描述向量化」）
        self.table_descriptions = {
            "part": "物料主数据表：part_number 编号, name 名称, type 类型(RAW/STANDARD/MADE/OUTSOURCED/SEMIFINISHED/PRODUCT), category_id 分类, lifecycle_state 状态(DRAFT/REVIEWING/RELEASED), version 版本, created_at 创建时间",
            "part_category": "物料分类树：category_code 编码, category_name 名称, parent_id 父分类, path 物化路径",
            "bom": "BOM 头：bom_number 编号, parent_part_id 父件, lifecycle_state 状态",
            "bom_line": "BOM 行：bom_id 所属BOM, child_part_id 子件, quantity 数量",
            "sys_user": "用户：username 用户名, display_name 姓名, org_id 组织, status 状态",
            "sys_role": "角色：role_code 编码(ADMIN/ENGINEER/VIEWER), role_name 名称",
        }
    @property
    def llm_online(self) -> bool:
        return bool(self.llm_base_url and self.llm_api_key)


settings = Settings()
