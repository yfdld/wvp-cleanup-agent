package com.genersoft.iot.vmp.agent.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 磁盘监控，检测录像所在磁盘的使用率
 */
public class DiskMonitor {

    private static final Logger logger = LoggerFactory.getLogger(DiskMonitor.class);

    private final Path recordRoot;

    public DiskMonitor(Path recordRoot) {
        this.recordRoot = recordRoot;
    }

    /**
     * 获取录像目录所在磁盘的使用率（百分比，0-100）
     */
    public double getUsagePercent() {
        try {
            FileStore store = Files.getFileStore(recordRoot);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) {
                return 0;
            }
            double used = total - usable;
            return (used / total) * 100.0;
        } catch (IOException e) {
            logger.error("获取磁盘使用率失败", e);
            return -1;
        }
    }

    /**
     * 获取磁盘总容量（字节）
     */
    public long getTotalSpace() {
        try {
            return Files.getFileStore(recordRoot).getTotalSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 计算需要释放的空间（字节），使磁盘使用率降到阈值以下
     */
    public long getRequiredFreeSpace(double thresholdPercent) {
        try {
            FileStore store = Files.getFileStore(recordRoot);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            double currentPercent = ((double)(total - usable) / total) * 100.0;
            if (currentPercent <= thresholdPercent) {
                return 0;
            }
            double targetUsed = total * (thresholdPercent / 100.0);
            return (long)((total - usable) - targetUsed) + 1;
        } catch (IOException e) {
            logger.error("计算需释放空间失败", e);
            return -1;
        }
    }
}