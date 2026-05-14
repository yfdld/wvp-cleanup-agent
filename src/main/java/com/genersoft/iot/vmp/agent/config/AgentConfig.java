package com.genersoft.iot.vmp.agent.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Agent配置模型，对应agent.yml
 */
public class AgentConfig {

    private ZlmConfig zlm = new ZlmConfig();
    private CleanupConfig cleanup = new CleanupConfig();
    private SyncConfig sync = new SyncConfig();
    private LogConfig log = new LogConfig();

    public ZlmConfig getZlm() { return zlm; }
    public void setZlm(ZlmConfig zlm) { this.zlm = zlm; }
    public CleanupConfig getCleanup() { return cleanup; }
    public void setCleanup(CleanupConfig cleanup) { this.cleanup = cleanup; }
    public SyncConfig getSync() { return sync; }
    public void setSync(SyncConfig sync) { this.sync = sync; }
    public LogConfig getLog() { return log; }
    public void setLog(LogConfig log) { this.log = log; }

    public static class ZlmConfig {
        private String host = "127.0.0.1";
        private int sslPort = 1443;
        private String secret = "";
        private int retryInterval = 10;
        private int configRefreshInterval = 600;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getSslPort() { return sslPort; }
        public void setSslPort(int sslPort) { this.sslPort = sslPort; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public int getRetryInterval() { return retryInterval; }
        public void setRetryInterval(int retryInterval) { this.retryInterval = retryInterval; }
        public int getConfigRefreshInterval() { return configRefreshInterval; }
        public void setConfigRefreshInterval(int configRefreshInterval) { this.configRefreshInterval = configRefreshInterval; }
    }

    public static class CleanupConfig {
        private double diskThreshold = 80;
        private int checkInterval = 60;
        private int maxDeleteCount = 500;
        private int maxDbCleanCount = 100;
        private boolean cleanEmptyDirs = true;

        public double getDiskThreshold() { return diskThreshold; }
        public void setDiskThreshold(double diskThreshold) { this.diskThreshold = diskThreshold; }
        public int getCheckInterval() { return checkInterval; }
        public void setCheckInterval(int checkInterval) { this.checkInterval = checkInterval; }
        public int getMaxDeleteCount() { return maxDeleteCount; }
        public void setMaxDeleteCount(int maxDeleteCount) { this.maxDeleteCount = maxDeleteCount; }
        public int getMaxDbCleanCount() { return maxDbCleanCount; }
        public void setMaxDbCleanCount(int maxDbCleanCount) { this.maxDbCleanCount = maxDbCleanCount; }
        public boolean isCleanEmptyDirs() { return cleanEmptyDirs; }
        public void setCleanEmptyDirs(boolean cleanEmptyDirs) { this.cleanEmptyDirs = cleanEmptyDirs; }
    }

    public static class SyncConfig {
        private int pendingMaxCount = 200;
        private int maxRetries = 10;
        private int maxRetainHours = 72;
        private int heartbeatInterval = 30;

        public int getPendingMaxCount() { return pendingMaxCount; }
        public void setPendingMaxCount(int pendingMaxCount) { this.pendingMaxCount = pendingMaxCount; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxRetainHours() { return maxRetainHours; }
        public void setMaxRetainHours(int maxRetainHours) { this.maxRetainHours = maxRetainHours; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public static class LogConfig {
        private String level = "INFO";
        private String file = "./logs/agent.log";
        private int maxHistory = 7;

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public int getMaxHistory() { return maxHistory; }
        public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
    }

    /**
     * 从指定路径加载配置文件
     */
    public static AgentConfig load(String configPath) {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new RuntimeException("配置文件不存在: " + configPath);
        }
        Yaml yaml = new Yaml(new Constructor(AgentConfig.class, new LoaderOptions()));
        try (InputStream in = new FileInputStream(path.toFile())) {
            AgentConfig config = yaml.load(in);
            if (config == null) {
                throw new RuntimeException("配置文件为空: " + configPath);
            }
            validate(config);
            return config;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("加载配置文件失败: " + configPath, e);
        }
    }

    /**
     * 校验必填项
     */
    private static void validate(AgentConfig config) {
        if (config.zlm.secret == null || config.zlm.secret.isEmpty()
                || config.zlm.secret.contains("请填写")) {
            throw new RuntimeException("agent.yml 中 zlm.secret 未配置，请填写ZLM的api.secret");
        }
        if (config.zlm.host == null || config.zlm.host.isEmpty()) {
            throw new RuntimeException("agent.yml 中 zlm.host 未配置");
        }
        if (config.cleanup.diskThreshold < 1.0 || config.cleanup.diskThreshold >= 100.0) {
            throw new RuntimeException("agent.yml 中 cleanup.diskThreshold 必须在 1-99.9 之间");
        }
        if (config.cleanup.checkInterval < 10) {
            throw new RuntimeException("agent.yml 中 cleanup.checkInterval 不能小于 10 秒");
        }
    }
}