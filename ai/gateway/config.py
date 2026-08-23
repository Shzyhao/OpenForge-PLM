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

    @property
    def llm_online(self) -> bool:
        return bool(self.llm_base_url and self.llm_api_key)


settings = Settings()
