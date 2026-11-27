# TCP 连通性探测服务

通过 HTTP 同步探测一个或多个指定目标的 TCP 连通性。服务使用 Spring Boot 2.7.18 接收请求，使用 JDK 8 Socket 建立 TCP 连接。探测成功表示服务所在环境能建立该连接。请使用本机或获授权的目标。

## 获取、构建和启动

使用 JDK 8，并将 JAVA_HOME 指向 JDK 目录。Windows 下使用 mvnw.cmd，Linux 和 macOS 下使用 ./mvnw。首次构建需要访问 Maven 仓库，Wrapper 会下载 Maven 3.9.11。

```powershell
git clone --branch codex/tcp-probe https://github.com/young8462/TCP-connection-probing-service.git
Set-Location TCP-connection-probing-service
.\mvnw.cmd -v
.\mvnw.cmd clean verify
& "$env:JAVA_HOME/bin/java.exe" -jar target/tcp-probe-0.1.0-SNAPSHOT.jar --server.port=18080
```

服务默认监听 127.0.0.1:8080。上面为验收指定 18080，以下请求在另一个 PowerShell 窗口执行。结束后在服务窗口按 Ctrl+C。

## HTTP 接口

### GET /healthz

返回 HTTP 200 和 `{"status":"up"}`。

```powershell
$base = 'http://127.0.0.1:18080'
Invoke-RestMethod "$base/healthz"
```

### POST /probe

使用 Content-Type: application/json。targets 为包含 1–20 项的数组。host 填主机名或 IP 地址，port 填 1–65535 的整数。单目标也使用数组。结果数组与输入顺序一致，重复目标各有一项结果。

```json
{
  "targets": [
    { "host": "127.0.0.1", "port": 18080 }
  ]
}
```

```json
{
  "results": [
    { "host": "127.0.0.1", "port": 18080, "status": "reachable", "duration_ms": 2 }
  ]
}
```

| HTTP 状态 | 含义 |
| --- | --- |
| 200 | 批次已处理。每项结果分别说明探测状态。 |
| 400 | JSON 无效或字段校验失败。字段错误通过 error.field 标出位置。 |
| 415 | 请求的内容类型不受支持。 |
| 429 | 请求额度耗尽，返回 error.code 为 rate_limit_exceeded，附带 Retry-After: 1。 |
| 503 | 当前容量无法接收整批目标，返回 error.code 为 probe_capacity_unavailable。 |

单项状态包括 reachable、connection_refused、timeout、dns_error、io_error 和 deadline_exceeded。duration_ms 是单项探测耗时，取整到毫秒。批次截止时会保留已完成项，未完成项标记为 deadline_exceeded，其 duration_ms 为 0。

## 默认限制

| 参数 | 默认值 |
| --- | --- |
| 每批目标数 | 1–20 |
| 全局并发探测 | 8 项 |
| 全局容量 | 执行中与排队合计 40 项 |
| 单目标 TCP 连接超时 | 1 秒 |
| 批次响应截止时间 | 5 秒，含排队和结果等待 |
| 全局请求限流 | 每秒补充 5 次请求额度，最多积累 10 次 |

每个匹配到探测接口的 POST 请求消耗一次额度，限流发生在 JSON 解析之前。健康检查不占用额度。JDK Socket 的连接超时适用于 TCP 建连阶段。DNS 解析或正在运行的连接可能在批次响应结束后才退出，占用的容量会在实际退出后释放。

## 本地验收

### 单目标及混合批量

用服务的 HTTP 监听端口作为可连通目标。测试期间临时绑定一个本机端口且不监听，用作不可连通目标。

```powershell
$single = '{"targets":[{"host":"127.0.0.1","port":18080}]}'
Invoke-RestMethod "$base/probe" -Method Post -ContentType 'application/json' -Body $single |
    ConvertTo-Json -Depth 5

$unavailable = New-Object System.Net.Sockets.Socket(
    [System.Net.Sockets.AddressFamily]::InterNetwork,
    [System.Net.Sockets.SocketType]::Stream,
    [System.Net.Sockets.ProtocolType]::Tcp)
try {
    $unavailable.Bind([System.Net.IPEndPoint]::new([System.Net.IPAddress]::Loopback, 0))
    $batch = @{ targets = @(
        @{ host = '127.0.0.1'; port = 18080 }
        @{ host = '127.0.0.1'; port = $unavailable.LocalEndPoint.Port }
        @{ host = '127.0.0.1'; port = 18080 }
    ) } | ConvertTo-Json -Depth 4 -Compress

    Invoke-RestMethod "$base/probe" -Method Post -ContentType 'application/json' -Body $batch |
        ConvertTo-Json -Depth 5
} finally {
    $unavailable.Dispose()
}
```

单目标应返回 reachable。混合批量按顺序返回 reachable、connection_refused 或 timeout、reachable。

### 触发限流

向接口快速发送 30 次请求。这里用缺少 targets 字段的 JSON 验证请求限流，正常校验返回 400，突发超限返回 429。

```powershell
$codes = 1..30 | ForEach-Object {
    curl.exe -sS -o NUL -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{}' "$base/probe"
}
$codes | Group-Object | Select-Object Name, Count
```

正常情况下输出包含 429。自动化端到端测试使用可控时钟验证第 11 个请求被限流及额度恢复，不依赖本机发送速度。

## 代码结构

| 包 | 职责 |
| --- | --- |
| controller | HTTP 接入、请求校验和错误响应 |
| probe | TCP 连接、批次调度和结果模型 |
| ratelimit | 全局令牌桶及 HTTP 拦截 |
| src/test | 启动、接口、批次调度、限流和端到端测试 |
