package com.openforge.workflow.engine;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 条件表达式求值（SpEL 标准变量语法：#amount > 1000）。
 * 只读上下文禁类型引用与方法调用——表达式不可越权。
 */
@Component
public class ExpressionEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    public boolean evaluate(String expr, Map<String, Object> variables) {
        try {
            Expression expression = parser.parseExpression(expr);
            SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            variables.forEach(context::setVariable);
            Boolean result = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "条件表达式求值失败: " + expr + " (" + e.getMessage() + ")");
        }
    }
}
