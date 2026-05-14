package com.genersoft.iot.vmp.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.genersoft.iot.vmp.agent.config.AgentConfig;
import com.genersoft.iot.vmp.agent.lifecycle.AgentLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WVP Cleanup Agent 主入口
 *
 * 用法: java -jar wvp-cleanup-agent.jar [--config=agent.yml] [--quiet]
 * 默认从当前目录加载 agent.yml
 * --quiet 关闭日志输出（适合放入 SHM 等无需日志的场景）
 */
public class AgentBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(AgentBootstrap.class);

    public static void main(String[] args) {
        boolean quiet = false;
        String configPath = "agent.yml";

        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
            } else if ("--quiet".equals(arg)) {
                quiet = true;
            }
        }

        if (quiet) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
        }

        AgentConfig config;
        try {
            config = AgentConfig.load(configPath);
        } catch (Exception e) {
            System.err.println("加载配置文件失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        if (!quiet) {
            configureLogging(config);
        }

        AgentLifecycle lifecycle = new AgentLifecycle(config);
        lifecycle.start();
        lifecycle.await();
    }

    /**
     * 配置日志级别
     */
    private static void configureLogging(AgentConfig config) {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            String level = config.getLog().getLevel();
            ch.qos.logback.classic.Logger rootLogger = context.getLogger("ROOT");
            rootLogger.setLevel(Level.toLevel(level, Level.INFO));
            logger.info("日志级别: {}", level);
        } catch (Exception e) {
            System.err.println("配置日志级别失败: " + e.getMessage());
        }
    }
}