package com.genersoft.iot.vmp.agent.sync;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * pending删除记录持久化存储
 * 文件已删除但WVP未同步的记录，持久化到本地文件，WVP恢复后批量同步
 */
public class PendingStore {

    private static final Logger logger = LoggerFactory.getLogger(PendingStore.class);

    private final Path storeFile;
    private final int maxCount;
    private final int maxRetries;
    private final long maxRetainMs;

    private final List<PendingItem> items = new ArrayList<>();

    public PendingStore(String dataDir, int maxCount, int maxRetries, int maxRetainHours) {
        this.storeFile = Paths.get(dataDir, "pending_deletions.json");
        this.maxCount = maxCount;
        this.maxRetries = maxRetries;
        this.maxRetainMs = maxRetainHours * 3600_000L;
        ensureDataDir(dataDir);
        load();
    }

    private void ensureDataDir(String dataDir) {
        try {
            Files.createDirectories(Paths.get(dataDir));
        } catch (IOException e) {
            logger.error("创建数据目录失败: {}", dataDir, e);
        }
    }

    /**
     * 添加一条pending记录
     */
    public synchronized void add(String filePath) {
        items.add(new PendingItem(filePath, System.currentTimeMillis(), 0));
        trim();
        save();
    }

    /**
     * 批量添加pending记录
     */
    public synchronized void addAll(List<String> filePaths) {
        long now = System.currentTimeMillis();
        for (String fp : filePaths) {
            items.add(new PendingItem(fp, now, 0));
        }
        trim();
        save();
    }

    /**
     * 获取所有待同步的记录
     */
    public synchronized List<PendingItem> getAll() {
        return new ArrayList<>(items);
    }

    /**
     * 获取待同步记录数量
     */
    public synchronized int size() {
        return items.size();
    }

    /**
     * 标记一条记录同步成功，移除
     */
    public synchronized void markSuccess(String filePath) {
        items.removeIf(item -> item.filePath.equals(filePath));
        save();
    }

    /**
     * 标记一条记录同步失败，增加重试计数
     */
    public synchronized void markFailed(String filePath) {
        for (PendingItem item : items) {
            if (item.filePath.equals(filePath)) {
                item.retryCount++;
                if (item.retryCount >= maxRetries) {
                    logger.warn("pending记录超过最大重试次数({})，丢弃: {}", maxRetries, filePath);
                    items.remove(item);
                }
                break;
            }
        }
        save();
    }

    /**
     * 清理过期和超量的记录
     */
    private void trim() {
        long now = System.currentTimeMillis();
        Iterator<PendingItem> it = items.iterator();
        while (it.hasNext()) {
            PendingItem item = it.next();
            if (now - item.createdAt > maxRetainMs) {
                logger.warn("pending记录超过最大保留时间({}小时)，丢弃: {}", maxRetainMs / 3600_000, item.filePath);
                it.remove();
            }
        }
        while (items.size() > maxCount) {
            PendingItem removed = items.remove(0);
            logger.warn("pending记录超过最大条数({})，丢弃最旧记录: {}", maxCount, removed.filePath);
        }
    }

    private void load() {
        if (!Files.exists(storeFile)) {
            return;
        }
        try {
            String content = new String(Files.readAllBytes(storeFile), StandardCharsets.UTF_8);
            JSONObject json = JSON.parseObject(content);
            if (json == null) {
                return;
            }
            JSONArray arr = json.getJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    PendingItem item = new PendingItem(
                            obj.getString("filePath"),
                            obj.getLongValue("createdAt"),
                            obj.getIntValue("retryCount")
                    );
                    items.add(item);
                }
            }
            logger.info("加载pending记录: {} 条", items.size());
        } catch (Exception e) {
            logger.error("加载pending记录失败", e);
        }
    }

    private synchronized void save() {
        try {
            JSONObject json = new JSONObject();
            JSONArray arr = new JSONArray();
            for (PendingItem item : items) {
                JSONObject obj = new JSONObject();
                obj.put("filePath", item.filePath);
                obj.put("createdAt", item.createdAt);
                obj.put("retryCount", item.retryCount);
                arr.add(obj);
            }
            json.put("items", arr);
            Files.write(storeFile, json.toJSONString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("保存pending记录失败", e);
        }
    }

    public static class PendingItem {
        public final String filePath;
        public final long createdAt;
        public int retryCount;

        public PendingItem(String filePath, long createdAt, int retryCount) {
            this.filePath = filePath;
            this.createdAt = createdAt;
            this.retryCount = retryCount;
        }
    }
}