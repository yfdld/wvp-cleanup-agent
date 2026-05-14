package com.genersoft.iot.vmp.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
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
     * 配置日志级别和文件滚动策略
     */
    private static void configureLogging(AgentConfig config) {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger rootLogger = context.getLogger("ROOT");

            // 设置日志级别
            String level = config.getLog().getLevel();
            rootLogger.setLevel(Level.toLevel(level, Level.INFO));

            // 移除已有的 FILE appender（如果有）
            rootLogger.detachAppender("FILE");

            // 创建 PatternLayoutEncoder
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
            encoder.start();

            // 创建 SizeAndTimeBasedRollingPolicy
            String logFile = config.getLog().getFile();
            String maxLogSize = config.getLog().getMaxLogSize() + "MB";
            SizeAndTimeBasedRollingPolicy rollingPolicy = new SizeAndTimeBasedRollingPolicy();
            rollingPolicy.setContext(context);
            rollingPolicy.setFileNamePattern(logFile + ".%d{yyyy-MM-dd}.%i");
            rollingPolicy.setMaxFileSize(FileSize.valueOf(maxLogSize));
            rollingPolicy.setMaxHistory(config.getLog().getMaxHistory());
            rollingPolicy.setTotalSizeCap(FileSize.valueOf(
                    (long) config.getLog().getMaxLogSize() * config.getLog().getMaxHistory() + "MB"));
            rollingPolicy.setParent(null);
            rollingPolicy.start();

            // 创建 RollingFileAppender
            RollingFileAppender fileAppender = new RollingFileAppender();
            fileAppender.setContext(context);
            fileAppender.setName("FILE");
            fileAppender.setFile(logFile);
            fileAppender.setEncoder(encoder);
            fileAppender.setRollingPolicy(rollingPolicy);
            rollingPolicy.setParent(fileAppender);
            fileAppender.start();

            // 添加到 root logger
            rootLogger.addAppender(fileAppender);

            logger.info("日志级别: {}, 文件: {}, 单文件最大: {}, 保留天数: {}",
                    level, logFile, maxLogSize, config.getLog().getMaxHistory());
        } catch (Exception e) {
            System.err.println("配置日志失败: " + e.getMessage());
        }
    }
}