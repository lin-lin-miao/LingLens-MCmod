package com.linglens.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个静态方法为“空闲 Tick 任务”。<br>
 * 被标记的方法将在服务器空闲 Tick 时被自动调用（每次空闲所有标记方法按声明顺序执行）。<br>
 * 方法签名必须为 {@code public static void}，且满足以下条件之一：
 * <ul>
 *   <li>无参数</li>
 *   <li>单参数 {@code MinecraftServer server}（可选，需要服务器实例时使用）</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdleTick {
}