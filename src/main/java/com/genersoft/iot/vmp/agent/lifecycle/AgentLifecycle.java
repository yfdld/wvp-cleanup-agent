package com.genersoft.iot.vmp.agent.lifecycle;

import com.genersoft.iot.vmp.agent.cleanup.CleanupEngine;
import com.genersoft.iot.vmp.agent.cleanup.DiskMonitor;
import com.genersoft.iot.vmp.agent.cleanup.PathGuard;
import com.genersoft.iot.vmp.agent.config.AgentConfig;
import com.genersoft.iot.vmp.agent.sync.PendingStore;
import com.genersoft.iot.vmp.agent.sync.SyncManager;
import com.genersoft.iot.vmp.agent.wvp.WvpApiClient;
import com.genersoft.iot.vmp.agent.zlm.ZlmApiClient;
import com.genersoft.iot.vmp.agent.zlm.ZlmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent生命周期管理，协调各模块的启动、运行和停止
 */
public class AgentLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AgentLifecycle.class);

    private final AgentConfig config;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);

    private ZlmApiClient zlmClient;
    private ZlmConfig zlmConfig;
    private WvpApiClient wvpClient;
    private PathGuard pathGuard;
    private DiskMonitor diskMonitor;
    private CleanupEngine cleanupEngine;
    private PendingStore pendingStore;
    private SyncManager syncManager;

    public AgentLifecycle(AgentConfig config) {
        this.config = config;
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "agent-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动Agent
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("Agent已在运行中");
            return;
        }

        logger.info("========================================");
        logger.info("WVP Cleanup Agent 启动中...");
        logger.info("========================================");

        zlmClient = new ZlmApiClient(
                config.getZlm().getHost(),
                config.getZlm().getSslPort(),
                config.getZlm().getSecret()
        );

        connectToZlm();

        initModules();

        startCleanupTask();

        startHeartbeatTask();

        startConfigRefreshTask();

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        logger.info("========================================");
        logger.info("Agent启动完成");
        logger.info("  录像根目录: {}", zlmConfig.getRecordPath());
        logger.info("  磁盘阈值: {}%", config.getCleanup().getDiskThreshold());
        logger.info("  检查间隔: {}秒", config.getCleanup().getCheckInterval());
        logger.info("  WVP地址: {}", zlmConfig.getWvpUrl());
        logger.info("  节点ID: {}", zlmConfig.getMediaServerId());
        logger.info("========================================");
    }

    /**
     * 连接ZLM获取配置，失败则无限重试
     */
    private void connectToZlm() {
        int retryInterval = config.getZlm().getRetryInterval();
        int attempt = 0;

        while (running.get()) {
            attempt++;
            logger.info("正在连接ZLM获取配置... (第{}次)", attempt);

            zlmConfig = zlmClient.fetchServerConfig();

            if (zlmConfig != null && zlmConfig.isValid()) {
                logger.info("ZLM配置获取成功");
                logger.info("  mp4_save_path: {}", zlmConfig.getMp4SavePath());
                logger.info("  appName: {}", zlmConfig.getAppName());
                logger.info("  mediaServerId: {}", zlmConfig.getMediaServerId());
                logger.info("  WVP地址: {}", zlmConfig.getWvpUrl());
                return;
            }

            if (zlmConfig == null) {
                logger.warn("ZLM不可达，{}秒后重试...", retryInterval);
            } else {
                logger.warn("ZLM配置不完整，{}秒后重试...", retryInterval);
            }

            try {
                Thread.sleep(retryInterval * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 初始化各模块
     */
    private void initModules() {
        pathGuard = new PathGuard(zlmConfig.getRecordPath());
        diskMonitor = new DiskMonitor(pathGuard.getRecordRoot());
        wvpClient = new WvpApiClient(zlmConfig.getWvpUrl(), zlmConfig.getMediaServerId());

        pendingStore = new PendingStore(
                "./data",
                config.getSync().getPendingMaxCount(),
                config.getSync().getMaxRetries(),
                config.getSync().getMaxRetainHours()
        );

        syncManager = new SyncManager(
                wvpClient,
                pendingStore,
                config.getSync().getHeartbeatInterval()
        );

        cleanupEngine = new CleanupEngine(
                pathGuard,
                diskMonitor,
                wvpClient,
                pendingStore,
                config.getCleanup().getDiskThreshold(),
                config.getCleanup().getMaxDeleteCount(),
                config.getCleanup().getMaxDbCleanCount(),
                config.getCleanup().isCleanEmptyDirs()
        );
    }

    /**
     * 启动定时清理任务
     */
    private void startCleanupTask() {
        int interval = config.getCleanup().getCheckInterval();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (cleanupStarted.compareAndSet(false, true)) {
                    logger.info("清理任务已启动");
                }
                cleanupEngine.execute();
            } catch (Exception e) {
                logger.error("清理任务执行异常", e);
            }
        }, 10, interval, TimeUnit.SECONDS);
        logger.info("清理任务已调度，间隔: {}秒", interval);
    }

    /**
     * 启动WVP心跳检测任务
     */
    private void startHeartbeatTask() {
        int interval = config.getSync().getHeartbeatInterval();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                syncManager.heartbeat();
            } catch (Exception e) {
                logger.error("心跳检测异常", e);
            }
        }, 5, interval, TimeUnit.SECONDS);
        logger.info("心跳检测已调度，间隔: {}秒", interval);
    }

    /**
     * 启动ZLM配置刷新任务
     */
    private void startConfigRefreshTask() {
        int interval = config.getZlm().getConfigRefreshInterval();
        if (interval <= 0) {
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                ZlmConfig newConfig = zlmClient.fetchServerConfig();
                if (newConfig != null && newConfig.isValid()) {
                    String newPath = newConfig.getRecordPath();
                    String oldPath = zlmConfig.getRecordPath();
                    if (!newPath.equals(oldPath)) {
                        logger.warn("ZLM录像路径已变更: {} -> {}", oldPath, newPath);
                        zlmConfig = newConfig;
                        initModules();
                    }
                }
            } catch (Exception e) {
                logger.debug("ZLM配置刷新失败: {}", e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
        logger.info("ZLM配置刷新已调度，间隔: {}秒", interval);
    }

    /**
     * 停止Agent
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        logger.info("Agent正在停止...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Agent已停止");
    }

    /**
     * 阻塞等待直到Agent停止
     */
    public void await() {
        while (running.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}