package com.genersoft.iot.vmp.agent.cleanup;

import com.genersoft.iot.vmp.agent.wvp.WvpApiClient;
import com.genersoft.iot.vmp.agent.sync.PendingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 清理引擎，负责扫描录像文件、按需删除、清理空目录、清理孤儿记录
 *
 * 每次检查都执行：
 *   1. 孤儿记录清理：以磁盘最老的已入库MP4为基准，查出数据库中比它还老的记录，
 *      检查文件是否存在，不存在的删除（上限 maxDbCleanCount）
 *   2. 如果磁盘超阈值，删除旧文件释放空间（上限 maxDeleteCount），同步清理数据库
 */
public class CleanupEngine {

    private static final Logger logger = LoggerFactory.getLogger(CleanupEngine.class);

    private final PathGuard pathGuard;
    private final DiskMonitor diskMonitor;
    private final WvpApiClient wvpClient;
    private final PendingStore pendingStore;
    private final double diskThreshold;
    private final int maxDeleteCount;
    private final int maxDbCleanCount;
    private final boolean cleanEmptyDirs;

    public CleanupEngine(PathGuard pathGuard, DiskMonitor diskMonitor,
                         WvpApiClient wvpClient, PendingStore pendingStore,
                         double diskThreshold, int maxDeleteCount,
                         int maxDbCleanCount, boolean cleanEmptyDirs) {
        this.pathGuard = pathGuard;
        this.diskMonitor = diskMonitor;
        this.wvpClient = wvpClient;
        this.pendingStore = pendingStore;
        this.diskThreshold = diskThreshold;
        this.maxDeleteCount = maxDeleteCount;
        this.maxDbCleanCount = maxDbCleanCount;
        this.cleanEmptyDirs = cleanEmptyDirs;
    }

    /**
     * 执行一次清理
     */
    public CleanupResult execute() {
        CleanupResult result = new CleanupResult();

        double usagePercent = diskMonitor.getUsagePercent();
        result.diskUsageBefore = usagePercent;
        logger.info("当前磁盘使用率: {}%, 阈值: {}%", String.format("%.1f", usagePercent), String.format("%.1f", diskThreshold));

        if (usagePercent < 0) {
            logger.error("无法获取磁盘使用率，跳过本次清理");
            return result;
        }

        // 1. 每次检查都做孤儿记录清理
        int orphanCleaned = executeOrphanCleanup();
        result.orphanRecordsCleaned = orphanCleaned;

        // 2. 磁盘超阈值才删文件
        if (usagePercent > diskThreshold) {
            executeFileCleanup(result);
        } else {
            logger.info("磁盘使用率未超阈值，无需清理文件");
        }

        // 3. 清理空目录
        if (cleanEmptyDirs) {
            int dirsRemoved = cleanEmptyDirectories();
            result.emptyDirsRemoved = dirsRemoved;
        }

        double usageAfter = diskMonitor.getUsagePercent();
        result.diskUsageAfter = usageAfter;
        logger.info("清理完成: 删除{}个文件, 释放{}MB, 清理{}个孤儿记录, 磁盘使用率 {}% -> {}%",
                result.deletedCount, result.freedSpace / 1024 / 1024,
                result.orphanRecordsCleaned,
                String.format("%.1f", usagePercent), String.format("%.1f", usageAfter));

        return result;
    }

    /**
     * 孤儿记录清理：
     * 1. 扫描磁盘所有 .mp4 文件，按时间从旧到新排序
     * 2. 从最老的 .mp4 开始，逐个去数据库查有没有对应记录
     * 3. 第一个找到数据库记录的 .mp4 → 用它的 startTime 作为基准
     * 4. 比它还老的数据库记录 → 检查文件是否存在 → 不存在的删掉（上限 maxDbCleanCount）
     * 5. 如果所有 .mp4 都找不到数据库记录 → 跳过
     */
    private int executeOrphanCleanup() {
        List<Path> mp4Files = scanMp4FilesSorted();
        if (mp4Files.isEmpty()) {
            logger.debug("磁盘上没有 .mp4 文件，跳过孤儿记录清理");
            return 0;
        }

        // 从最老的 .mp4 开始，逐个查找数据库记录
        WvpApiClient.OlderRecordsResult olderResult = null;
        for (Path mp4 : mp4Files) {
            String absPath = mp4.toAbsolutePath().toString();
            olderResult = wvpClient.getOlderRecordsByFile(absPath, maxDbCleanCount);
            if (olderResult != null && olderResult.isFound()) {
                logger.info("找到已入库的基准文件: {}, 查出 {} 条更老记录", absPath,
                        olderResult.getOlderRecords() != null ? olderResult.getOlderRecords().size() : 0);
                break;
            }
        }

        if (olderResult == null || !olderResult.isFound()) {
            logger.info("磁盘上的 .mp4 文件均未入库，跳过孤儿记录清理");
            return 0;
        }

        List<WvpApiClient.RecordFileInfo> olderRecords = olderResult.getOlderRecords();
        if (olderRecords == null || olderRecords.isEmpty()) {
            logger.info("无比基准文件更老的记录，无孤儿记录");
            return 0;
        }

        // 检查每条更老记录的文件是否存在
        List<Integer> orphanIds = new ArrayList<>();
        for (WvpApiClient.RecordFileInfo record : olderRecords) {
            Path filePath = Paths.get(record.getFilePath());
            if (!Files.exists(filePath)) {
                orphanIds.add(record.getId());
                logger.debug("发现孤儿记录: id={}, filePath={}", record.getId(), record.getFilePath());
            }
        }

        if (orphanIds.isEmpty()) {
            logger.info("所有更老记录的文件均存在，无孤儿记录");
            return 0;
        }

        // 限制清理数量
        List<Integer> toDelete = orphanIds;
        if (orphanIds.size() > maxDbCleanCount) {
            toDelete = orphanIds.subList(0, maxDbCleanCount);
            logger.warn("孤儿记录 {} 条超过上限 {}, 本次只清理 {} 条",
                    orphanIds.size(), maxDbCleanCount, maxDbCleanCount);
        }

        logger.info("发现 {} 条孤儿记录，正在清理...", toDelete.size());
        boolean deleted = wvpClient.deleteOrphanRecords(toDelete);
        if (deleted) {
            logger.info("成功清理 {} 条孤儿录像记录", toDelete.size());
            return toDelete.size();
        } else {
            logger.warn("孤儿记录清理失败，{} 条记录未处理", toDelete.size());
            return 0;
        }
    }

