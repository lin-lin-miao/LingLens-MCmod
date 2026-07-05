package com.linglens.manager;

import com.linglens.annotation.IdleTick;
import com.linglens.annotation.IdleTickSave;
import com.linglens.config.ConfigManager;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * 空闲 Tick 管理器
 * <p>
 * 用于在服务器 Tick 负载低时执行非关键性任务。<br>
 * 所有注册的任务都是持久有效的(不会因执行一次而消失),直到手动反注册或重置。
 * <ul>
 * <li><b>@IdleTick 任务</b>:每次空闲 Tick 会<b>遍历执行所有</b>已注册的空闲任务。</li>
 * <li><b>@IdleTickSave 任务</b>:到达 {@link #TARGET_INTERVAL_TICKS} 时开始一轮保存,
 * 每个空闲 Tick 仅执行<b>一个</b>保存任务(轮询方式),完成整轮后重置计数器,等待下一轮；
 * 若超过 {@link #MAX_INTERVAL_TICKS} 仍未完成,则进入强制模式,每个 Tick 执行一个。</li>
 * </ul>
 * <br>
 * 使用方式(二选一):
 * 
 * <pre>{@code
 * // 方式一:手动注册
 * IdleTickManager.registerIdleTask(() -> myCache.refresh());
 * IdleTickManager.registerSaveTask(() -> myDataStore.save());
 *
 * // 方式二:注解自动注册(推荐)
 * // 1. 在任意类的静态方法上添加 @IdleTick 或 @IdleTickSave
 * // 2. 初始化时调用 IdleTickManager.registerAnnotatedMethods(YourClass.class);
 * }</pre>
 */
public class IdleTickManager {

    // ========== 可调参数（默认值，可通过 ConfigManager 运行时修改）==========
    /** 理想间隔:30分(36000 tick),空闲达到此间隔时开始执行保存任务循环（默认值） */
    private static final int DEFAULT_TARGET_INTERVAL_TICKS = 36000;

    /** 硬性最大间隔:60分(72000 tick),若保存循环未完成则强制每个Tick执行一个（默认值） */
    private static final int DEFAULT_MAX_INTERVAL_TICKS = 72000;

    /** 空闲阈值:平均Tick耗时小于45ms视为空闲(跑满20TPS)（默认值） */
    private static final double DEFAULT_IDLE_THRESHOLD_MS = 45.0;
    // ===============================

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /** Tick计数器 */
    private static int ticksCounter = 0;

    /** 空闲任务列表:每次空闲Tick时遍历全部执行(持久有效) */
    private static final List<Consumer<MinecraftServer>> idleTasks = new CopyOnWriteArrayList<>();

    /** 保存任务列表:持久有效,到达间隔时开始一轮轮询(持久有效) */
    private static final List<Consumer<MinecraftServer>> saveTasks = new CopyOnWriteArrayList<>();

    /** 当前保存循环中下一个要执行的任务索引 */
    private static int saveTaskIndex = 0;

    /** 是否正处于保存任务循环中(达到间隔后为 true,完成整轮后为 false) */
    private static boolean saveCycleActive = false;

    /** 是否处于强制保存模式(达到最大间隔,每个Tick必须执行一个保存任务) */
    private static boolean forceSaving = false;

    /** 待扫描注解的类列表(各模块在静态块中注册,服务器启动后由 onTickEnd 自动扫描) */
    private static final Set<Class<?>> pendingClasses = new CopyOnWriteArraySet<>();

    /** 是否已完成对 pendingClasses 的扫描 */
    private static boolean pendingScanned = false;

    // ========== 注册 / 取消注册 ==========

    /**
     * 将一个类注册到待扫描列表中,等待服务器启动后由 onTickEnd 自动扫描。
     * <p>
     * 适用于在类的静态初始化块中调用,无需外部手动注册。
     * </p>
     *
     * @param clazz 包含 @IdleTick 或 @IdleTickSave 注解的类
     */
    public static void registerPendingClass(Class<?> clazz) {
        pendingClasses.add(clazz);
        LOGGER.debug("[LingLens] 注册待扫描类: {}", clazz.getName());
    }

    /**
     * 注册一个空闲Tick时执行的任务(持久有效,不会因执行而移除)。
     * <p>
     * 每次空闲Tick都会遍历执行【所有】已注册的空闲任务。
     * </p>
     *
     * @param task 要注册的任务(接受 MinecraftServer 参数)
     */
    public static void registerIdleTask(Consumer<MinecraftServer> task) {
        idleTasks.add(task);
        LOGGER.debug("[LingLens] 注册空闲任务(Consumer),当前空闲任务数: {}", idleTasks.size());
    }

    /**
     * 注册一个空闲Tick时执行的任务(Runnable 便捷重载)。
     *
     * @param task 要注册的任务(无参数)
     */
    public static void registerIdleTask(Runnable task) {
        idleTasks.add((server) -> task.run());
        LOGGER.debug("[LingLens] 注册空闲任务(Runnable),当前空闲任务数: {}", idleTasks.size());
    }

    /**
     * 注册一个保存任务(持久有效,不会因执行而移除)。
     * <p>
     * 到达 {@link #TARGET_INTERVAL_TICKS} 时开始一轮保存循环,
     * 每个空闲Tick仅执行一个保存任务(轮询方式),整轮完成后重置计数器；
     * 若超过 {@link #MAX_INTERVAL_TICKS} 仍未完成,则强制每个Tick执行一个。
     * </p>
     *
     * @param task 要注册的保存任务(接受 MinecraftServer 参数)
     */
    public static void registerSaveTask(Consumer<MinecraftServer> task) {
        saveTasks.add(task);
        LOGGER.debug("[LingLens] 注册保存任务(Consumer),当前保存任务数: {}", saveTasks.size());
    }

    /**
     * 注册一个保存任务(Runnable 便捷重载)。
     *
     * @param task 要注册的保存任务(无参数)
     */
    public static void registerSaveTask(Runnable task) {
        saveTasks.add((server) -> task.run());
        LOGGER.debug("[LingLens] 注册保存任务(Runnable),当前保存任务数: {}", saveTasks.size());
    }

    /**
     * 移除指定的空闲任务(Consumer 重载)
     *
     * @param task 之前注册的任务(需为同一对象引用)
     * @return 是否移除成功
     */
    public static boolean unregisterIdleTask(Consumer<MinecraftServer> task) {
        boolean removed = idleTasks.remove(task);
        if (removed) {
            LOGGER.debug("[LingLens] 移除空闲任务(Consumer),当前空闲任务数: {}", idleTasks.size());
        }
        return removed;
    }

    /**
     * 移除指定的空闲任务(Runnable 重载)
     *
     * @param task 之前注册的任务(需为同一对象引用)
     * @return 是否移除成功
     */
    public static boolean unregisterIdleTask(Runnable task) {
        boolean removed = idleTasks.remove(task);
        if (removed) {
            LOGGER.debug("[LingLens] 移除空闲任务(Runnable),当前空闲任务数: {}", idleTasks.size());
        }
        return removed;
    }

    /**
     * 移除指定的保存任务(Consumer 重载)
     *
     * @param task 之前注册的任务(需为同一对象引用)
     * @return 是否移除成功
     */
    public static boolean unregisterSaveTask(Consumer<MinecraftServer> task) {
        boolean removed = saveTasks.remove(task);
        if (removed) {
            LOGGER.debug("[LingLens] 移除保存任务(Consumer),当前保存任务数: {}", saveTasks.size());
        }
        return removed;
    }

    /**
     * 移除指定的保存任务(Runnable 重载)
     *
     * @param task 之前注册的任务(需为同一对象引用)
     * @return 是否移除成功
     */
    public static boolean unregisterSaveTask(Runnable task) {
        boolean removed = saveTasks.remove(task);
        if (removed) {
            LOGGER.debug("[LingLens] 移除保存任务(Runnable),当前保存任务数: {}", saveTasks.size());
        }
        return removed;
    }

    /**
     * 获取已注册的保存任务总数
     *
     * @return 任务数量
     */
    public static int getSaveTaskCount() {
        return saveTasks.size();
    }

    /**
     * 获取已注册的空闲任务总数
     *
     * @return 任务数量
     */
    public static int getIdleTaskCount() {
        return idleTasks.size();
    }

    /**
     * 获取当前保存循环中剩余待执行的任务数(从当前索引到列表末尾)
     *
     * @return 剩余任务数,若不在保存循环中则返回 0
     */
    public static int getRemainingInCycle() {
        if (!saveCycleActive)
            return 0;
        int remaining = saveTasks.size() - saveTaskIndex;
        return Math.max(remaining, 0);
    }

    /**
     * 重置所有状态(常用于服务器重载或调试)
     */
    public static void reset() {
        ticksCounter = 0;
        saveTaskIndex = 0;
        saveCycleActive = false;
        forceSaving = false;
        idleTasks.clear();
        saveTasks.clear();
        LOGGER.info("[LingLens] 空闲Tick管理器已重置");
    }

    // ========== 注解扫描注册 ==========

    /**
     * 扫描指定类中带有 {@link IdleTick} 和 {@link IdleTickSave} 注解的静态方法,
     * 并将它们自动注册到空闲/保存任务列表中。
     * <p>
     * 要求被注解的方法满足:
     * <ul>
     * <li>修饰符为 {@code public static}</li>
     * <li>返回类型为 {@code void}</li>
     * <li>参数列表为空 或 单参数且类型为 {@code MinecraftServer}</li>
     * </ul>
     * 不满足条件的方法会被跳过并记录警告。
     * </p>
     *
     * @param clazz 包含注解方法的类(通常在主类初始化时传入)
     */
    public static void registerAnnotatedMethods(Class<?> clazz) {
        Method[] methods = clazz.getDeclaredMethods();
        for (Method method : methods) {
            // 检查是否为注解目标
            boolean hasIdleTick = method.isAnnotationPresent(IdleTick.class);
            boolean hasIdleTickSave = method.isAnnotationPresent(IdleTickSave.class);
            if (!hasIdleTick && !hasIdleTickSave) {
                continue;
            }

            // 校验修饰符和返回类型
            int mod = method.getModifiers();
            if (!Modifier.isPublic(mod) || !Modifier.isStatic(mod)
                    || !method.getReturnType().equals(void.class)) {
                LOGGER.warn("[LingLens] 方法 {}.{} 被注解标记但修饰符/返回类型不符合条件" +
                        "(需为 public static void),已跳过",
                        clazz.getSimpleName(), method.getName());
                continue;
            }

            // 校验参数:无参 或 单参数且类型为 MinecraftServer
            Class<?>[] paramTypes = method.getParameterTypes();
            boolean takesServer = false;
            if (paramTypes.length == 0) {
                takesServer = false;
            } else if (paramTypes.length == 1 && paramTypes[0] == MinecraftServer.class) {
                takesServer = true;
            } else {
                LOGGER.warn("[LingLens] 方法 {}.{} 被注解标记但参数不符合条件" +
                        "(需为无参 或 单参数 MinecraftServer),已跳过",
                        clazz.getSimpleName(), method.getName());
                continue;
            }

            final boolean finalTakesServer = takesServer;
            Consumer<MinecraftServer> task = (server) -> {
                try {
                    method.setAccessible(true);
                    if (finalTakesServer) {
                        method.invoke(null, server);
                    } else {
                        method.invoke(null);
                    }
                } catch (Exception e) {
                    LOGGER.error("[LingLens] 执行注解方法 {}.{} 异常", clazz.getSimpleName(), method.getName(), e);
                }
            };

            if (hasIdleTick) {
                registerIdleTask(task);
                LOGGER.debug("[LingLens] 通过 @IdleTick 注册方法: {}.{}", clazz.getSimpleName(), method.getName());
            }
            if (hasIdleTickSave) {
                registerSaveTask(task);
                LOGGER.debug("[LingLens] 通过 @IdleTickSave 注册方法: {}.{}", clazz.getSimpleName(), method.getName());
            }
            LOGGER.info("[LingLens] 注册空闲Tick:{} 注册空闲Save:{}", idleTasks.size(), saveTasks.size());
        }
    }

    /**
     * 扫描所有已注册的待扫描类(仅在第一次调用时执行)。
     * 此方法由 {@link #onTickEnd(MinecraftServer)} 自动调用。
     */
    public static void scanPendingIfNeeded() {
        if (pendingScanned)
            return;
        pendingScanned = true;
        if (pendingClasses.isEmpty()) {
            LOGGER.debug("[LingLens] 待扫描类列表为空,跳过");
            return;
        }
        LOGGER.info("[LingLens] 开始扫描注册,共 {} 个", pendingClasses.size());
        for (Class<?> clazz : pendingClasses) {
            registerAnnotatedMethods(clazz);
        }
        pendingClasses.clear();
    }

    // ========== 核心 Tick 逻辑 ==========

    /**
     * 在每个Tick结束时调用,根据当前服务器负载决定是否执行任务。
     * <ol>
     * <li>每Tick累计计数</li>
     * <li>空闲且无强制/保存循环时:遍历执行所有 idleTasks</li>
     * <li>空闲且处于保存循环时:执行一个 saveTask,索引后移,完成整轮后重置循环</li>
     * <li>达到 {@link #TARGET_INTERVAL_TICKS} 时:启动保存循环(仅在空闲时执行)</li>
     * <li>达到 {@link #MAX_INTERVAL_TICKS} 时:进入强制模式,每个Tick执行一个保存任务</li>
     * </ol>
     *
     * @param server Minecraft服务器实例(用于获取平均Tick时间)
     */
    public static void onTickEnd(MinecraftServer server) {

        ConfigManager cfg = ConfigManager.getInstance();
        double avgTickTime = server.getAverageTickTime();
        boolean isIdle = avgTickTime / 1_000_000.0 < cfg.getIdleThresholdMs();

        // ====================== 1. 强制保存模式 ======================
        if (forceSaving) {
            // 强制模式下只执行保存任务,每个Tick执行一个,不执行空闲任务
            if (saveTaskIndex < saveTasks.size()) {
                Consumer<MinecraftServer> task = saveTasks.get(saveTaskIndex);
                saveTaskIndex++;
                try {
                    task.accept(server);
                    LOGGER.debug("[LingLens] [强制保存] 执行保存任务[{}/{}]",
                            saveTaskIndex, saveTasks.size());
                } catch (Exception e) {
                    LOGGER.error("[LingLens] 强制保存任务执行异常 [index={}] " + task.getClass().getName(), e);
                }

                // 检查本轮是否完成
                if (saveTaskIndex >= saveTasks.size()) {
                    // 本轮全部执行完毕,退出强制模式
                    forceSaving = false;
                    saveCycleActive = false;
                    saveTaskIndex = 0;
                    ticksCounter = 0;
                    LOGGER.debug("[LingLens] 强制保存整轮完成,退出强制模式");
                }
            } else {
                // 理论上不应进入此分支,但作为防御
                forceSaving = false;
                saveCycleActive = false;
                saveTaskIndex = 0;
                ticksCounter = 0;
            }
            return; // 强制模式期间不执行其他逻辑
        }

        // ====================== 2. 累计Tick ======================
        ticksCounter++;

        // ====================== 3. 空闲处理 ======================
        if (isIdle) {
            // 3a. 遍历执行所有 idleTasks(持久有效,不消费)
            for (Consumer<MinecraftServer> task : idleTasks) {
                try {
                    task.accept(server);
                } catch (Exception e) {
                    LOGGER.error("[LingLens] 空闲任务执行异常: " + task.getClass().getName(), e);
                }
            }

            // 3b. 如果处于保存循环中,执行一个保存任务
            if (saveCycleActive && saveTaskIndex < saveTasks.size()) {
                Consumer<MinecraftServer> task = saveTasks.get(saveTaskIndex);
                saveTaskIndex++;
                try {
                    task.accept(server);
                    LOGGER.debug("[LingLens] [空闲保存] 执行保存任务[{}/{}]",
                            saveTaskIndex, saveTasks.size());
                } catch (Exception e) {
                    LOGGER.error("[LingLens] 空闲保存任务执行异常 [index={}] " + task.getClass().getName(), e);
                }

                // 检查本轮是否完成
                if (saveTaskIndex >= saveTasks.size()) {
                    saveCycleActive = false;
                    saveTaskIndex = 0;
                    ticksCounter = 0;
                    LOGGER.debug("[LingLens] 空闲保存整轮完成,重置计数器");
                }
            }
        }

        // ====================== 4. 间隔判断 ======================
        if (ticksCounter >= cfg.getIdleMaxIntervalTicks()) {
            // 达到硬性最大间隔:无论是否空闲,进入强制保存模式
            // 重置索引到当前进度(与 saveCycleActive 状态一致)
            forceSaving = true;
            saveCycleActive = true; // 确保同步
            // 立即在当前Tick执行一个保存任务
            if (saveTaskIndex < saveTasks.size()) {
                Consumer<MinecraftServer> task = saveTasks.get(saveTaskIndex);
                saveTaskIndex++;
                try {
                    task.accept(server);
                    LOGGER.debug("[LingLens] [进入强制保存] 执行保存任务[{}/{}]",
                            saveTaskIndex, saveTasks.size());
                } catch (Exception e) {
                    LOGGER.error("[LingLens] 进入强制模式时保存任务执行异常 [index={}] " + task.getClass().getName(), e);
                }

                if (saveTaskIndex >= saveTasks.size()) {
                    forceSaving = false;
                    saveCycleActive = false;
                    saveTaskIndex = 0;
                    ticksCounter = 0;
                }
            } else {
                // 没有保存任务,直接重置
                forceSaving = false;
                saveCycleActive = false;
                saveTaskIndex = 0;
                ticksCounter = 0;
            }
        } else if (isIdle && !saveCycleActive && ticksCounter >= cfg.getIdleTargetIntervalTicks()) {
            // 空闲、不在保存循环中、达到理想间隔:启动保存循环
            // 前提:有保存任务可执行
            if (!saveTasks.isEmpty()) {
                saveCycleActive = true;
                saveTaskIndex = 0;
                LOGGER.debug("[LingLens] 启动保存循环,共 {} 个任务", saveTasks.size());
                // 立即在当前空闲Tick执行第一个保存任务
                Consumer<MinecraftServer> task = saveTasks.get(saveTaskIndex);
                saveTaskIndex++;
                try {
                    task.accept(server);
                    LOGGER.debug("[LingLens] [启动保存] 执行保存任务[1/{}]", saveTasks.size());
                } catch (Exception e) {
                    LOGGER.error("[LingLens] 启动保存时任务执行异常 [index=0] " + task.getClass().getName(), e);
                }

                if (saveTaskIndex >= saveTasks.size()) {
                    // 只有一个保存任务,一轮完成
                    saveCycleActive = false;
                    saveTaskIndex = 0;
                    ticksCounter = 0;
                    LOGGER.debug("[LingLens] 保存循环完成(单任务)");
                }
            } else {
                // 没有注册任何保存任务,直接重置计数器
                ticksCounter = 0;
            }
        }
    }
}
