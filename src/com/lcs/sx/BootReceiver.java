package com.lcs.sx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String act = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(act)
                || "android.intent.action.QUICKBOOT_POWERON".equals(act)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(act)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(act);
        if (!boot) return;
        if (SxLog.getBool(context, "guard", false)) {
            SxLog.log("开机自启，拉起守护服务");
            GuardService.start(context);
        }
    }
}
