//package cn.har01d.alist_tvbox.aop;
//
//import cn.har01d.alist_tvbox.annotation.RequestRateLimit;
//import org.aspectj.lang.ProceedingJoinPoint;
//import org.aspectj.lang.annotation.*;
//import org.springframework.stereotype.Component;
//
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//@Aspect
//@Component
//public class RequestRateLimitAspect {
//    private static final Map<String, Map<String, Integer>> requestCountMap = new ConcurrentHashMap<>();
//
//    @Pointcut("@annotation(requestRateLimit)")
//    public void requestRateLimitPointcut(RequestRateLimit requestRateLimit) {
//    }
//
//    @Around(value = "requestRateLimitPointcut(requestRateLimit)", argNames = "joinPoint,requestRateLimit")
//    public Object enforceRequestRateLimit(ProceedingJoinPoint joinPoint, RequestRateLimit requestRateLimit) throws Throwable {
//        String methodName = joinPoint.getSignature().toShortString();
//        String key = joinPoint.getTarget().getClass().getName() + methodName;
//
//        int limit = requestRateLimit.value();
//        int duration = requestRateLimit.duration();
//
//        if (!requestCountMap.containsKey(key)) {
//            requestCountMap.put(key, new HashMap<>());
//        }
//
//        Map<String, Integer> methodRequestCountMap = requestCountMap.get(key);
//        long currentTime = System.currentTimeMillis() / 1000; // 当前时间戳（秒）
//        int requestCount = methodRequestCountMap.getOrDefault(String.valueOf(currentTime), 0);
//
//        if (requestCount >= limit) {
//            throw new RuntimeException("请求频率超过限制");
//        }
//
//        methodRequestCountMap.put(String.valueOf(currentTime), requestCount + 1);
//
//        // 清理旧的请求计数器
//        long expirationTime = currentTime - duration;
//        methodRequestCountMap.entrySet().removeIf(entry -> Long.parseLong(entry.getKey()) < expirationTime);
//
//        return joinPoint.proceed();
//    }
//}
