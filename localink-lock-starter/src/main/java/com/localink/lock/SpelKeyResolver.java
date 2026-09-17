package com.localink.lock;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.TypedValue;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 切面共用的 SpEL key 解析器：表达式按字符串缓存（parse 贵、求值便宜），
 * 求值上下文绑定方法参数名（依赖 -parameters 编译）。空值快速失败——拼出尾空 key 会静默串行化整个接口。
 */
public class SpelKeyResolver {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ConcurrentMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * 求值 SpEL 表达式为 String；失败或为空抛 PARAM_ERROR。
     */
    public String resolve(ProceedingJoinPoint pjp, String expression) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                TypedValue.NULL, method, pjp.getArgs(), parameterNameDiscoverer);
        String evaluated = expressionCache
                .computeIfAbsent(expression, parser::parseExpression)
                .getValue(context, String.class);
        if (evaluated == null || evaluated.isBlank()) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "SpEL key 求值为空: " + expression);
        }
        return evaluated;
    }
}
