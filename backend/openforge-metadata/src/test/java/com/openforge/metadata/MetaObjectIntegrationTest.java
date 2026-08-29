package com.openforge.metadata;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openforge.metadata.entity.MetaObject;
import com.openforge.metadata.mapper.MetaObjectMapper;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 元数据建模 H2 集成验证（F2-1）：建模 CRUD + 权限门禁 + 白名单拦截 + DDL 真实执行。
 * 权限查询以 MockBean 替换（真实 auth 链路由 auth 侧集成测试覆盖）；
 * 生成的 DDL 直接在 H2（PostgreSQL 模式）执行并写入读取——证明语句合法可执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetaObjectIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MetaObjectMapper metaObjectMapper;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    private void mockPerms(long userId, String... permissions) {
        when(permissionQueryClient.fetch(ArgumentMatchers.eq(userId)))
                .thenReturn(new PermissionView(userId, "USER", List.of("USER"), List.of(permissions)));
    }

    /** 设备台账建模载荷（F2 验收样例；REFERENCE 自引用演示引用闭合）。 */
    private String equipmentBody(String objectKey) {
        return """
                {
                  "objectKey": "%s",
                  "displayName": "设备台账",
                  "fields": [
                    {"fieldKey": "name", "displayName": "设备名称", "fieldType": "STRING", "required": true, "maxLength": 128},
                    {"fieldKey": "location", "displayName": "位置", "fieldType": "STRING"},
                    {"fieldKey": "purchase_price", "displayName": "采购价", "fieldType": "NUMBER"},
                    {"fieldKey": "installed_at", "displayName": "安装日期", "fieldType": "DATE"},
                    {"fieldKey": "is_critical", "displayName": "关键设备", "fieldType": "BOOLEAN"},
                    {"fieldKey": "host_equipment", "displayName": "主机设备", "fieldType": "REFERENCE", "refObject": "%s"}
                  ]
                }
                """.formatted(objectKey, objectKey);
    }

    @Test
    @DisplayName("建模→列表→详情→DDL 预览→DDL 真实执行并可写入读取 全链路")
    void modelingToExecutableDdl() throws Exception {
        mockPerms(1L, "meta:manage");

        // 建模：DRAFT 落库，表名 dyn_ 前缀，refField 默认 id，字段按定义顺序
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(equipmentBody("equipment")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.objectKey").value("equipment"))
                .andExpect(jsonPath("$.data.tableName").value("dyn_equipment"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.fields.length()").value(6))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("name"))
                .andExpect(jsonPath("$.data.fields[0].required").value(true))
                .andExpect(jsonPath("$.data.fields[5].refObject").value("equipment"))
                .andExpect(jsonPath("$.data.fields[5].refField").value("id"));

        // 列表：字段数统计（过滤定位，避免依赖插入顺序）
        mockMvc.perform(get("/api/v1/meta/objects").param("page", "1").param("pageSize", "50"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[?(@.objectKey=='equipment')].fieldCount", hasItem(6)));

        // DDL 预览与真实执行同源：在 H2 上逐条执行后写入读取
        MvcResult ddlResult = mockMvc.perform(get("/api/v1/meta/objects/{id}/ddl", idOf("equipment"))
                        .header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tableName").value("dyn_equipment"))
                .andReturn();
        String ddl = JsonMapper.builder().build()
                .readTree(ddlResult.getResponse().getContentAsString())
                .path("data").path("ddl").asText();
        for (String statement : ddl.split(";\\n")) {
            jdbcTemplate.execute(statement);
        }
        jdbcTemplate.update(
                "INSERT INTO dyn_equipment (name, location, purchase_price, host_equipment) VALUES (?, ?, ?, ?)",
                "CNC-01", "一号车间", 125000.5, 9L);
        Double price = jdbcTemplate.queryForObject(
                "SELECT purchase_price FROM dyn_equipment WHERE name = ?", Double.class, "CNC-01");
        assertThat(price).isEqualTo(125000.5);
        // 标准列由数据库默认值填充
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM dyn_equipment WHERE name = ?", Integer.class, "CNC-01");
        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("权限门禁：无信任头 401 / 无权限 403 / 有权限放行")
    void permissionGating() throws Exception {
        String body = equipmentBody("perm_check_obj");
        // 无 X-User-Id（未经网关）
        mockMvc.perform(post("/api/v1/meta/objects")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
        // 有身份但无 meta:manage
        mockPerms(2L);
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 2)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2004));
        // 有权限放行
        mockPerms(3L, "meta:manage");
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 3)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("建模红线：重复键 3010 / 非法字段 1000 / 引用不存在 3012 / 非法引用展示字段 3011")
    void modelingRedLines() throws Exception {
        mockPerms(1L, "meta:manage");
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(equipmentBody("dup_obj")))
                .andExpect(jsonPath("$.code").value(0));
        // 重复 objectKey
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(equipmentBody("dup_obj")))
                .andExpect(jsonPath("$.code").value(3010));

        // fieldKey 白名单：大写 / 标准列 / 保留字 / 注入载荷
        String[] badFieldKeys = {"Name", "tenant_id", "select", "name; DROP TABLE part"};
        for (String key : badFieldKeys) {
            mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"objectKey": "bad_field_obj", "displayName": "X",
                                     "fields": [{"fieldKey": "%s", "displayName": "X", "fieldType": "STRING"}]}
                                    """.formatted(key)))
                    .andExpect(jsonPath("$.code").value(1000));
        }

        // REFERENCE 引用不存在的对象
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"objectKey": "ref_bad_obj", "displayName": "X",
                                 "fields": [{"fieldKey": "ref_ghost", "displayName": "X",
                                             "fieldType": "REFERENCE", "refObject": "ghost_object"}]}
                                """))
                .andExpect(jsonPath("$.code").value(3012));

        // 引用展示字段必须是 id 或被引对象（此处为自身）的字段
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"objectKey": "ref_field_obj", "displayName": "X",
                                 "fields": [{"fieldKey": "name", "displayName": "X", "fieldType": "STRING"},
                                            {"fieldKey": "ref_self", "displayName": "X", "fieldType": "REFERENCE",
                                             "refObject": "ref_field_obj", "refField": "not_exists"}]}
                                """))
                .andExpect(jsonPath("$.code").value(3011));
    }

    @Test
    @DisplayName("草稿可改（字段全量替换）；已发布不可改 4010；不存在 404")
    void draftUpdateRules() throws Exception {
        mockPerms(1L, "meta:manage");
        mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(equipmentBody("draft_obj")))
                .andExpect(jsonPath("$.code").value(0));

        // 草稿全量替换：6 字段 → 2 字段
        mockMvc.perform(put("/api/v1/meta/objects/{id}", idOf("draft_obj")).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName": "设备台账V2",
                                 "fields": [
                                   {"fieldKey": "name", "displayName": "名称", "fieldType": "STRING", "required": true},
                                   {"fieldKey": "location", "displayName": "位置", "fieldType": "STRING"}
                                 ]}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.displayName").value("设备台账V2"))
                .andExpect(jsonPath("$.data.fields.length()").value(2));

        // 模拟发布后不可改（新版本流程随 F2-3）
        MetaObject published = metaObjectMapper.selectById(idOf("draft_obj"));
        published.setStatus("PUBLISHED");
        metaObjectMapper.updateById(published);
        mockMvc.perform(put("/api/v1/meta/objects/{id}", idOf("draft_obj")).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName": "X", "fields": [{"fieldKey": "name", "displayName": "X", "fieldType": "STRING"}]}
                                """))
                .andExpect(jsonPath("$.code").value(4010));

        // 不存在
        mockMvc.perform(get("/api/v1/meta/objects/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4009));
    }

    private Long idOf(String objectKey) {
        return metaObjectMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetaObject>()
                        .eq(MetaObject::getObjectKey, objectKey))
                .getId();
    }
}
