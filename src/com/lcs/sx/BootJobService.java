package com.lcs.sx;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 兜底保活：持久化 JobScheduler 任务，开机后由系统拉起，
 * 一旦发现守护服务没在跑就重新拉起（MIUI 可能拦截 BOOT_COMPLETED，这条路径更稳）。
 */
public class BootJobService extends JobService {

    private static final int JOB_ID = 2001;

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            if (SxLog.getBool(this, "guard", false) && !GuardService.SERVICE_ALIVE) {
                SxLog.log("JobScheduler 兜底拉起守护服务");
                GuardService.start(this);
            }
        } catch (Throwable ignore) { }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, BootJobService.class))
                    .setPersisted(true)
                    .setPeriodic(15 * 60 * 1000L)
                    .build();
            js.schedule(job);
        } catch (Throwable t) {
            SxLog.log("JobScheduler 注册失败: " + t);
        }
    }
}