    /**
     * 文件清理：磁盘超阈值时删除旧文件
     */
    private void executeFileCleanup(CleanupResult result) {
        long requiredFree = diskMonitor.getRequiredFreeSpace(diskThreshold);
        logger.info("需释放空间: {} MB", requiredFree / 1024 / 1024);

        List<Path> filesToDelete = selectFilesToDelete(requiredFree);
        if (filesToDelete.isEmpty()) {
            logger.info("没有可删除的文件");
            return;
        }

        logger.info("待删除文件数: {}", filesToDelete.size());
        List<String> deletedPaths = new ArrayList<>();
        long freedSpace = 0;

        for (Path file : filesToDelete) {
            long fileSize = getFileSize(file);
            if (pathGuard.deleteFile(file)) {
                deletedPaths.add(file.toAbsolutePath().toString());
                freedSpace += fileSize;
                result.deletedCount++;
            }
        }

        result.freedSpace = freedSpace;

        if (!deletedPaths.isEmpty()) {
            WvpApiClient.ConfirmResult confirmResult = wvpClient.confirmDeletion(deletedPaths);
            if (confirmResult != null && confirmResult.isSuccess()) {
                logger.info("WVP同步成功，删除 {} 条录像记录", deletedPaths.size());
            } else {
                logger.warn("WVP同步失败，{} 条记录加入pending队列", deletedPaths.size());
                pendingStore.addAll(deletedPaths);
            }
        }
    }

    /**
     * 扫描所有 .mp4 文件，按修改时间从旧到新排序
     */
    private List<Path> scanMp4FilesSorted() {
        List<PathWithSize> mp4Files = new ArrayList<>();
        Path root = pathGuard.getRecordRoot();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (pathGuard.isSafe(file) && file.toString().toLowerCase().endsWith(".mp4")) {
                        mp4Files.add(new PathWithSize(file, attrs.size(), attrs.lastModifiedTime().toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("扫描MP4文件失败", e);
            return Collections.emptyList();
        }

        mp4Files.sort(Comparator.comparingLong(p -> p.lastModified));

        List<Path> result = new ArrayList<>();
        for (PathWithSize pws : mp4Files) {
            result.add(pws.path);
        }
        return result;
    }

    /**
     * 选择需要删除的文件，按修改时间从旧到新，直到累计大小满足需求或达到最大数量
     */
    private List<Path> selectFilesToDelete(long requiredFree) {
        List<PathWithSize> allFiles = scanAllFiles();
        if (allFiles.isEmpty()) {
            return Collections.emptyList();
        }

        allFiles.sort(Comparator.comparingLong(p -> p.lastModified));

        List<Path> selected = new ArrayList<>();
        long accumulated = 0;

        for (PathWithSize pws : allFiles) {
            if (selected.size() >= maxDeleteCount) {
                break;
            }
            selected.add(pws.path);
            accumulated += pws.size;
            if (accumulated >= requiredFree) {
                break;
            }
        }

        return selected;
    }

    /**
     * 扫描录像根目录下所有文件
     */
    private List<PathWithSize> scanAllFiles() {
        List<PathWithSize> files = new ArrayList<>();
        Path root = pathGuard.getRecordRoot();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (pathGuard.isSafe(file)) {
                        files.add(new PathWithSize(file, attrs.size(), attrs.lastModifiedTime().toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("扫描录像目录失败", e);
        }
        return files;
    }

    /**
     * 清理空目录，从深到浅
     */
    private int cleanEmptyDirectories() {
        int removed = 0;
        Path root = pathGuard.getRecordRoot();
        try {
            List<Path> allDirs = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (pathGuard.isSafe(dir) && !dir.equals(root)) {
                        allDirs.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            allDirs.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));

            for (Path dir : allDirs) {
                if (pathGuard.deleteEmptyDir(dir)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            logger.error("扫描空目录失败", e);
        }
        if (removed > 0) {
            logger.info("清理了 {} 个空目录", removed);
        }
        return removed;
    }

    private long getFileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static class PathWithSize {
        final Path path;
        final long size;
        final long lastModified;

        PathWithSize(Path path, long size, long lastModified) {
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /**
     * 清理结果
     */
    public static class CleanupResult {
        public double diskUsageBefore;
        public double diskUsageAfter;
        public int deletedCount;
        public long freedSpace;
        public int emptyDirsRemoved;
        public int orphanRecordsCleaned;
    }
}