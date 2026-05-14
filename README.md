# WVP Cleanup Agent

WVP 云端录像磁盘阈值清理代理。独立运行于 ZLMediaKit 服务器上，当录像磁盘使用率超过设定阈值时，自动按文件新旧顺序清理录像文件，并同步 WVP 数据库记录。

> **版本说明**：本项目基于 WVP 2.7.2 开发，WVP 侧新增的 API 接口在该版本上验证通过。不保证与 2.7.3 及以上版本的兼容性。

## 功能特性

- **磁盘阈值清理**：磁盘使用率超过阈值（默认 80%）时自动触发，按需释放空间，刚好降到阈值以下
- **孤儿记录清理**：每次检查都自动清理孤儿数据库记录（文件已不存在但数据库仍有记录），以磁盘最老的已入库 MP4 为基准
- **自动发现配置**：通过 ZLM HTTPS API 自动获取录像路径、节点 ID、WVP 地址，无需手动填写
- **空目录清理**：文件删除后自动清理空文件夹
- **WVP 断连容错**：WVP 不可达时继续按本地规则清理，恢复后自动同步数据库
- **配置外置**：所有参数通过 `agent.yml` 配置，修改后重启生效
- **零侵入**：不修改 ZLM 和 WVP 核心代码，仅在 WVP 侧新增 API 接口

## 工作原理

```
┌─────────────┐     HTTPS API      ┌─────────────┐
│             │◄───────────────────│             │
│  ZLMediaKit │  获取运行时配置     │   Agent     │
│             │                    │             │
└─────────────┘                    └──────┬──────┘
                                          │
                                    HTTP API
                                          │
                                          ▼
                                   ┌─────────────┐
                                   │    WVP      │
                                   │  确认删除同步 │
                                   │  孤儿记录查询 │
                                   │  孤儿记录删除 │
                                   └─────────────┘
```

### 每次检查流程

```
1. 孤儿记录清理（无论磁盘是否超阈值）：
   扫描磁盘所有 .mp4，按时间从旧到新排序
     ↓
   从最老的 .mp4 开始，逐个去数据库查有没有对应记录
     ↓
   找到第一个已入库的 .mp4 → 用它的 startTime 做基准
     ↓
   查出数据库中比它还老的记录
     ↓
   逐条检查文件是否还在磁盘上
     ↓
   文件已不存在的 → 孤儿记录 → 删除（上限 maxDbCleanCount）

2. 如果磁盘超阈值：
   扫描录像目录，按修改时间从旧到新排序
     ↓
   累加文件大小直到满足需释放空间或达到单次上限 → 删除文件
     ↓
   调用 WVP API 同步数据库记录

3. 清理空目录
```

### 孤儿记录判定逻辑

```
磁盘文件（按时间排序）：
  [最老] 2024-01-01_10.mp4  ← 已入库，作为基准
         2024-01-01_11.mp4
         2024-01-02_08.mp4
         2024-01-02_09.mp4  [最新]

数据库记录（按 startTime 排序）：
  [最老] 2024-01-01_09.mp4  ← 文件不存在 → 孤儿，删除
         2024-01-01_10.mp4  ← 基准文件，跳过
         2024-01-01_11.mp4  ← 文件存在，跳过
         2024-01-02_08.mp4  ← 文件存在，跳过
```

## 编译

### 环境要求

- JDK 1.8+
- Maven 3.6+

### 编译 Agent

```bash
cd wvp-cleanup-agent
mvn clean package -DskipTests
```

编译产物：`target/wvp-cleanup-agent-1.0.0.jar`（已包含所有依赖的 fat jar）

### 编译 WVP（含 Agent API）

WVP 侧新增了 API 接口供 Agent 调用，需要重新编译 WVP：

```bash
cd wvp-GB28181-pro
mvn clean package -DskipTests
```

## 部署

### 1. 部署 Agent

Agent 可放在任意独立目录运行，无需与 ZLM 同目录：

```bash
mkdir -p /opt/wvp-cleanup-agent
cp wvp-cleanup-agent-1.0.0.jar /opt/wvp-cleanup-agent/
cp agent.yml /opt/wvp-cleanup-agent/
```

