package com.genersoft.iot.vmp.agent.zlm;

/**
 * ZLM运行时配置，从getServerConfig API返回中解析
 */
public class ZlmConfig {

    private String mp4SavePath;
    private String appName;
    private String mediaServerId;
    private String wvpUrl;
    private String secret;

    public String getMp4SavePath() { return mp4SavePath; }
    public void setMp4SavePath(String mp4SavePath) { this.mp4SavePath = mp4SavePath; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getMediaServerId() { return mediaServerId; }
    public void setMediaServerId(String mediaServerId) { this.mediaServerId = mediaServerId; }

    public String getWvpUrl() { return wvpUrl; }
    public void setWvpUrl(String wvpUrl) { this.wvpUrl = wvpUrl; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    /**
     * 拼接录像根目录
     */
    public String getRecordPath() {
        if (mp4SavePath == null || appName == null) {
            return null;
        }
        String base = mp4SavePath.endsWith("/") ? mp4SavePath : mp4SavePath + "/";
        return base + appName;
    }

    /**
     * 校验配置完整性
     */
    public boolean isValid() {
        return mp4SavePath != null && !mp4SavePath.isEmpty()
                && appName != null && !appName.isEmpty()
                && mediaServerId != null && !mediaServerId.isEmpty()
                && wvpUrl != null && !wvpUrl.isEmpty();
    }
}