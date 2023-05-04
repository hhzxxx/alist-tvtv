package cn.har01d.alist_tvbox.annotation;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD,ElementType.PARAMETER})
public @interface CacheCheck {

    /**
     * 过期时间 秒
     */
    long exTime() default 0;
}