### 2. 修改配置

编辑 `agent.yml`，填写 ZLM 的连接信息（`secret` 必须与 ZLM `config.ini` 中的 `api.secret` 一致）：

```yaml
zlm:
  host: 127.0.0.1
  sslPort: 1443
  secret: 你的ZLM的api.secret
```

其余配置项均有默认值，可按需修改。

### 3. 启动

测试用（SSH 前台运行，Ctrl+C 停止）：

```bash
cd /opt/wvp-cleanup-agent
java -jar wvp-cleanup-agent-1.0.0.jar
```

无需日志时可加 `--quiet` 参数（适合放入 SHM 等场景）：

```bash
java -jar wvp-cleanup-agent-1.0.0.jar --quiet
```

生产环境建议注册为 systemd 服务：

```ini
# /etc/systemd/system/wvp-cleanup-agent.service
[Unit]
Description=WVP Cleanup Agent
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/wvp-cleanup-agent
ExecStart=/usr/bin/java -jar /opt/wvp-cleanup-agent/wvp-cleanup-agent-1.0.0.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable wvp-cleanup-agent
systemctl start wvp-cleanup-agent
```


## 配置文件说明

完整配置见 `agent.yml`，所有配置项：

| 分类 | 配置项 | 默认值 | 说明 |
|------|--------|:---:|------|
| **zlm** | `host` | `127.0.0.1` | ZLM 服务器 IP |
| | `sslPort` | `1443` | ZLM HTTPS 端口 |
| | `secret` | — | **必填**，ZLM API 密钥 |
| | `retryInterval` | `10` | 启动连接失败重试间隔（秒） |
| | `configRefreshInterval` | `600` | 运行时配置刷新间隔（秒） |
| **cleanup** | `diskThreshold` | `80` | 磁盘使用率阈值（%） |
| | `checkInterval` | `60` | 定时检查间隔（秒） |
| | `maxDeleteCount` | `500` | 单次最大删除文件数 |
| | `maxDbCleanCount` | `100` | 单次最大清理数据库记录数（孤儿记录上限） |
| | `cleanEmptyDirs` | `true` | 是否清理空目录 |
| **sync** | `pendingMaxCount` | `200` | pending 最大条数 |
| | `maxRetries` | `10` | 单条最大重试次数 |
| | `maxRetainHours` | `72` | pending 最大保留时间（小时） |
| | `heartbeatInterval` | `30` | WVP 心跳间隔（秒） |
| **log** | `level` | `INFO` | 日志级别 |
| | `file` | `./logs/agent.log` | 日志文件路径 |
| | `maxHistory` | `7` | 日志保留天数 |

## WVP 侧接口说明

Agent 需要 WVP 提供以下 API 接口，已在 WVP 项目中实现。

### 添加方式

**1. 新增 Controller 文件**

在 `src/main/java/com/genersoft/iot/vmp/vmanager/agent/` 目录下创建 `AgentController.java`。

**2. 放行安全拦截**

在 `WebSecurityConfig.java` 的 `configure(HttpSecurity http)` 方法中，将 `/api/agent/**` 加入 `permitAll()` 列表：

```java
.antMatchers("/api/user/login", "/index/hook/**", "/api/agent/**", "/swagger-ui/**", "/doc.html#/**").permitAll()
```

**3. 新增 Mapper 方法**

在 `CloudRecordServiceMapper.java` 中添加 `queryOlderRecords` 方法：

```java
@Select("<script>" +
        "select * from wvp_cloud_record where media_server_id = #{mediaServerId} and start_time &lt; #{startTime} and collect = false order by start_time ASC" +
        "</script>")
List<CloudRecordItem> queryOlderRecords(@Param("mediaServerId") String mediaServerId, @Param("startTime") Long startTime);
```

### 接口列表

#### GET /api/agent/cleanup/candidates

获取可清理的录像文件列表，按开始时间从旧到新排序，排除已收藏的文件。

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `mediaServerId` | String | 是 | ZLM 节点 ID |
| `limit` | int | 否 | 最大返回条数，默认 500 |

