# shanxun-auto-restart

闪讯(Netkeeper)掉线自动重启 + 自动连接。Magisk 模块，开机自启守护进程。

## 原理

每 30 秒 ping baidu.com 检测网络，不通就自动：
1. kill 闪讯
2. 唤屏 + 回桌面
3. 重新打开闪讯
4. 点「连接」按钮
5. 验证网络是否恢复

## 安装

### Magisk 模块（推荐）

1. 下载 `shanxun-auto-restart` 文件夹，压缩为 zip
2. Magisk → 模块 → 从本地安装 → 选 zip → 重启

### 手动脚本

```bash
adb push restart_shanxun.sh /data/local/tmp/
adb shell su -c "sh /data/local/tmp/restart_shanxun.sh setup"    # 首次：授权
adb shell su -c "sh /data/local/tmp/restart_shanxun.sh restart"  # 手动重启
adb shell su -c "nohup sh /data/local/tmp/restart_shanxun.sh daemon &"  # 守护
```

## 命令

| 命令 | 说明 |
|------|------|
| `setup` | 首次使用：授予后台运行权限（Magisk 模块自动处理） |
| `restart` | kill → 重启 → 点连接 → 验网络 |
| `stop` | 仅停止闪讯 |
| `start` | 仅启动闪讯 |
| `daemon` | 守护模式：30 秒检查一次，断网自动重启 |
| `status` | 查看状态和日志 |

## 配置

编辑 `shanxun-restart` 或 `restart_shanxun.sh` 顶部：

```shell
CHECK_INTERVAL=30      # 检查间隔（秒）
RESTART_HOUR=4         # 每日定时重启（小时）
RESTART_MINUTE=0       # 每日定时重启（分钟）
ENABLE_SCHEDULED=true  # 启用定时重启
ENABLE_WATCHDOG=true   # 启用掉线检测
```

## 要求

- Android + Root（Magisk）
- 闪讯已记住账号密码（自动登录状态）
- 包名：`com.xinli.netkeeper`

## 日志

```bash
adb shell su -c "tail -f /data/local/tmp/shanxun_restart.log"
```
