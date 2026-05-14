package com.genersoft.iot.vmp.agent.zlm;

import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLSocketFactory;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * ZLM API客户端，通过HTTPS调用ZLM的API获取运行时配置
 */
public class ZlmApiClient {

    private static final Logger logger = LoggerFactory.getLogger(ZlmApiClient.class);

    private final String baseUrl;
    private final String secret;
    private final OkHttpClient httpClient;

    public ZlmApiClient(String host, int sslPort, String secret) {
        this.secret = secret;
        this.baseUrl = "https://" + host + ":" + sslPort + "/index/api/";
        this.httpClient = buildHttpClient();
    }

    private OkHttpClient buildHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("初始化HTTPS客户端失败", e);
        }
    }

    /**
     * 调用getServerConfig获取ZLM运行时配置
     */
    public ZlmConfig fetchServerConfig() {
        JSONObject response = post("getServerConfig", null);
        if (response == null || response.getInteger("code") == null || response.getInteger("code") != 0) {
            return null;
        }
        JSONObject data = response.getJSONArray("data").getJSONObject(0);
        if (data == null) {
            return null;
        }

        ZlmConfig config = new ZlmConfig();
        config.setMp4SavePath(data.getString("protocol.mp4_save_path"));
        config.setAppName(data.getString("record.appName"));
        config.setMediaServerId(data.getString("general.mediaServerId"));
        config.setSecret(data.getString("api.secret"));

        String hookUrl = data.getString("hook.on_record_mp4");
        if (hookUrl != null && !hookUrl.isEmpty()) {
            config.setWvpUrl(extractWvpUrl(hookUrl));
        }

        return config;
    }

    /**
     * 从hook URL中提取WVP地址
     * 例如: http://127.0.0.1:5580/index/hook/on_record_mp4 -> http://127.0.0.1:5580
     */
    private String extractWvpUrl(String hookUrl) {
        int idx = hookUrl.indexOf("/index/");
        if (idx > 0) {
            return hookUrl.substring(0, idx);
        }
        return hookUrl;
    }

    /**
     * 发送POST请求到ZLM API
     */
    private JSONObject post(String api, JSONObject params) {
        String url = baseUrl + api;
        FormBody.Builder builder = new FormBody.Builder();
        builder.add("secret", secret);
        if (params != null) {
            for (String key : params.keySet()) {
                Object value = params.get(key);
                if (value != null) {
                    builder.add(key, value.toString());
                }
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                return JSONObject.parse(body);
            }
        } catch (Exception e) {
            logger.debug("ZLM API调用失败: {} - {}", api, e.getMessage());
        }
        return null;
    }

    /**
     * 测试ZLM是否可达
     */
    public boolean isReachable() {
        JSONObject response = post("getServerConfig", null);
        return response != null && response.getInteger("code") != null && response.getInteger("code") == 0;
    }
}