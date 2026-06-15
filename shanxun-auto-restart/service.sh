#!/system/bin/sh
# Magisk 模块 service.sh - 开机后自动启动闪讯守护
# 等待系统启动完成
sleep 30

MODDIR=${0%/*}
SCRIPT="$MODDIR/system/bin/shanxun-restart"

# 确保脚本有执行权限
chmod 755 "$SCRIPT"

# 一次性权限设置（每次开机都执行，确保权限不丢）
PACKAGE="com.xinli.netkeeper"
cmd appops set "$PACKAGE" START_FOREGROUND allow 2>/dev/null
cmd appops set "$PACKAGE" RUN_IN_BACKGROUND allow 2>/dev/null
dumpsys deviceidle whitelist +"$PACKAGE" 2>/dev/null

# 后台启动守护模式
nohup sh "$SCRIPT" daemon >/dev/null 2>&1 &
