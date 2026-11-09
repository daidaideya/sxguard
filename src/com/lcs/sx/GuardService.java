package com.lcs.sx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 掉线自动重连守护服务。
 * 逻辑：每 3 秒探测一次联网状态（204 探测），掉线立刻走 authorize -> connect 重新认证。
 * 认证失败时先用上次保存的 context 做 find-session 找回旧会话，再注销重建，避免 109 / 重复登录。
 */
public class GuardService extends Service {

    public static final String ACTION_START = "com.lcs.sx.START";
    public static final String ACTION_STOP = "com.lcs.sx.STOP";
    public static final String ACTION_NOW = "com.lcs.sx.NOW";
    public static final String ACTION_LOGOUT = "com.lcs.sx.LOGOUT";

    private static final String DEFAULT_HOST = "115.239.134.16";
    private static final String DEFAULT_PORT = "9956";
    private static final String DEFAULT_NASIP = "125.123.1.40";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL = "guard";

    private volatile boolean running;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    public static volatile boolean SERVICE_ALIVE = false;
    public static volatile String STATE = "未启动";
    public static volatile int RECONNECTS = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopGuard();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("守护中"));
        if (ACTION_LOGOUT.equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    doLogout();
                }
            }).start();
            return START_STICKY;
        }
        if (ACTION_NOW.equals(action)) {
            SxLog.log("手动触发一次重连");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    doLogin(true);
                }
            }).start();
            return START_STICKY;
        }
        startGuard();
        return START_STICKY;
    }

    private void startGuard() {
        if (running || SERVICE_ALIVE) return;
        running = true;
        SERVICE_ALIVE = true;
        SxLog.setBool(this, "guard", true);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sx:guard");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignore) { }
        SxLog.log("守护服务启动");
        BootJobService.schedule(this);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "sx-guard");
        worker.start();
    }

    private void stopGuard() {
        running = false;
        SERVICE_ALIVE = false;
        STATE = "已停止";
        SxLog.setBool(this, "guard", false);
        SxLog.log("守护服务停止");
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignore) { }
        }
        if (worker != null) worker.interrupt();
        worker = null;
        updateNotification("已停止");
    }

    private void loop() {
        int failStreak = 0;
        long lastKeepAlive = 0;
        while (running) {
            try {
                boolean online = NetUtil.isOnline();
                if (online) {
                    if (!"在线".equals(STATE)) SxLog.log("网络在线");
                    STATE = "在线";
                    failStreak = 0;
                    updateNotification("在线");
                    long now = System.currentTimeMillis();
                    if (now - lastKeepAlive > 600_000L) {
                        lastKeepAlive = now;
                        refreshToken();
                    }
                } else {
                    STATE = "掉线";
                    SxLog.log("检测到掉线，开始重连");
                    updateNotification("掉线，重连中…");
                    if (doLogin(false)) {
                        failStreak = 0;
                        if (NetUtil.isOnline()) {
                            STATE = "在线";
                            RECONNECTS++;
                            SxLog.log("重连成功（累计 " + RECONNECTS + " 次）");
                        } else {
                            SxLog.log("认证返回成功，但网络仍不通");
                            failStreak++;
                        }
                    } else {
                        failStreak++;
                    }
                }
            } catch (Throwable t) {
                SxLog.log("循环异常: " + t);
                failStreak++;
            }
            long sleep = 3000;
            if (failStreak > 0) {
                sleep = Math.min(30_000L, 3000L * (1L << Math.min(4, failStreak)));
            }
            try { Thread.sleep(sleep); } catch (InterruptedException ignore) { }
        }
    }

    /** 一次完整登录尝试 */
    private synchronized boolean doLogin(boolean force) {
        String user = SxLog.get(this, "user", "");
        String pass = SxLog.get(this, "pass", "");
        if (user.isEmpty() || pass.isEmpty()) {
            SxLog.log("未配置账号密码");
            return false;
        }

        if (!force && NetUtil.isOnline()) {
            SxLog.log("当前已在线，无需登录");
            return true;
        }
        NetUtil.PortalInfo pi = NetUtil.probePortal();
        String host = SxLog.get(this, "host", "");
        if (host.isEmpty()) host = DEFAULT_HOST;
        String portS = SxLog.get(this, "port", "");
        if (portS.isEmpty()) portS = DEFAULT_PORT;
        String userIp = NetUtil.localIp();
        String userMac = NetUtil.wifiMac();
        String nasIp = SxLog.get(this, "nasip", "");
        String wlanAc = SxLog.get(this, "wlanac", "");

        if (pi != null) {
            if (!pi.portalHost.isEmpty()) host = pi.portalHost;
            if (pi.portalPort > 0) portS = String.valueOf(pi.portalPort);
            if (!pi.userIp.isEmpty()) userIp = pi.userIp;
            if (!pi.userMac.isEmpty()) userMac = pi.userMac;
            if (!pi.nasIp.isEmpty()) nasIp = pi.nasIp;
            if (!pi.wlanAcName.isEmpty()) wlanAc = pi.wlanAcName;
        }
        if (userMac.isEmpty()) userMac = "b4-c4-fc-67-c7-32";
        int port = 9956;
        try { port = Integer.parseInt(portS); } catch (Throwable ignore) { }

        if (nasIp.isEmpty()) nasIp = DEFAULT_NASIP;
        SxLog.set(this, "nasip", nasIp);
        if (!wlanAc.isEmpty()) SxLog.set(this, "wlanac", wlanAc);

        SxProto sx = new SxProto(host, port, user, pass);
        SxLog.log("登录 " + host + ":" + port + " ip=" + userIp + " nasip=" + nasIp);
        try {
            boolean ok = sx.connect(userIp, nasIp, wlanAc, userMac);
            SxLog.log(ok ? "connect 成功 session=" + sx.lastSessionId : "connect 失败: " + sx.lastError);
            if (ok) {
                saveState(sx);
                return true;
            }
            // 109 / 已有会话：先找回并注销旧会话，再重试一次
            String ctx = SxLog.get(this, "ctx", "");
            if (!ctx.isEmpty()) {
                SxLog.log("尝试 find-session 恢复旧会话");
                if (sx.findSession(userIp, ctx)) {
                    SxLog.log("find-session 成功 session=" + sx.lastSessionId + "，注销后重建");
                    sx.disconnect();
                    boolean ok2 = sx.connect(userIp, nasIp, wlanAc, userMac);
                    SxLog.log(ok2 ? "重连成功 session=" + sx.lastSessionId : "重连失败: " + sx.lastError);
                    if (ok2) {
                        saveState(sx);
                        return true;
                    }
                } else {
                    SxLog.log("find-session 失败: " + sx.lastError);
                }
            }
        } catch (Throwable t) {
            SxLog.log("登录异常: " + t);
        }
        return false;
    }

    /** 主动注销当前会话（用于测试掉线重连 / 换号） */
    private synchronized void doLogout() {
        String token = SxLog.get(this, "token", "");
        String session = SxLog.get(this, "session", "");
        String host = SxLog.get(this, "host", DEFAULT_HOST);
        String portS = SxLog.get(this, "port", DEFAULT_PORT);
        int port = 9956;
        try { port = Integer.parseInt(portS); } catch (Throwable ignore) { }
        if (token.isEmpty() || session.isEmpty()) {
            SxLog.log("没有可注销的会话（未登录过）");
            return;
        }
        SxProto sx = new SxProto(host, port, SxLog.get(this, "user", ""), SxLog.get(this, "pass", ""));
        sx.lastClientToken = token;
        sx.lastSessionId = session;
        sx.lastSessionToken = SxLog.get(this, "stoken", "");
        sx.lastContext = SxLog.get(this, "ctx", "");
        try {
            boolean ok = sx.disconnect();
            SxLog.log(ok ? "已注销会话 " + session : "注销失败: " + sx.lastError);
        } catch (Throwable t) {
            SxLog.log("注销异常: " + t);
        }
    }

    public static void logout(Context c) {
        Intent i = new Intent(c, GuardService.class);
        i.setAction(ACTION_LOGOUT);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    private void saveState(SxProto sx) {
        SxLog.set(this, "host", sx.hostName());
        SxLog.set(this, "port", String.valueOf(sx.portNum()));
        SxLog.set(this, "ctx", sx.lastContext);
        SxLog.set(this, "session", sx.lastSessionId);
        SxLog.set(this, "token", sx.lastClientToken);
        SxLog.set(this, "stoken", sx.lastSessionToken);
    }

    /** 定时刷新 authorization token，防止 30 分钟过期后无法注销/查询 */
    private void refreshToken() {
        String token = SxLog.get(this, "token", "");
        if (token.isEmpty()) return;
        String host = SxLog.get(this, "host", "115.239.134.16");
        String port = SxLog.get(this, "port", "9956");
        String user = SxLog.get(this, "user", "");
        String pass = SxLog.get(this, "pass", "");
        int p = 9956;
        try { p = Integer.parseInt(port); } catch (Throwable ignore) { }
        SxProto sx = new SxProto(host, p, user, pass);
        sx.lastClientToken = token;
        sx.lastSessionId = SxLog.get(this, "session", "");
        sx.lastSessionToken = SxLog.get(this, "stoken", "");
        try {
            if (sx.getSession()) {
                saveState(sx);
            }
        } catch (Throwable ignore) { }
    }

    // ---------------------------------------------------------- 通知
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "闪讯守护", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("闪讯守护")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignore) { }
    }

    public static void start(Context c) {
        Intent i = new Intent(c, GuardService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, GuardService.class);
        i.setAction(ACTION_STOP);
        c.startService(i);
    }

    public static void now(Context c) {
        Intent i = new Intent(c, GuardService.class);
        i.setAction(ACTION_NOW);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
}
