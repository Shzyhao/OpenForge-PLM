package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.auth.entity.SysNumberCounter;
import com.openforge.auth.entity.SysNumberRule;
import com.openforge.auth.mapper.NumberCounterMapper;
import com.openforge.auth.mapper.NumberRuleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编号规则引擎：段化配置(CONST/DATE/SEQ) + 并发防重号。
 *
 * 并发策略（演进路线）：
 * - M1 单实例部署：按 (ruleKey, period) 分段锁在应用内串行化取号，数据库唯一约束兜底；
 * - M2 多实例：升级为 PG 标准 SELECT FOR UPDATE（单实例已验证流程）或 Redis INCR（架构文档 9.1）。
 *
 * 部署约束（v1.28 P2#15 钉住）：当前 JVM 内分段锁**仅单实例语义**——auth 服务不水平扩缩
 * （模块注册表 sys_module 本就单实例，对齐 LogRetentionJob 先例）。若未来扩多实例，
 * {@link #nextNumber(String, LocalDate)} 的应用内锁不再跨进程互斥，必须先行改造为
 * SELECT FOR UPDATE（counter 行级悲观锁，同事务 read+write）或 Redis INCR，
 * 否则跨实例并发取号只能靠 DB 唯一约束反复重试兜底（表现为取号 500 + 重试风暴）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NumberRuleService {

    /** 段定义（由规则 JSON 反序列化）：CONST 常量 / DATE 日期 / SEQ 流水号 */
    public record Segment(String type, String value, String pattern, Integer length) {
    }

    private static final Set<String> SEGMENT_TYPES = Set.of("CONST", "DATE", "SEQ");
    private static final Set<String> RESET_POLICIES = Set.of("NONE", "DAILY", "MONTHLY", "YEARLY");
    private static final int SEQ_LENGTH_MAX = 9;

    private final NumberRuleMapper ruleMapper;
    private final NumberCounterMapper counterMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** (ruleKey:period) -> 监视器锁，单实例取号串行化 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public List<SysNumberRule> listRules() {
        return ruleMapper.selectList(null);
    }

    /** 取号计数器水位（十轮改进：管理面可见，此前只能 SQL 手查）。 */
    public List<com.openforge.auth.entity.SysNumberCounter> listCounters() {
        return counterMapper.selectList(new LambdaQueryWrapper<com.openforge.auth.entity.SysNumberCounter>()
                .orderByAsc(com.openforge.auth.entity.SysNumberCounter::getRuleKey)
                .orderByAsc(com.openforge.auth.entity.SysNumberCounter::getPeriod));
    }

    public SysNumberRule createRule(String ruleKey, String ruleName,
                                    List<Segment> segments, String resetPolicy) {
        Long existing = ruleMapper.selectCount(
                new LambdaQueryWrapper<SysNumberRule>().eq(SysNumberRule::getRuleKey, ruleKey));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.NUMBER_RULE_KEY_EXISTS);
        }
        validateSegments(segments, resetPolicy);
        SysNumberRule rule = new SysNumberRule();
        rule.setRuleKey(ruleKey);
        rule.setRuleName(ruleName);
        try {
            rule.setSegments(objectMapper.writeValueAsString(segments));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "段定义序列化失败");
        }
        rule.setResetPolicy(resetPolicy == null ? "NONE" : resetPolicy);
        rule.setStatus("ACTIVE");
        rule.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        ruleMapper.insert(rule);
        return rule;
    }

    /** 停用/启用（2026-11-28 审查补齐：规则此前只能建不能停，坏规则只能留着继续取号）。 */
    @Transactional
    public SysNumberRule toggleRule(Long id, boolean enable) {
        SysNumberRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.NUMBER_RULE_NOT_FOUND);
        }
        rule.setStatus(enable ? "ACTIVE" : "DISABLED");
        ruleMapper.updateById(rule);
        return rule;
    }

    /** 生成下一个编号（真实取号，消费计数器）。date 参数化以便测试周期重置。 */
    public String nextNumber(String ruleKey, LocalDate date) {
        SysNumberRule rule = requireActiveRule(ruleKey);
        List<Segment> segments = parseSegments(rule.getSegments());
        String period = periodOf(rule.getResetPolicy(), date);

        // 分段锁串行化（单实例）；锁粒度 = 规则+周期，不同规则互不影响
        String lockKey = ruleKey + ":" + period;
        synchronized (locks.computeIfAbsent(lockKey, k -> new Object())) {
            long next = currentValue(ruleKey, period) + 1;
            String rendered = render(segments, date, next);
            // 先格式化后落号（2026-11-28 审查实锤：原实现先 saveValue 后格式化——格式化失败也跳号）
            saveValue(ruleKey, period, next);
            return rendered;
        }
    }

    /** 真预览（2026-11-28 审查实锤：原 POST /next 名为预览实为真实取号，管理页"取号预览"实测吃掉真实流水）。
     *  按当前水位 +1 渲染下一个编号，**不落计数器**；并发期间为近似值。 */
    public String previewNumber(String ruleKey, LocalDate date) {
        SysNumberRule rule = requireActiveRule(ruleKey);
        List<Segment> segments = parseSegments(rule.getSegments());
        String period = periodOf(rule.getResetPolicy(), date);
        return render(segments, date, currentValue(ruleKey, period) + 1);
    }

    public String nextNumber(String ruleKey) {
        return nextNumber(ruleKey, LocalDate.now());
    }

    public String previewNumber(String ruleKey) {
        return previewNumber(ruleKey, LocalDate.now());
    }

    private SysNumberRule requireActiveRule(String ruleKey) {
        SysNumberRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<SysNumberRule>()
                .eq(SysNumberRule::getRuleKey, ruleKey)
                .eq(SysNumberRule::getStatus, "ACTIVE"));
        if (rule == null) {
            throw new BizException(ErrorCode.NUMBER_RULE_NOT_FOUND);
        }
        return rule;
    }

    private long currentValue(String ruleKey, String period) {
        SysNumberCounter counter = counterMapper.selectOne(
                new LambdaQueryWrapper<SysNumberCounter>()
                        .eq(SysNumberCounter::getRuleKey, ruleKey)
                        .eq(SysNumberCounter::getPeriod, period));
        if (counter != null) {
            return counter.getCurrentValue();
        }
        try {
            SysNumberCounter init = new SysNumberCounter();
            init.setRuleKey(ruleKey);
            init.setPeriod(period);
            init.setCurrentValue(0L);
            counterMapper.insert(init);
        } catch (DataAccessException e) {
            log.debug("counter init skipped (exists): {}/{}", ruleKey, period);
        }
        return 0L;
    }

    private void saveValue(String ruleKey, String period, long value) {
        counterMapper.updateValue(ruleKey, period, value);
    }

    /** 纯渲染（无副作用）；取号成功路径在调用方先渲染后落号。 */
    private String render(List<Segment> segments, LocalDate date, long next) {
        StringBuilder sb = new StringBuilder();
        for (Segment seg : segments) {
            switch (seg.type()) {
                case "CONST" -> sb.append(seg.value() == null ? "" : seg.value());
                case "DATE" -> sb.append(date.format(DateTimeFormatter.ofPattern(
                        seg.pattern() == null ? "yyyyMMdd" : seg.pattern())));
                case "SEQ" -> {
                    int len = seg.length() == null ? 5 : seg.length();
                    if (next >= (long) Math.pow(10, len)) {
                        // 溢出：原实现静默变长（00001→100000）。变长破坏等宽展示但不破坏唯一性，
                        // 且规则无编辑端点——硬拒绝会让业务在水位耗尽后彻底取不出号，故保留变长但显式告警。
                        log.warn("number seq overflow: ruleKey grows beyond configured width, len={} next={}", len, next);
                    }
                    sb.append(String.format("%0" + len + "d", next));
                }
                default -> throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "未知段类型: " + seg.type());
            }
        }
        return sb.toString();
    }

    /** 段定义全量校验（2026-11-28 审查实锤：原只查 SEQ 个数——坏日期格式/位数 0 或负/未知段类型/
     *  未知重置策略都能创建，直到取号才 500；「周期重置+无日期段」次日重号）。 */
    private void validateSegments(List<Segment> segments, String resetPolicy) {
        if (segments == null || segments.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "段定义不能为空");
        }
        long seqCount = segments.stream().filter(s -> "SEQ".equals(s.type())).count();
        if (seqCount != 1) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "有且仅能有一个 SEQ 段");
        }
        String policy = resetPolicy == null ? "NONE" : resetPolicy;
        if (!RESET_POLICIES.contains(policy)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知重置策略: " + policy + "（NONE/DAILY/MONTHLY/YEARLY）");
        }
        for (Segment seg : segments) {
            if (seg.type() == null || !SEGMENT_TYPES.contains(seg.type())) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知段类型: " + seg.type() + "（CONST/DATE/SEQ）");
            }
            switch (seg.type()) {
                case "CONST" -> {
                    if (seg.value() == null || seg.value().isBlank()) {
                        throw new BizException(ErrorCode.INVALID_ARGUMENT, "CONST 段的 value 不能为空");
                    }
                }
                case "DATE" -> {
                    String pattern = seg.pattern() == null ? "yyyyMMdd" : seg.pattern();
                    try {
                        DateTimeFormatter.ofPattern(pattern).format(LocalDate.now());
                    } catch (IllegalArgumentException e) {
                        throw new BizException(ErrorCode.INVALID_ARGUMENT, "DATE 段 pattern 不合法: " + pattern);
                    }
                }
                case "SEQ" -> {
                    if (seg.length() != null && (seg.length() < 1 || seg.length() > SEQ_LENGTH_MAX)) {
                        throw new BizException(ErrorCode.INVALID_ARGUMENT,
                                "SEQ 段 length 须在 1-" + SEQ_LENGTH_MAX + " 之间");
                    }
                }
                default -> throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知段类型: " + seg.type());
            }
        }
        if (!"NONE".equals(policy) && segments.stream().noneMatch(s -> "DATE".equals(s.type()))) {
            // 周期重置按周期换计数器：无日期段时相邻周期的编号肉眼相同（次日重号）
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "重置策略 " + policy + " 必须包含 DATE 段，否则相邻周期会出现重号");
        }
    }

    private List<Segment> parseSegments(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Segment.class));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "段定义解析失败: " + e.getMessage());
        }
    }

    private String periodOf(String resetPolicy, LocalDate date) {
        if (resetPolicy == null) {
            return "";
        }
        return switch (resetPolicy) {
            case "DAILY" -> date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            case "MONTHLY" -> date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case "YEARLY" -> date.format(DateTimeFormatter.ofPattern("yyyy"));
            default -> "";
        };
    }
}
