package com.linglens.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个静态方法为“保存 Tick 任务”。<br>
 * 被标记的方法将在空闲 Tick 时排队逐个执行，超出最大间隔则强制每个 Tick 执行一个。<br>
 * 方法签名必须为 {@code public static void}，且满足以下条件之一：
 * <ul>
 *   <li>无参数</li>
 *   <li>单参数 {@code MinecraftServer server}（可选，需要服务器实例时使用）</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdleTickSave {
}