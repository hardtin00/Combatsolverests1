package com.evacipated.cardcrawl.modthespire.lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SpirePatch {
    Class<?> clz() default SpirePatch.class;
    String cls() default "";
    String method();
    Class<?>[] paramtypez() default {};
    String[] paramtypes() default {};
    String requiredModId() default "";
    boolean optional() default false;
}