响应示例：

```json
{
  "code": 0,
  "data": [
    {
      "id": 123,
      "filePath": "/gb/luxiang/record/34020000001320000001/2024-05-10/file.mp4",
      "fileSize": 52428800,
      "startTime": 1715300000000
    }
  ]
}
```

#### POST /api/agent/cleanup/confirm

确认文件已删除，WVP 清理对应数据库记录，同时返回比已删除记录更老的记录列表（供 Agent 做孤儿记录验证）。

请求体：

```json
{
  "mediaServerId": "home",
  "filePaths": ["/gb/luxiang/.../file.mp4"]
}
```

响应示例：

```json
{
  "code": 0,
  "data": {
    "deletedCount": 10,
    "olderRecords": [
      {
        "id": 1,
        "filePath": "/gb/luxiang/.../old.mp4",
        "fileSize": 12345,
        "startTime": 1715000000000
      }
    ]
  }
}
```

#### POST /api/agent/cleanup/delete-records

批量删除孤儿录像记录（文件已不存在但数据库仍有记录）。

请求体：

```json
{
  "ids": [1, 2, 3, 4, 5]
}
```

响应示例：

```json
{
  "code": 0,
  "data": 5
}
```

#### GET /api/agent/cleanup/older-records-by-file

根据文件路径查找数据库记录，如果存在则返回比它更老的记录。用于 Agent 孤儿记录清理。

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `mediaServerId` | String | 是 | ZLM 节点 ID |
| `filePath` | String | 是 | 文件完整路径 |
| `limit` | int | 否 | 最大返回条数，默认 100 |

响应示例：

```json
{
  "code": 0,
  "data": {
    "found": true,
    "referenceStartTime": 1715300000000,
    "olderRecords": [
      {
        "id": 1,
        "filePath": "/gb/luxiang/.../old.mp4",
        "fileSize": 12345,
        "startTime": 1715000000000
      }
    ]
  }
}
```

#### GET /api/agent/cleanup/config

获取该节点的清理策略配置。

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `mediaServerId` | String | 是 | ZLM 节点 ID |

响应示例：

```json
{
  "code": 0,
  "data": {
    "mediaServerId": "home",
    "recordDay": 7
  }
}
```

## 容错机制

| 场景 | Agent 行为 |
|------|-----------|
| Agent 启动时 ZLM 未启动 | 每 10 秒重试连接，无限等待，清理任务不启动 |
| Agent 运行中 ZLM 崩溃 | 清理功能不受影响，继续用缓存配置运行 |
| Agent 运行中 WVP 断连 | 继续按本地规则清理，删除记录写入 `data/pending_deletions.json` |
| WVP 恢复连接 | 自动同步 pending 记录到 WVP，清理数据库 |
| pending 记录超量 | 丢弃最旧记录，确保磁盘不会因 pending 堆积而爆满 |
| 孤儿记录超过单次上限 | 本次只清理上限条数，剩余下次继续 |
| 磁盘上所有 MP4 均未入库 | 跳过孤儿记录清理，下次检查再试 |

## 项目结构

```
wvp-cleanup-agent/
├── pom.xml
├── agent.yml
├── README.md
└── src/main/java/com/genersoft/iot/vmp/agent/
    ├── AgentBootstrap.java           主入口
    ├── config/
    │   └── AgentConfig.java          配置模型与校验
    ├── zlm/
    │   ├── ZlmApiClient.java         ZLM HTTPS API 客户端
    │   └── ZlmConfig.java            ZLM 运行时配置模型
    ├── wvp/
    │   └── WvpApiClient.java         WVP API 客户端
    ├── cleanup/
    │   ├── PathGuard.java            路径安全守卫
    │   ├── DiskMonitor.java          磁盘监控
    │   └── CleanupEngine.java        清理引擎
    ├── sync/
    │   ├── PendingStore.java         pending 持久化存储
    │   └── SyncManager.java          同步管理器
    └── lifecycle/
        └── AgentLifecycle.java       生命周期管理
```