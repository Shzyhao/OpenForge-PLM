package com.openforge.common.module;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块自描述（A4 设计 3.1）：classpath 根的 openforge-module.yml，
 * 部署即注册——服务启动扫描并上报注册中心（auth sys_module）。
 */
@Data
public class ModuleDescriptor {

    public static final String RESOURCE = "openforge-module.yml";

    private String moduleKey;
    private String moduleType;          // KERNEL / BUSINESS / AI / EXTENSION
    private String displayName;
    private String version;
    private List<String> routes = new ArrayList<>();
    private List<String> dependencies = new ArrayList<>();
    private List<Map<String, String>> menu = new ArrayList<>();
    private String flywayTable;
    private String health = "/actuator/health";

    /** 从 openforge-module.yml 解析（snakeyaml → Map，手工绑定，避免引 jackson-dataformat-yaml）。 */
    public static ModuleDescriptor parse(Map<String, Object> yaml) {
        ModuleDescriptor d = new ModuleDescriptor();
        d.setModuleKey(str(yaml.get("moduleKey")));
        d.setModuleType(str(yaml.get("moduleType")));
        d.setDisplayName(str(yaml.get("displayName")));
        d.setVersion(str(yaml.get("version")));
        d.setRoutes(strList(yaml.get("routes")));
        d.setDependencies(strList(yaml.get("dependencies")));
        if (yaml.get("menu") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> entry = new java.util.LinkedHashMap<>();
                    m.forEach((k, v) -> entry.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                    d.getMenu().add(entry);
                }
            }
        }
        d.setFlywayTable(str(yaml.get("flywayHistoryTable")));
        if (yaml.get("flyway") instanceof Map<?, ?> f && f.get("historyTable") != null) {
            d.setFlywayTable(String.valueOf(f.get("historyTable")));
        }
        if (yaml.get("health") != null) {
            d.setHealth(String.valueOf(yaml.get("health")));
        }
        return d;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> strList(Object v) {
        List<String> result = new ArrayList<>();
        if (v instanceof List<?> list) {
            list.forEach(i -> result.add(String.valueOf(i)));
        }
        return result;
    }
}
