#!/system/bin/sh
# ============================================
# 闪讯(Netkeeper) 定时自动重启 + 自动登录
# 需要 Root 权限运行
# ============================================

# ========== 配置区 ==========
PACKAGE="com.xinli.netkeeper"         # 闪讯包名
# 以下账号密码仅备用，闪讯会自动记住，正常不需要填
# USERNAME=""
# PASSWORD=""
# ===========================

LOG_FILE="/data/local/tmp/shanxun_restart.log"
MAX_LOG_LINES=200
WAIT_AFTER_KILL=3
MAX_RETRY=3
WAIT_UI_READY=5                       # 等待界面加载的秒数

# 守护模式配置
CHECK_INTERVAL=30                     # 检查间隔（秒），30秒
RESTART_HOUR=4                        # 每日定时重启小时（24小时制）
RESTART_MINUTE=0                      # 每日定时重启分钟
ENABLE_SCHEDULED=true                 # 启用每日定时重启
ENABLE_WATCHDOG=true                  # 启用掉线检测守护

log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $1"
    echo "$msg"
    echo "$msg" >> "$LOG_FILE"
    if [ -f "$LOG_FILE" ]; then
        local lines=$(wc -l < "$LOG_FILE")
        if [ "$lines" -gt "$MAX_LOG_LINES" ]; then
            local tmp=$(mktemp)
            tail -n $((MAX_LOG_LINES / 2)) "$LOG_FILE" > "$tmp"
            mv "$tmp" "$LOG_FILE"
        fi
    fi
}

# 检查 root
check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        log "错误: 需要 root 权限"
        exit 1
    fi
}

# 检查是否安装
check_installed() {
    if ! pm list packages 2>/dev/null | grep -q "$PACKAGE"; then
        log "错误: 未找到 $PACKAGE"
        exit 1
    fi
}

# 检查闪讯是否在运行
is_running() {
    [ -n "$(pidof "$PACKAGE" 2>/dev/null)" ]
}

# 强制停止闪讯
force_stop() {
    log "正在停止闪讯..."
    am force-stop "$PACKAGE" 2>/dev/null
    local pids=$(pidof "$PACKAGE" 2>/dev/null)
    if [ -n "$pids" ]; then
        kill -9 $pids 2>/dev/null
    fi
    sleep "$WAIT_AFTER_KILL"
    log "闪讯已停止"
}

# 启动闪讯 APP
launch_app() {
    log "正在启动闪讯..."
    # 唤醒屏幕 + 解锁（滑动上划）
    input keyevent KEYCODE_WAKEUP
    sleep 0.5
    input swipe 540 2000 540 800
    sleep 1
    # 回桌面再打开，避免 MIUI 后台限制
    input keyevent KEYCODE_HOME
    sleep 1
    am start -n "${PACKAGE}/com.xinli.wenet.activitys.WelcomeActivity" >/dev/null 2>&1
}

# ============================================
# UI 自动化登录（核心功能）
# 使用 uiautomator 获取界面元素并自动操作
# ============================================

# 获取当前界面的 UI 布局 XML（带重试，兼容 MIUI）
dump_ui() {
    local tmpfile="/data/local/tmp/shanxun_ui.xml"
    local attempt=0
    while [ "$attempt" -lt 3 ]; do
        rm -f "$tmpfile"
        # 确保屏幕亮着
        input keyevent KEYCODE_WAKEUP 2>/dev/null
        sleep 0.5
        uiautomator dump "$tmpfile" >/dev/null 2>&1
        if [ -f "$tmpfile" ] && [ -s "$tmpfile" ]; then
            cat "$tmpfile" 2>/dev/null
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    echo ""
    return 1
}

