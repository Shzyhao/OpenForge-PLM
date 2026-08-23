package com.openforge.material.service;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

import java.util.Map;
import java.util.Set;

/** 物料域生命周期状态机（开发文档 3.1.1）。M3 接流程引擎后由审批流驱动流转。 */
final class StateMachine {

    /** 物料: 草稿→评审→发布; 驳回回草稿; 发布件冻结/废止 */
    static final Map<String, Set<String>> PART = Map.of(
            "DRAFT", Set.of("REVIEWING"),
            "REVIEWING", Set.of("RELEASED", "DRAFT"),
            "RELEASED", Set.of("FROZEN", "PHASED_OUT"),
            "FROZEN", Set.of("RELEASED", "PHASED_OUT"),
            "PHASED_OUT", Set.of());

    /** BOM: 草稿→评审→发布 */
    static final Map<String, Set<String>> BOM = Map.of(
            "DRAFT", Set.of("REVIEWING"),
            "REVIEWING", Set.of("RELEASED", "DRAFT"),
            "RELEASED", Set.of());

    static void requireTransition(Map<String, Set<String>> machine, String from, String to) {
        if (!machine.getOrDefault(from, Set.of()).contains(to)) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "非法状态流转: " + from + " → " + to);
        }
    }

    private StateMachine() {
    }
}
