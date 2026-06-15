#!/system/bin/sh
# 卸载时清理
# 杀掉守护进程
ps -ef 2>/dev/null | grep "shanxun-restart daemon" | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null
# 清理日志（可选，注释掉则保留）
# rm -f /data/local/tmp/shanxun_restart.log
