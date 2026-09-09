package com.openforge.common.spel;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.List;
import java.util.Map;

/**
 * 条件表达式求值（SpEL 标准变量语法：#amount > 1000；P3 自 workflow 下沉 common 供
 * 多域复用——工作流条件路由 / 连接器链分支）。
 * 沙箱（StandardEvaluationContext 显式收紧）：
 * - 只读 MapAccessor 支持 #steps.fetch.body.amount 的 Map 键点语法下钻，不提供写访问；
 * - 禁类型引用（T(...)）、禁构造器（new ...）、禁方法调用（无 method resolver）。
 */
@org.springframework.stereotype.Component
public class ExpressionEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    public boolean evaluate(String expr, Map<String, Object> variables) {
        try {
            Expression expression = parser.parseExpression(expr);
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setPropertyAccessors(List.of(new ReadOnlyMapAccessor()));
            context.setMethodResolvers(List.of());
            context.setConstructorResolvers(List.of());
            context.setTypeLocator(typeName -> {
                throw new org.springframework.expression.spel.SpelEvaluationException(
                        SpelMessage.TYPE_NOT_FOUND, typeName);
            });
            variables.forEach(context::setVariable);
            Boolean result = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "条件表达式求值失败: " + expr + " (" + e.getMessage() + ")");
        }
    }

    /** 只读 Map 键访问器：#var.key 的点语法下钻（仅 canRead，写不适用）。 */
    static class ReadOnlyMapAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[]{Map.class};
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof Map<?, ?> map && map.containsKey(name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) {
            throw new UnsupportedOperationException("表达式上下文只读");
        }
    }
}
