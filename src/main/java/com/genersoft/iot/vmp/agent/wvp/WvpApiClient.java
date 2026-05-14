package com.genersoft.iot.vmp.agent.wvp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WVP API客户端，用于与WVP服务通信
 */
public class WvpApiClient {

    private static final Logger logger = LoggerFactory.getLogger(WvpApiClient.class);

    private final String baseUrl;
    private final String mediaServerId;
    private final OkHttpClient httpClient;

    public WvpApiClient(String wvpUrl, String mediaServerId) {
        this.baseUrl = wvpUrl.endsWith("/") ? wvpUrl : wvpUrl + "/";
        this.mediaServerId = mediaServerId;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }

    /**
     * 从WVP获取可删除的录像文件列表
     * @param limit 最大返回条数
     * @return 可删除的文件路径列表，按时间从旧到新排序
     */
    public List<RecordFileInfo> getCleanupCandidates(int limit) {
        String url = baseUrl + "api/agent/cleanup/candidates"
                + "?mediaServerId=" + mediaServerId
                + "&limit=" + limit;
        JSONObject response = get(url);
        if (response == null || response.getInteger("code") == null || response.getInteger("code") != 0) {
            return null;
        }
        JSONArray data = response.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return new ArrayList<>();
        }
        return data.toJavaList(RecordFileInfo.class);
    }

    /**
     * 向WVP确认文件已删除，WVP清理数据库记录
     * @param filePaths 已删除的文件路径列表
     * @return 确认结果，包含成功状态和更老的记录列表
     */
    public ConfirmResult confirmDeletion(List<String> filePaths) {
        String url = baseUrl + "api/agent/cleanup/confirm";
        JSONObject body = new JSONObject();
        body.put("mediaServerId", mediaServerId);
        body.put("filePaths", filePaths);
        JSONObject response = postJson(url, body);

        ConfirmResult result = new ConfirmResult();
        if (response != null && response.getInteger("code") != null && response.getInteger("code") == 0) {
            result.success = true;
            JSONObject data = response.getJSONObject("data");
            if (data != null) {
                JSONArray olderArr = data.getJSONArray("olderRecords");
                if (olderArr != null && !olderArr.isEmpty()) {
                    result.olderRecords = olderArr.toJavaList(RecordFileInfo.class);
                }
            }
        }
        return result;
    }

    /**
     * 批量删除孤儿录像记录（文件已不存在但数据库仍有记录）
     * @param ids 记录ID列表
     * @return 是否成功
     */
    public boolean deleteOrphanRecords(List<Integer> ids) {
        String url = baseUrl + "api/agent/cleanup/delete-records";
        JSONObject body = new JSONObject();
        body.put("ids", ids);
        JSONObject response = postJson(url, body);
        return response != null && response.getInteger("code") != null && response.getInteger("code") == 0;
    }

    /**
     * 根据文件路径查找数据库记录，如果存在则返回比它更老的记录
     * @param filePath 文件路径
     * @param limit 最大返回条数
     * @return 查询结果，包含found标志和olderRecords
     */
    public OlderRecordsResult getOlderRecordsByFile(String filePath, int limit) {
        String url = baseUrl + "api/agent/cleanup/older-records-by-file"
                + "?mediaServerId=" + mediaServerId
                + "&filePath=" + filePath
                + "&limit=" + limit;
        JSONObject response = get(url);
        OlderRecordsResult result = new OlderRecordsResult();
        if (response == null || response.getInteger("code") == null || response.getInteger("code") != 0) {
            return result;
        }
        JSONObject data = response.getJSONObject("data");
        if (data == null) {
            return result;
        }
        result.found = data.getBoolean("found");
        JSONArray olderArr = data.getJSONArray("olderRecords");
        if (olderArr != null && !olderArr.isEmpty()) {
            result.olderRecords = olderArr.toJavaList(RecordFileInfo.class);
        }
        return result;
    }

    /**
     * 从WVP获取清理策略配置
     * @return 配置JSON，失败返回null
     */
    public JSONObject getCleanupConfig() {
        String url = baseUrl + "api/agent/cleanup/config"
                + "?mediaServerId=" + mediaServerId;
        JSONObject response = get(url);
        if (response == null || response.getInteger("code") == null || response.getInteger("code") != 0) {
            return null;
        }
        return response.getJSONObject("data");
    }

    /**
     * 测试WVP是否可达
     */
    public boolean isReachable() {
        JSONObject response = get(baseUrl + "api/agent/cleanup/config?mediaServerId=" + mediaServerId);
        return response != null && response.getInteger("code") != null;
    }

    private JSONObject get(String url) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return execute(request);
    }

    private JSONObject postJson(String url, JSONObject body) {
        RequestBody requestBody = RequestBody.create(
                body.toJSONString(),
                MediaType.parse("application/json; charset=utf-8")
        );
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();
        return execute(request);
    }

    private JSONObject execute(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                return JSONObject.parse(body);
            }
            logger.warn("WVP API返回异常: {} - HTTP {}", request.url(), response.code());
        } catch (Exception e) {
            logger.warn("WVP API调用失败: {} - {}", request.url(), e.getMessage());
        }
        return null;
    }

    /**
     * 录像文件信息
     */
    public static class RecordFileInfo {
        private int id;
        private String filePath;
        private long fileSize;
        private long startTime;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
    }

    /**
     * 确认删除结果
     */
    public static class ConfirmResult {
        private boolean success;
        private List<RecordFileInfo> olderRecords;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public List<RecordFileInfo> getOlderRecords() { return olderRecords; }
        public void setOlderRecords(List<RecordFileInfo> olderRecords) { this.olderRecords = olderRecords; }
    }

    /**
     * 根据文件路径查询更老记录的结果
     */
    public static class OlderRecordsResult {
        private boolean found;
        private List<RecordFileInfo> olderRecords;

        public boolean isFound() { return found; }
        public void setFound(boolean found) { this.found = found; }
        public List<RecordFileInfo> getOlderRecords() { return olderRecords; }
        public void setOlderRecords(List<RecordFileInfo> olderRecords) { this.olderRecords = olderRecords; }
    }
}