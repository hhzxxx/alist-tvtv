package cn.har01d.alist_tvbox.aop;

import cn.har01d.alist_tvbox.annotation.CacheCheck;
import cn.har01d.alist_tvbox.service.IRedisService;
import cn.har01d.alist_tvbox.util.MD5Utils;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

@Aspect
@Component
@Order(8778)
@Slf4j
public class CacheAspect {
    @Autowired
    private IRedisService redisService;


    @Pointcut("execution(public * cn.har01d.alist_tvbox.service..*.*(..))")
    public void service() {
    }

    @Around("service()")
    public Object checkCache(ProceedingJoinPoint joinPoint) throws Throwable {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        String className = targetClass.getSimpleName();
        Method method = this.getMethod(joinPoint);
        CacheCheck cacheCheck = (CacheCheck)method.getAnnotation(CacheCheck.class);
        if(cacheCheck != null){
            long exTime = cacheCheck.exTime();
            Boolean hasArgs = false;
            // 获取目标方法的入参
            Object[] args = joinPoint.getArgs();
            StringBuilder key = new StringBuilder();
            key.append("cache:").append(className).append(":").append(method.getName());
            StringBuilder pkey = new StringBuilder();
            // 遍历参数列表，查找含有特定注解的参数
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            for (int i = 0; i < args.length; i++) {
                Annotation[] annotations = parameterAnnotations[i];
                for (Annotation annotation : annotations) {
                    if (annotation instanceof CacheCheck) {
                        Object arg = args[i];
                        if(arg instanceof ServletUriComponentsBuilder){
                            pkey.append(MD5Utils.md5(((ServletUriComponentsBuilder)arg).build()
                                    .getHost()));
                        }else {
                            pkey.append(MD5Utils.md5(JSON.toJSONString(arg)));
                        }
                        hasArgs = true;
                    }
                }
            }
            if(hasArgs){
                key.append(":").append(MD5Utils.md5(pkey.toString()));
            }
            Object cache = null;
            try {
                cache = redisService.get(key.toString());
            }catch (Exception e){
                return joinPoint.proceed();
            }
            if(cache == null){
                Object result = joinPoint.proceed();
                try {
                    redisService.set(key.toString(),result,60*60*24*3);
                    if( exTime > 0){
                        redisService.set(key.toString().replace("cache","extime"),exTime,exTime);
                    }
                }catch (Exception ignore){
                    return result;
                }
                return result;
            }else {
                if( exTime > 0){
                    if(!redisService.hasKey(key.toString().replace("cache","extime"))){
                        new Thread(() -> {
                            Object result = null;
                            try {
                                result = joinPoint.proceed();
                            } catch (Throwable e) {
                                throw new RuntimeException(e);
                            }
                            try {
                                redisService.set(key.toString(),result,60*60*24*3);
                                redisService.set(key.toString().replace("cache","extime"),exTime,exTime);
                            }catch (Exception ignore){
                            }
                        }).start();
                    }
                }
                return cache;
            }
        }else {
            return joinPoint.proceed();
        }
    }










    protected Method getMethod(JoinPoint joinPoint) {
        Signature sig = joinPoint.getSignature();
        MethodSignature msig = null;
        if (!(sig instanceof MethodSignature)) {
            throw new IllegalArgumentException("该注解只能用于方法");
        } else {
            msig = (MethodSignature)sig;
            Object target = joinPoint.getTarget();

            try {
                return target.getClass().getMethod(msig.getName(), msig.getParameterTypes());
            } catch (NoSuchMethodException | SecurityException var6) {
                log.error(var6.getMessage(), var6);
            }

            return null;
        }
    }
}
