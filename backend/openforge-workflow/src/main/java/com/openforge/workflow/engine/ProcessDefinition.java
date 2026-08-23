package com.openforge.workflow.engine;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

import java.util.List;

/** 流程定义模型（JSON 反序列化）。节点类型：START/APPROVAL/CONDITION/END。 */
public record ProcessDefinition(List<NodeDef> nodes, List<EdgeDef> edges) {

    /**
     * @param mode     审批模式：ALL=会签（默认，全票通过；任一拒绝触发驳回）；ANY=或签（任一人办理即决定）
     * @param rejectTo 驳回时回退到的节点 id（重新生成任务），null=终止实例
     */
    public record NodeDef(
            String id,
            String type,
            String name,
            AssigneeDef assignee,      // 仅 APPROVAL
            List<RuleDef> rules,       // 仅 CONDITION
            String mode,
            String rejectTo) {
    }

    /** USER（value=用户id）/ ROLE（value=角色编码）/ USERS（values=用户id数组，配合 mode 会签或签） */
    public record AssigneeDef(String type, String value, List<String> values) {
    }

    /** CONDITION 分支：expr 为 SpEL（如 amount > 1000）；null 表示默认分支（兜底） */
    public record RuleDef(String expr, String to) {
    }

    public record EdgeDef(String from, String to) {
    }

    public NodeDef node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "节点不存在: " + id));
    }

    public EdgeDef edgeFrom(String from) {
        return edges.stream().filter(e -> e.from().equals(from)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "节点无出边: " + from));
    }

    public void validate() {
        if (nodes == null || edges == null || nodes.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "节点与边不能为空");
        }
        long starts = nodes.stream().filter(n -> "START".equals(n.type())).count();
        long ends = nodes.stream().filter(n -> "END".equals(n.type())).count();
        if (starts != 1 || ends < 1) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "必须有且仅有一个 START 节点，至少一个 END 节点");
        }
        for (NodeDef n : nodes) {
            if ("APPROVAL".equals(n.type())) {
                if (n.assignee() == null || n.assignee().type() == null) {
                    throw new BizException(ErrorCode.INVALID_ARGUMENT, "审批节点缺少审批人: " + n.id());
                }
                if ("USERS".equals(n.assignee().type())
                        && (n.assignee().values() == null || n.assignee().values().size() < 2)) {
                    throw new BizException(ErrorCode.INVALID_ARGUMENT,
                            "USERS 审批节点需要至少 2 个用户（单人请用 USER）: " + n.id());
                }
            }
            if (n.rejectTo() != null) {
                NodeDef target = nodes.stream().filter(x -> x.id().equals(n.rejectTo())).findFirst()
                        .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT,
                                "rejectTo 指向的节点不存在: " + n.rejectTo()));
                if (!"APPROVAL".equals(target.type())) {
                    throw new BizException(ErrorCode.INVALID_ARGUMENT, "rejectTo 只能指向审批节点: " + n.rejectTo());
                }
            }
            if ("CONDITION".equals(n.type())
                    && (n.rules() == null || n.rules().isEmpty()
                        || n.rules().stream().noneMatch(r -> r.expr() == null))) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "条件节点需要规则且必须含默认分支(expr=null): " + n.id());
            }
        }
    }
}
