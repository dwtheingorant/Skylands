# Debug Session: run-client-launch

- Status: OPEN
- Goal: 启动 NeoForge 1.21.1 客户端，收集运行日志与报错

## Hypotheses

1. NeoForge 运行时依赖仍然无法下载，`runClient` 会在真正启动 Minecraft 之前失败。
2. 依赖下载通过后，`SkylandsChunkGenerator` 相关 API 调用可能出现编译错误或链接错误。
3. 世界生成相关逻辑可能在客户端初始化或数据加载阶段触发注册/Codec 错误。
4. 海洋/珊瑚地物新增逻辑可能在运行时因特征注册表访问方式不匹配而报错。

## Plan

1. 运行 `./gradlew runClient`
2. 记录启动日志与失败阶段
3. 根据证据判断是环境问题还是代码问题

## Evidence

- `./gradlew runClient` 未进入 Minecraft 启动阶段。
- 失败点：`createMinecraftArtifacts` 解析 `neoFormRuntimeTool` 依赖时下载 `net.neoforged:neoform-runtime:2.0.18` 失败。
- 具体网络错误：访问 `https://maven.neoforged.net/releases/.../neoform-runtime-2.0.18-all.jar` 时 TLS 握手被远端终止。
- `curl -I --tlsv1.2 https://maven.neoforged.net/...` 同样失败，报 `LibreSSL SSL_connect: SSL_ERROR_SYSCALL`。
- `openssl s_client -connect maven.neoforged.net:443 -servername maven.neoforged.net` 连接建立后未收到对端证书，握手读取 `0 bytes`。
- 切换到本机 `JDK 21` 后，Gradle 仍然在访问 `maven.neoforged.net` 时抛出 `SSLHandshakeException: Remote host terminated the handshake`。

## Hypothesis Status

1. 依赖下载/TLS 问题：Confirmed
2. Java 编译/API 不匹配：Not reached
3. 客户端注册或 worldgen 加载错误：Not reached
4. 珊瑚地物运行时问题：Not reached

## Conclusion

- 根因更接近本机到 `maven.neoforged.net` 的 TLS/网络链路问题，而不是 Java 24 独有问题。
- 运行 Mod 之前需要先解决该域名访问，或为 NeoForge 相关制品提供可用镜像/代理。
