# SxGuard — 闪讯客户端 · 掉线自动重连

自己实现的闪讯（Netkeeper / 欣利 WeNet Portal）客户端，替代官方 `com.xinli.netkeeper`。
**直接跑 Portal REST 协议，不靠点官方 App 的按钮，不需要 root 运行**，3 秒一次探测，掉线后 0.4~1 秒内自动重新认证上线。

> 仓库历史里的 `shanxun-auto-restart`（kill 掉官方 App 再点「连接」的 Magisk 模块）是第一版方案，
> 已被本项目的原生协议实现完全替代，代码仍可在早期 commit 里找到。

## 特性

- 原生协议登录：`authorize` → `sessions`，HMAC-SHA1 签名，不依赖官方 App
- 掉线检测：`generate_204` 探测（MIUI / gstatic / 华为三路），被劫持即判离线
- 自动重连：失败指数退避（3s → 30s 封顶），不会打爆服务器
- 会话自愈：`connect` 失败时用上次的 `context` 走 `find-session` → 注销 → 重建
- 前台服务常驻 + 常亮通知，token 每 10 分钟续期（authorization token 1800s 过期）
- 开机自启：`BOOT_COMPLETED` + `JobScheduler` 持久化任务 +（可选）Magisk 模块兜底
- 全部日志双通道：界面滚动日志 + `adb logcat -s SxGuard:I`

## 快速开始

安装（设备已 root 时用 `su` 装可绕过 MIUI 的 USB 安装限制）：

```bash
adb push SxGuard.apk /data/local/tmp/sx.apk
adb shell su -c "pm install -r -g /data/local/tmp/sx.apk"
adb shell su -c "cmd appops set com.lcs.sx 10008 allow"   # MIUI 自启动放行（重启会被还原，见下）
adb shell am start -n com.lcs.sx/.MainActivity
```

打开后填账号（形如 `手机号@校区.XY`）和密码，点 **启动守护**。

| 按钮 | 作用 |
| --- | --- |
| 启动守护 | 启动前台常驻服务，3s 一次探测 + 自动重连 |
| 停止 | 停止守护（不注销，链路保持） |
| 立即重连 | 强制走一次完整认证（诊断用，日志打印服务端原文） |
| 注销下线 | 调 `DELETE /portal/v1.0/sessions/{id}` 主动下线（验证重连用） |
| 忽略电池优化 | 拉起系统电池白名单授权 |

服务已导出，可以完全脱离界面用 adb 操作：

```bash
adb shell am start-foreground-service -n com.lcs.sx/.GuardService -a com.lcs.sx.START
adb shell am start-foreground-service -n com.lcs.sx/.GuardService -a com.lcs.sx.NOW
adb shell am start-foreground-service -n com.lcs.sx/.GuardService -a com.lcs.sx.LOGOUT
adb shell am start-foreground-service -n com.lcs.sx/.GuardService -a com.lcs.sx.STOP
adb logcat -s SxGuard:I
```

## 开机自启

MIUI 的 `AutoStartManagerService` 会直接拒绝来自 `BOOT_COMPLETED` 的进程拉起：

```
Unable to launch app com.lcs.sx/10216 for broadcast BOOT_COMPLETED: process is not permitted to auto start
```

而且 `cmd appops set com.lcs.sx 10008 allow` 在重启后会被 MIUI 改回 `ignore`。两条兜底，任选：

1. **Magisk 模块（推荐）** `magisk/sxboot/`
   开机等 `sys.boot_completed` 后拉起守护，并每 60s 检查 `pidof com.lcs.sx`，进程被杀会自动复活。
   ```bash
   python build.py      # 产出 magisk/sxguard-boot.zip
   adb push magisk/sxguard-boot.zip /sdcard/
   adb shell su -c "magisk --install-module /sdcard/sxguard-boot.zip"
   ```
2. **手动开一次开关**：安全中心 → 应用管理 → 闪讯守护 → 自启动；省电策略选「无限制」。

## 协议逆向结果

服务器：`http://<校园 Portal 地址>:9956`（`X-PWS-Scheme` 响应头下发，`apiuri=/portal/api`）

| 名称 | 值 |
| --- | --- |
| client_id | `jportal` |
| sharedSecret | `s3cr3t` |
| version | `1.3.6` |
| scope | `portal-rest-api` |

端点：

```
GET    /portal/v1.0/authorize?response_type=challenge&client_id=jportal&scope=portal-rest-api
                              &require_token=true&need_refresh_token=false&user_ip=IP&user_mac=02-00-00-00-00-00
POST   /portal/v1.0/sessions          # 登录 / 更新会话
DELETE /portal/v1.0/sessions/{id}     # 注销
GET    /portal/v1.0/sessions          # 查询
POST   /portal/v1.0/sessions/find     # 用 context 找回会话
```

