package com.genersoft.iot.vmp.agent.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径安全守卫，确保所有文件操作不超出录像根目录
 */
public class PathGuard {

    private static final Logger logger = LoggerFactory.getLogger(PathGuard.class);

    private final Path recordRoot;

    public PathGuard(String recordPath) {
        try {
            this.recordRoot = Paths.get(recordPath).toRealPath();
            if (!Files.isDirectory(recordRoot)) {
                throw new IllegalStateException("录像根目录不存在或不是目录: " + recordRoot);
            }
            logger.info("录像根目录: {}", recordRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法解析录像根目录: " + recordPath, e);
        }
    }

    /**
     * 获取录像根目录
     */
    public Path getRecordRoot() {
        return recordRoot;
    }

    /**
     * 校验目标路径是否在录像根目录内
     */
    public boolean isSafe(Path target) {
        try {
            Path realTarget = target.toRealPath();
            return realTarget.startsWith(recordRoot);
        } catch (IOException e) {
            logger.warn("无法解析路径: {}", target);
            return false;
        }
    }

    /**
     * 安全删除文件，不在录像根目录内的文件拒绝删除
     */
    public boolean deleteFile(Path file) {
        if (!isSafe(file)) {
            logger.warn("拒绝删除范围外文件: {}", file);
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                logger.debug("已删除文件: {}", file);
            }
            return deleted;
        } catch (IOException e) {
            logger.warn("删除文件失败: {} - {}", file, e.getMessage());
            return false;
        }
    }

    /**
     * 安全删除空目录，不超出录像根目录，不删除根目录本身
     */
    public boolean deleteEmptyDir(Path dir) {
        if (!isSafe(dir)) {
            logger.warn("拒绝删除范围外目录: {}", dir);
            return false;
        }
        if (dir.equals(recordRoot)) {
            return false;
        }
        try {
            String[] children = dir.toFile().list();
            if (children != null && children.length == 0) {
                boolean deleted = Files.deleteIfExists(dir);
                if (deleted) {
                    logger.debug("已删除空目录: {}", dir);
                }
                return deleted;
            }
        } catch (IOException e) {
            logger.warn("删除空目录失败: {} - {}", dir, e.getMessage());
        }
        return false;
    }
}