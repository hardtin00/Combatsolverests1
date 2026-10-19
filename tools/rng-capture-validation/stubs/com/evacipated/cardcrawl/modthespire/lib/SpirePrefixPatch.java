package com.evacipated.cardcrawl.modthespire.lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-only symbol. This stub is excluded from the probe JAR. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SpirePrefixPatch {
}
