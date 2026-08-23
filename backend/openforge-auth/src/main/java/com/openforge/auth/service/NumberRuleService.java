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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编号规则引擎：段化配置(CONST/DATE/SEQ) + 并发防重号。
 *
 * 并发策略（演进路线）：
 * - M1 单实例部署：按 (ruleKey, period) 分段锁在应用内串行化取号，数据库唯一约束兜底；
 * - M2 多实例：升级为 PG 标准 SELECT FOR UPDATE（单实例已验证流程）或 Redis INCR（架构文档 9.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NumberRuleService {

    /** 段定义（由规则 JSON 反序列化）：CONST 常量 / DATE 日期 / SEQ 流水号 */
    public record Segment(String type, String value, String pattern, Integer length) {
    }

    private final NumberRuleMapper ruleMapper;
    private final NumberCounterMapper counterMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** (ruleKey:period) -> 监视器锁，单实例取号串行化 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public List<SysNumberRule> listRules() {
        return ruleMapper.selectList(null);
    }

    public SysNumberRule createRule(String ruleKey, String ruleName,
                                    List<Segment> segments, String resetPolicy) {
        Long existing = ruleMapper.selectCount(
                new LambdaQueryWrapper<SysNumberRule>().eq(SysNumberRule::getRuleKey, ruleKey));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.NUMBER_RULE_KEY_EXISTS);
        }
        validateSegments(segments);
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
        rule.setTenantId(0L);
        ruleMapper.insert(rule);
        return rule;
    }

    /** 生成下一个编号。date 参数化以便测试周期重置。 */
    public String nextNumber(String ruleKey, LocalDate date) {
        SysNumberRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<SysNumberRule>()
                .eq(SysNumberRule::getRuleKey, ruleKey)
                .eq(SysNumberRule::getStatus, "ACTIVE"));
        if (rule == null) {
            throw new BizException(ErrorCode.NUMBER_RULE_NOT_FOUND);
        }
        List<Segment> segments = parseSegments(rule.getSegments());
        String period = periodOf(rule.getResetPolicy(), date);

        // 分段锁串行化（单实例）；锁粒度 = 规则+周期，不同规则互不影响
        String lockKey = ruleKey + ":" + period;
        synchronized (locks.computeIfAbsent(lockKey, k -> new Object())) {
            return generate(segments, ruleKey, period, date, currentValue(ruleKey, period) + 1);
        }
    }

    public String nextNumber(String ruleKey) {
        return nextNumber(ruleKey, LocalDate.now());
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

    private String generate(List<Segment> segments, String ruleKey, String period,
                            LocalDate date, long next) {
        saveValue(ruleKey, period, next);
        StringBuilder sb = new StringBuilder();
        for (Segment seg : segments) {
            switch (seg.type()) {
                case "CONST" -> sb.append(seg.value() == null ? "" : seg.value());
                case "DATE" -> sb.append(date.format(DateTimeFormatter.ofPattern(
                        seg.pattern() == null ? "yyyyMMdd" : seg.pattern())));
                case "SEQ" -> {
                    int len = seg.length() == null ? 5 : seg.length();
                    sb.append(String.format("%0" + len + "d", next));
                }
                default -> throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "未知段类型: " + seg.type());
            }
        }
        return sb.toString();
    }

    private void validateSegments(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "段定义不能为空");
        }
        long seqCount = segments.stream().filter(s -> "SEQ".equals(s.type())).count();
        if (seqCount != 1) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "有且仅能有一个 SEQ 段");
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
