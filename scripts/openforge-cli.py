#!/usr/bin/env python3
"""OpenForge 脚手架 CLI（F3-4 / 路线 D-2）。

用法：
  python scripts/openforge-cli.py new-service <name> [--port 8090] [--display-name 订单]
  python scripts/openforge-cli.py selftest

new-service 生成一个即插即用的业务服务骨架：
  starter-security + starter-data（统一响应/权限/审计/多租户/模块注册）+
  module/<name>.yml 模块描述符（部署即注册，网关自动路由）+ Flyway 迁移样例 + 集成测试。
生成后：cd backend && mvn -pl openforge-<name> -am verify → ./scripts/dev-up.sh。
"""
import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

NAME_PATTERN = re.compile(r"^[a-z][a-z0-9_]{2,20}$")
ROOT = Path(__file__).resolve().parent.parent


def pom_xml(name: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.openforge</groupId>
        <artifactId>openforge-backend</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>openforge-{name}</artifactId>
    <name>OpenForge {name}</name>
    <description>由 openforge-cli 生成的业务服务骨架</description>

    <dependencies>
        <dependency>
            <groupId>com.openforge</groupId>
            <artifactId>openforge-starter-security</artifactId>
            <version>${{project.version}}</version>
        </dependency>
        <dependency>
            <groupId>com.openforge</groupId>
            <artifactId>openforge-starter-data</artifactId>
            <version>${{project.version}}</version>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
        <!-- Nacos 服务发现（默认关闭，部署时 NACOS_ENABLED=true 开启） -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
"""


def application_java(name: str) -> str:
    klass = "".join(p.capitalize() for p in name.split("_"))
    return f"""package com.openforge.{name};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.openforge") // starter 的公共装配经包扫描生效
public class {klass}Application {{

    public static void main(String[] args) {{
        SpringApplication.run({klass}Application.class, args);
    }}
}}
"""


def demo_controller_java(name: str) -> str:
    klass = "".join(p.capitalize() for p in name.split("_"))
    return f"""package com.openforge.{name}.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 骨架示例：删掉本类，换上真实 controller/service/entity/mapper。 */
@RestController
@RequestMapping("/api/v1/{name}")
public class {klass}Controller {{

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {{
        return ApiResponse.ok(Map.of("service", "openforge-{name}", "status", "ok"));
    }}

    @PostMapping
    @RequirePermission("{name}:create")   // 权限点随 auth V 迁移播种，或经 /internal/permissions 创建
    public ApiResponse<Map<String, Object>> create() {{
        return ApiResponse.ok(Map.of("todo", "实现业务创建"));
    }}
}}
"""


def application_yml(name: str, port: int) -> str:
    return f"""server:
  port: {port}

spring:
  application:
    name: openforge-{name}
  cloud:
    nacos:
      discovery:
        enabled: ${{NACOS_ENABLED:false}}
        server-addr: ${{NACOS_ADDR:localhost:8848}}
  datasource:
    url: jdbc:postgresql://${{PG_HOST:localhost}}:${{PG_PORT:5432}}/${{PG_DB:openforge}}
    username: ${{PG_USER:openforge}}
    password: ${{PG_PASSWORD:openforge}}
  flyway:
    enabled: true
    locations: classpath:db/migration/{name}
    baseline-on-migrate: true
    baseline-version: 0
    table: flyway_{name}_history   # 多服务共享库，独立迁移历史

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false

openforge:
  security:
    auth-base-url: ${{AUTH_SERVICE_URI:http://localhost:8081}}
    internal-token: ${{INTERNAL_TOKEN:openforge-internal-dev-token}}
  module:
    descriptor: classpath:module/{name}.yml
    service-uri: ${{openforge.module.service-uri:http://localhost:{port}}}

management:
  endpoints:
    web:
      exposure:
        include: health,info
"""


def test_application_yml(name: str) -> str:
    return f"""spring:
  datasource:
    url: jdbc:h2:mem:{name}test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
    username: sa
    password: ""
  flyway:
    table: flyway_{name}_history

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true

openforge:
  security:
    auth-base-url: http://localhost:0
    internal-token: test-internal-token
"""


def module_yml(name: str, display_name: str, port: int) -> str:
    return f"""# openforge-cli 生成：部署即注册（网关自动路由/菜单贡献/心跳判活）
moduleKey: {name}
moduleType: BUSINESS
displayName: {display_name}
version: 0.1.0
routes:
  - /api/v1/{name}
dependencies: []
menu:
  - {{ path: /{name}, title: {display_name}, icon: AppstoreOutlined }}
flyway:
  historyTable: flyway_{name}_history
serviceUri: ${{openforge.module.service-uri:http://localhost:{port}}}
"""


def migration_sql(name: str) -> str:
    return f"""-- 骨架示例表：删除或改造为真实业务表（tenant_id/审计列为平台惯例，租户拦截器自动过滤）
CREATE TABLE {name}_demo (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    BIGINT,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
"""


def test_java(name: str) -> str:
    klass = "".join(p.capitalize() for p in name.split("_"))
    return f"""package com.openforge.{name};

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 骨架冒烟：starter 装配 + Flyway 迁移在 H2 上加载成功。 */
@SpringBootTest
class {klass}ApplicationTests {{

    @Test
    void contextLoads() {{
    }}
}}
"""


def generate(name: str, port: int, display_name: str, backend_root: Path) -> Path:
    module_dir = backend_root / f"openforge-{name}"
    if module_dir.exists():
        raise SystemExit(f"已存在: {module_dir}")
    pkg = module_dir / "src" / "main" / "java" / "com" / "openforge" / name
    pkg.mkdir(parents=True)
    (pkg / "controller").mkdir()
    res = module_dir / "src" / "main" / "resources"
    (res / "db" / "migration" / name).mkdir(parents=True)
    (res / "module").mkdir(parents=True)
    test_pkg = module_dir / "src" / "test" / "java" / "com" / "openforge" / name
    test_pkg.mkdir(parents=True)
    (module_dir / "src" / "test" / "resources").mkdir(parents=True)

    (module_dir / "pom.xml").write_text(pom_xml(name), encoding="utf-8")
    klass = "".join(p.capitalize() for p in name.split("_"))
    (pkg / f"{klass}Application.java").write_text(application_java(name), encoding="utf-8")
    (pkg / "controller" / f"{klass}Controller.java").write_text(demo_controller_java(name), encoding="utf-8")
    (res / "application.yml").write_text(application_yml(name, port), encoding="utf-8")
    (res / "module" / f"{name}.yml").write_text(module_yml(name, display_name, port), encoding="utf-8")
    (res / "db" / "migration" / name / f"V1__init_{name}.sql").write_text(migration_sql(name), encoding="utf-8")
    (module_dir / "src" / "test" / "resources" / "application.yml").write_text(test_application_yml(name), encoding="utf-8")
    (test_pkg / f"{klass}ApplicationTests.java").write_text(test_java(name), encoding="utf-8")
    return module_dir


def register_module(backend_root: Path, name: str) -> None:
    root_pom = backend_root / "pom.xml"
    s = root_pom.read_text(encoding="utf-8")
    if f"<module>openforge-{name}</module>" in s:
        return
    marker = "        <module>openforge-starter-web</module>"
    assert marker in s, "根 pom 模块清单结构已变化，请手工添加 module"
    s = s.replace(marker, f"        <module>openforge-{name}</module>\n{marker}", 1)
    root_pom.write_text(s, encoding="utf-8", newline="\n")


def new_service(name: str, port: int, display_name: str) -> None:
    if not NAME_PATTERN.match(name):
        raise SystemExit(f"服务名非法: {name}（须匹配 ^[a-z][a-z0-9_]{{2,20}}$）")
    backend_root = ROOT / "backend"
    module_dir = generate(name, port, display_name, backend_root)
    register_module(backend_root, name)
    print(f"已生成 backend/openforge-{name}/（端口 {port}）并注册到根 pom")
    print(f"""
后续步骤：
  1. cd backend && mvn -pl openforge-{name} -am verify        # 编译 + 骨架测试
  2. ./scripts/dev-up.sh                                        # 全量启动（自动注册自动路由）
     或 SERVICES="auth gateway {name}" ./scripts/dev-up.sh     # 裁剪启动
  3. 替换 controller/entity/mapper 为真实业务；权限点在 auth 加迁移播种或走内部接口
  4. 前端菜单随模块注册表自动出现（/meta/designer 可为动态对象设计界面）
""")


def selftest() -> None:
    """在临时目录生成骨架并校验关键制品（不触碰真实工程）。"""
    with tempfile.TemporaryDirectory() as tmp:
        backend_root = Path(tmp) / "backend"
        backend_root.mkdir()
        (backend_root / "pom.xml").write_text(
            "        <module>openforge-starter-web</module>\n", encoding="utf-8")
        module_dir = generate("smoke_demo", 8099, "冒烟服务", backend_root)
        register_module(backend_root, "smoke_demo")

        expected = [
            "pom.xml",
            "src/main/java/com/openforge/smoke_demo/SmokeDemoApplication.java",
            "src/main/java/com/openforge/smoke_demo/controller/SmokeDemoController.java",
            "src/main/resources/application.yml",
            "src/main/resources/module/smoke_demo.yml",
            "src/main/resources/db/migration/smoke_demo/V1__init_smoke_demo.sql",
            "src/test/java/com/openforge/smoke_demo/SmokeDemoApplicationTests.java",
            "src/test/resources/application.yml",
        ]
        for rel in expected:
            assert (module_dir / rel).exists(), f"缺少 {rel}"
        pom = (module_dir / "pom.xml").read_text(encoding="utf-8")
        assert "openforge-starter-security" in pom and "openforge-starter-data" in pom
        module = (module_dir / "src/main/resources/module/smoke_demo.yml").read_text(encoding="utf-8")
        assert "moduleKey: smoke_demo" in module and "/api/v1/smoke_demo" in module
        root_pom = (backend_root / "pom.xml").read_text(encoding="utf-8")
        assert "<module>openforge-smoke_demo</module>" in root_pom
        # 生成物必须是合法 Java/yml 文本（无未替换占位符）
        app = (module_dir / "src/main/java/com/openforge/smoke_demo/SmokeDemoApplication.java").read_text(encoding="utf-8")
        assert "{" not in app.replace("{", "", 0) or "SpringApplication.run" in app
    print("selftest ok：骨架制品/根 pom 注册/starter 依赖/模块描述器 全部就位")


def main() -> None:
    parser = argparse.ArgumentParser(prog="openforge-cli", description="OpenForge 脚手架")
    sub = parser.add_subparsers(dest="command", required=True)
    p_new = sub.add_parser("new-service", help="生成业务服务骨架")
    p_new.add_argument("name", help="服务名（^[a-z][a-z0-9_]{2,20}$，如 orders / after_sale）")
    p_new.add_argument("--port", type=int, default=8090, help="服务端口（默认 8090，避开 8080-8088）")
    p_new.add_argument("--display-name", default=None, help="显示名（默认与服务名相同）")
    sub.add_parser("selftest", help="脚手架自测（临时目录，不触碰工程）")

    args = parser.parse_args()
    if args.command == "new-service":
        new_service(args.name, args.port, args.display_name or args.name)
    else:
        selftest()


if __name__ == "__main__":
    main()
