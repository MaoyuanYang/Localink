package com.localink.lock;

/**
 * 切面共用的受检异常载体：Supplier 不允许抛受检异常，用载体 RuntimeException 包装穿越函数式接口，
 * 切面入口统一拆包重抛；RuntimeException/Error 原样直传。
 */
public final class AspectProceed {

    private AspectProceed() {
    }

    /**
     * 在 Supplier 内执行 proceed，受检异常包装为载体。
     */
    public static Object proceed(ProceedFunction proceed) {
        try {
            return proceed.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new CheckedProceedThrowable(t);
        }
    }

    /**
     * 切面入口调用：捕获载体并拆包重抛原始受检异常。
     */
    public static void rethrowIfWrapped(RuntimeException e) throws Throwable {
        if (e instanceof CheckedProceedThrowable wrapper) {
            throw wrapper.getCause();
        }
        throw e;
    }

    @FunctionalInterface
    public interface ProceedFunction {
        Object run() throws Throwable;
    }

    private static final class CheckedProceedThrowable extends RuntimeException {
        CheckedProceedThrowable(Throwable cause) {
            super(cause);
        }
    }
}
