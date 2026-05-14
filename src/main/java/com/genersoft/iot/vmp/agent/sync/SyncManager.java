package com.genersoft.iot.vmp.agent.sync;

import com.genersoft.iot.vmp.agent.wvp.WvpApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步管理器，负责pending记录的同步和WVP心跳检测
 */
public class SyncManager {

    private static final Logger logger = LoggerFactory.getLogger(SyncManager.class);

    private final WvpApiClient wvpClient;
    private final PendingStore pendingStore;
    private final int heartbeatInterval;

    private volatile boolean wvpOnline = false;

    public SyncManager(WvpApiClient wvpClient, PendingStore pendingStore, int heartbeatInterval) {
        this.wvpClient = wvpClient;
        this.pendingStore = pendingStore;
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * 检查WVP是否在线
     */
    public boolean isWvpOnline() {
        return wvpOnline;
    }

    /**
     * 执行心跳检测
     */
    public void heartbeat() {
        boolean reachable = wvpClient.isReachable();
        if (reachable && !wvpOnline) {
            logger.info("WVP已恢复连接");
            wvpOnline = true;
            flushPending();
        } else if (!reachable && wvpOnline) {
            logger.warn("WVP连接断开");
            wvpOnline = false;
        } else if (reachable) {
            wvpOnline = true;
        } else {
            wvpOnline = false;
        }
    }

    /**
     * 同步所有pending记录到WVP
     */
    public void flushPending() {
        List<PendingStore.PendingItem> items = pendingStore.getAll();
        if (items.isEmpty()) {
            return;
        }

        logger.info("开始同步 {} 条pending记录到WVP", items.size());
        List<String> filePaths = new ArrayList<>();
        for (PendingStore.PendingItem item : items) {
            filePaths.add(item.filePath);
        }

        WvpApiClient.ConfirmResult confirmResult = wvpClient.confirmDeletion(filePaths);
        boolean success = confirmResult != null && confirmResult.isSuccess();
        if (success) {
            for (String fp : filePaths) {
                pendingStore.markSuccess(fp);
            }
            logger.info("pending记录同步成功: {} 条", filePaths.size());
        } else {
            for (String fp : filePaths) {
                pendingStore.markFailed(fp);
            }
            logger.warn("pending记录同步失败: {} 条", filePaths.size());
        }
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }
}