鉴权头：

```
X-Xinli-Auth: Digest nonce="…", response="…", client_token="…", client_id="jportal",
              signature="…", signature_method="HMAC-SHA1", timestamp="…", session_token="…", version="1.0"
```

签名（HMAC-SHA1，key = `sharedSecret`，全小写 hex）：

```
response  = hex(HMAC(key, challenge))                          # challenge 来自 authorize
signature = hex(HMAC(key, METHOD + "&" + urlencode(path) + "&" + digestStr + "&" + entityStr))

digestStr : nonce, response, client_token, client_id, signature, signature_method,
            timestamp, session_token, version      # 顺序固定，空值跳过，key="value" 逗号+空格分隔
entityStr : 表单参数按 key 字典序重拼（不是原始 body 顺序），key=urlencode(value)&…
path      : 含 query 时整体 urlencode；DELETE 的 entityStr 是空串，消息以 "&" 结尾
timestamp : 秒级
```

`connect` 表单参数：`username, password, os=android, version=1.3.6, user_ip,
user_mac=02-00-00-00-00-00, redirect_url=http://HOST:PORT?userip=…&wlanacname=…&nasip=…&usermac=<真实MAC>`（MAC 用 `-` 分隔）

### 踩过的坑（重要）

> **`Content-Type` 必须是标准写法 `application/x-www-form-urlencoded`。**
> 官方 App 自己发的是 `application/x-form-urlencoded`（少个 `www`），服务端因此在验签时
> 不去解析 body，重算的 `entityStr` 变成空串 → 签名永远对不上 → 永远
> `401 {"error":109,"error_description":"rest authentication error"}`。
> 换成标准头后，同一套签名单子立刻 `200`。

其他已验证结论：

- `user_mac` 参数固定 `02-00-00-00-00-00`，真实 MAC 只出现在 `redirect_url` 的 `usermac` 里
- `nasip`（BRAS 地址）必须真实，乱填返回 `404`；密码错返回 401 `109`
- 同一账号多次 `connect` 会复用同一个 session，不互踢
- 注销必须同时带 `client_token` + `session_token`，否则 `403 error 110 Access is denied`
- 服务器时钟可能比设备快/慢几分钟，`timestamp` 用设备时间即可
- 会话丢失（换网/被踢）时用 `find-session` + 上次 `context` 能找回，再注销重建

## 构建

不需要 Gradle，一条命令：

```bash
python build.py      # → SxGuard.apk + magisk/sxguard-boot.zip
```

链路：`aapt2` 编译资源 → `javac --release 8`（`android.jar`，无 AndroidX）→ `d8` → 打包 → `zipalign` → `apksigner`
（`debug.keystore` 不存在时自动 `keytool` 生成，所以不提交密钥）。

环境变量在 `build.py` 顶部：`SDK`（默认 `D:/sdk`）、`BT`（build-tools，需 **35.0.0** 以上，
29.0.3 的 `d8` 在 JDK 21 下会 `NullPointerException`）、`KEYTOOL`。

## 目录结构

```
├── AndroidManifest.xml
├── build.py                      # 无 Gradle 构建脚本
├── res/layout/main.xml
├── src/com/lcs/sx/
│   ├── SxProto.java              # 协议实现：裸 Socket HTTP + HMAC-SHA1 签名 + chunked 解析
│   ├── NetUtil.java              # 本机 IP / MAC / 204 在线探测 / captive portal 参数发现
│   ├── GuardService.java         # 前台守护 + 探测 + 重连 + 续期 + 注销
│   ├── BootReceiver.java         # BOOT_COMPLETED / QUICKBOOT / PACKAGE_REPLACED
│   ├── BootJobService.java       # JobScheduler 持久化兜底
│   ├── MainActivity.java
│   └── SxLog.java                # 日志（界面 + logcat）+ 配置
└── magisk/sxboot/                # 开机兜底模块（module.prop + service.sh）
```

## 说明

- 仅适用于授权使用的校园网账号，请自行确认所在学校是否允许第三方客户端；账号密码只存在本机 `shared_prefs`。
- 官方 App 可以共存，但别同时开它的自动重连：它检测到已在线会报 `错误：1034` 拒绝拨号，不会抢会话。
- 换宿舍 / 换 BRAS 后若日志出现 `connect 失败: 404`，把界面里的 `nasip` 清空，守护会在离线时从 captive portal 跳转里自动取新的。
- 日志里 `400 <html>等待` = 请求行/头被改坏；`403 error 110` = 缺 `session_token`。
