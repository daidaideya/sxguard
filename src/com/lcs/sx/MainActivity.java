package com.lcs.sx;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity implements SxLog.Listener {

    private EditText userEt;
    private EditText passEt;
    private EditText nasEt;
    private TextView logTv;
    private TextView statusTv;
    private ScrollView scroll;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        userEt = findViewById(R.id.user);
        passEt = findViewById(R.id.pass);
        nasEt = findViewById(R.id.nasip);
        logTv = findViewById(R.id.log);
        statusTv = findViewById(R.id.status);
        scroll = findViewById(R.id.scroll);

        userEt.setText(SxLog.get(this, "user", ""));
        passEt.setText(SxLog.get(this, "pass", ""));
        nasEt.setText(SxLog.get(this, "nasip", "125.123.1.40"));

        findViewById(R.id.btnStart).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                GuardService.start(MainActivity.this);
            }
        });
        findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GuardService.stop(MainActivity.this);
            }
        });
        findViewById(R.id.btnNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                GuardService.now(MainActivity.this);
            }
        });
        findViewById(R.id.btnLogout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GuardService.logout(MainActivity.this);
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logTv.setText("");
            }
        });
        findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBattery();
            }
        });

        StringBuilder sb = new StringBuilder();
        for (String line : SxLog.snapshot()) sb.append(line).append('\n');
        logTv.setText(sb.toString());
        SxLog.addListener(this);

        BootJobService.schedule(this);

        ticker = new Runnable() {
            @Override
            public void run() {
                String st = GuardService.SERVICE_ALIVE ? GuardService.STATE : "未启动";
                statusTv.setText("状态：" + st + "   重连次数：" + GuardService.RECONNECTS);
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(ticker);
    }

    private void save() {
        SxLog.set(this, "user", userEt.getText().toString().trim());
        SxLog.set(this, "pass", passEt.getText().toString().trim());
        SxLog.set(this, "nasip", nasEt.getText().toString().trim());
        SxLog.log("已保存账号配置");
    }

    private void requestIgnoreBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                SxLog.log("已在电池优化白名单中");
            }
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignore) { }
        }
    }

    @Override
    public void onLog(final String line) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                logTv.append(line + "\n");
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        SxLog.removeListener(this);
        if (ticker != null) ui.removeCallbacks(ticker);
        super.onDestroy();
    }
}