# 通过 text 或 resource-id 查找元素的 bounds 中心点
# 参数: $1=属性名(text/resource-id)  $2=属性值  $3=已dump的XML(可选)
find_element_center() {
    local attr="$1"
    local value="$2"
    local xml="$3"
    if [ -z "$xml" ]; then
        xml=$(dump_ui)
    fi
    if [ -z "$xml" ]; then
        echo ""
        return 1
    fi

    # 用 grep+sed 提取匹配节点的 bounds
    # bounds 格式: [left,top][right,bottom]
    local bounds=$(echo "$xml" | tr '>' '\n' | grep "${attr}=\"${value}\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | sed 's/bounds="//;s/"//')

    if [ -z "$bounds" ]; then
        # 模糊匹配：包含关键字
        bounds=$(echo "$xml" | tr '>' '\n' | grep "${attr}=\"[^\"]*${value}[^\"]*\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | sed 's/bounds="//;s/"//')
    fi

    if [ -n "$bounds" ]; then
        # 解析 [left,top][right,bottom] 并计算中心
        local left=$(echo "$bounds" | sed 's/\[//g;s/\]/ /g' | awk '{print $1}' | cut -d',' -f1)
        local top=$(echo "$bounds" | sed 's/\[//g;s/\]/ /g' | awk '{print $1}' | cut -d',' -f2)
        local right=$(echo "$bounds" | sed 's/\[//g;s/\]/ /g' | awk '{print $2}' | cut -d',' -f1)
        local bottom=$(echo "$bounds" | sed 's/\[//g;s/\]/ /g' | awk '{print $2}' | cut -d',' -f2)

        local cx=$(( (left + right) / 2 ))
        local cy=$(( (top + bottom) / 2 ))
        echo "$cx $cy"
        return 0
    fi

    echo ""
    return 1
}

# 点击指定坐标的元素
tap_center() {
    local coords="$1"
    if [ -n "$coords" ]; then
        local x=$(echo "$coords" | awk '{print $1}')
        local y=$(echo "$coords" | awk '{print $2}')
        input tap "$x" "$y"
        return 0
    fi
    return 1
}

# 方式1: 通过 text 属性查找并点击
tap_by_text() {
    local text="$1"
    local coords=$(find_element_center "text" "$text")
    if [ -n "$coords" ]; then
        log "找到按钮: \"$text\" → $coords"
        tap_center "$coords"
        return 0
    fi
    log "未找到按钮: \"$text\""
    return 1
}

# 方式2: 通过 resource-id 查找并点击
tap_by_id() {
    local id="$1"
    local coords=$(find_element_center "resource-id" "$id")
    if [ -n "$coords" ]; then
        log "找到元素: $id → $coords"
        tap_center "$coords"
        return 0
    fi
    log "未找到元素: $id"
    return 1
}

# 方式3: 点击输入框并输入文本
# 参数: $1=输入框标识(text/resource-id)  $2=标识值  $3=要输入的内容
fill_input() {
    local find_by="$1"
    local find_value="$2"
    local input_text="$3"

    # 查找输入框
    local coords=$(find_element_center "$find_by" "$find_value")
    if [ -z "$coords" ]; then
        log "未找到输入框: $find_value"
        return 1
    fi

    # 点击输入框获取焦点
    tap_center "$coords"
    sleep 1

    # 清空已有内容
    input keyevent KEYCODE_CTRL_A
    sleep 0.3
    input keyevent KEYCODE_DEL
    sleep 0.3

    # 输入文本
    input text "$input_text"
    log "已输入: $find_value"
    sleep 0.5
    return 0
}

# 点击「连 接」按钮（闪讯已记住账号密码，直接点连接）
auto_login() {
    log "===== 点击连接 ====="
    sleep "$WAIT_UI_READY"

    local xml=$(dump_ui)
    if [ -z "$xml" ]; then
        log "无法获取 UI 布局"
        return 1
    fi

    # 已连接就不点了，但验一下网络
    if echo "$xml" | grep -q 'resource-id="com.xinli.netkeeper:id/aw"'; then
        log "已有断开按钮，跳过点击"
        verify_login
        return $?
    fi

    # 点「连 接」按钮: id/b1
    local btn_coords=$(find_element_center "resource-id" "com.xinli.netkeeper:id/b1" "$xml")
    if [ -z "$btn_coords" ]; then
        btn_coords=$(find_element_center "text" "连 接" "$xml")
    fi
    if [ -n "$btn_coords" ]; then
        tap_center "$btn_coords"
        log "✓ 已点击连接"
    else
        log "找不到连接按钮"
        return 1
    fi

    sleep 8
    verify_login
}

# 网络检查：就 ping baidu.com
check_network() {
    ping -c 1 -W 3 www.baidu.com >/dev/null 2>&1
}

# 验证登录状态（多方式检测，兼容校园网）
verify_login() {
    if check_network; then
        log "✓ 登录成功，网络已通"
        return 0
    fi
    log "✗ 网络不通，登录失败"
    return 1
}

# ============================================
# 完整重启流程
# ============================================
restart() {
    log "========== 开始重启闪讯 =========="

    # 1. 停止
    force_stop
    sleep 2

    # 2. 启动
    launch_app
    sleep "$WAIT_UI_READY"

    # 3. 验证 APP 是否启动
    if ! is_running; then
        log "闪讯启动失败，重试..."
        launch_app
        sleep "$WAIT_UI_READY"
    fi

    if ! is_running; then
        log "✗ 闪讯启动失败"
        return 1
    fi

    # 4. 点连接
    local attempt=0
    while [ "$attempt" -lt "$MAX_RETRY" ]; do
        attempt=$((attempt + 1))
        log "登录尝试 $attempt/$MAX_RETRY"
        auto_login && return 0
        input keyevent KEYCODE_BACK
        sleep 2
    done

    log "========== 登录失败 =========="
    return 1
}

# 状态查看
status() {
    echo "=== 闪讯状态 ==="
    echo "包名: $PACKAGE"
    if is_running; then
        echo "状态: 运行中 (PID: $(pidof "$PACKAGE"))"
    else
        echo "状态: 未运行"
    fi

    if check_network; then
        echo "网络: 已连通 ✓"
    else
        echo "网络: 未连通 ✗"
    fi

    echo ""
    echo "=== 最近日志 ==="
    if [ -f "$LOG_FILE" ]; then
        tail -15 "$LOG_FILE"
    else
        echo "(暂无日志)"
    fi
}

# ============================================
# 守护模式：掉线自动重启 + 定时重启
# ============================================
daemon() {
    check_root
    check_installed
    log "===== 守护模式启动 ====="
    log "检查间隔:${CHECK_INTERVAL}s | 定时重启:${RESTART_HOUR}:${RESTART_MINUTE} | 掉线检测:${ENABLE_WATCHDOG}"

    local last_restart_day=-1

    while true; do
        local current_hour=$(date '+%H' | sed 's/^0//')
        local current_min=$(date '+%M' | sed 's/^0//')
        local current_day=$(date '+%j')

        # 每日定时重启
        if [ "$ENABLE_SCHEDULED" = "true" ]; then
            if [ "$current_hour" -eq "$RESTART_HOUR" ] && [ "$current_min" -eq "$RESTART_MINUTE" ] && [ "$current_day" != "$last_restart_day" ]; then
                log "定时重启触发 (${RESTART_HOUR}:${RESTART_MINUTE})"
                restart
                last_restart_day="$current_day"
            fi
        fi

        # 掉线检测：网络不通 → 立刻 kill → 重启 → 登录
        if [ "$ENABLE_WATCHDOG" = "true" ]; then
            if ! is_running; then
                log "闪讯进程不在了，直接重启+登录"
                restart
            elif ! check_network; then
                log "断网了！等3秒二次确认..."
                sleep 3
                if ! check_network; then
                    log "确认断网，kill 闪讯 → 重启 → 重新登录"
                    restart
                else
                    log "网络恢复，虚惊一场"
                fi
            fi
        fi

        sleep "$CHECK_INTERVAL"
    done
}

# ========== 入口 ==========
case "$1" in
    restart)
        check_root
        check_installed
        restart
        ;;
    stop)
        check_root
        force_stop
        ;;
    start)
        check_root
        launch_app
        ;;
    status)
        status
        ;;
    daemon)
        daemon
        ;;
    setup)
        check_root
        check_installed
        log "===== 一次性权限设置 ====="
        cmd appops set "$PACKAGE" START_FOREGROUND allow 2>/dev/null
        cmd appops set "$PACKAGE" RUN_IN_BACKGROUND allow 2>/dev/null
        dumpsys deviceidle whitelist +"$PACKAGE" 2>/dev/null
        log "✓ 已授予后台运行和前台服务权限"
        log "✓ 已加入电池优化白名单"
        log "现在可以运行: sh $0 restart"
        ;;
    *)
        echo "用法: su -c sh $0 {setup|restart|stop|start|daemon|status}"
        echo ""
        echo "  setup    - 一次性权限设置（首次使用必须运行）"
        echo "  restart  - 重启闪讯并自动登录"
        echo "  stop     - 仅停止闪讯"
        echo "  start    - 仅启动闪讯"
        echo "  daemon   - 启动守护模式（掉线自动重启+登录）"
        echo "  status   - 查看状态和日志"
        ;;
esac
