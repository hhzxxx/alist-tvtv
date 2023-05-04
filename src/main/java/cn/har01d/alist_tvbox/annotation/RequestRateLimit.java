package cn.har01d.alist_tvbox.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestRateLimit {
    int value() default 10; // 设置默认的请求限制次数
    int duration() default 60; // 设置默认的时间窗口大小（单位：秒）
}