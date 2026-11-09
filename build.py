#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""无 Gradle 构建闪讯守护 APK：aapt2 + javac + d8 + zipalign + apksigner"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

SDK = "D:/sdk"
BT = os.path.join(SDK, "build-tools/35.0.0")
AJAR = os.path.join(SDK, "platforms/android-29/android.jar")
JH = "C:/Program Files/Java/jdk-21.0.11/bin" if os.path.isdir("C:/Program Files/Java/jdk-21.0.11/bin") else ""
KEYTOOL = os.path.join(JH, "keytool.exe") if JH else "keytool"
ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "build")
KS = os.path.join(ROOT, "debug.keystore")


def run(cmd, **kw):
    print(">>", " ".join(cmd) if isinstance(cmd, list) else cmd)
    r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        sys.exit("FAILED: %s" % cmd)
    if r.stdout.strip():
        print(r.stdout.strip()[:1200])
    return r


def tool(name):
    exe = os.path.join(BT, name + ".bat")
    if os.path.exists(exe):
        return exe
    exe = os.path.join(BT, name + ".exe")
    return exe if os.path.exists(exe) else name


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(os.path.join(OUT, "gen"))
    os.makedirs(os.path.join(OUT, "obj"))

    # 1. 资源编译
    run([tool("aapt2"), "compile", "--dir", os.path.join(ROOT, "res"),
         "-o", os.path.join(OUT, "res.zip")])

    # 2. 链接 + 生成 R.java
    run([tool("aapt2"), "link", "-o", os.path.join(OUT, "base.apk"),
         "-I", AJAR,
         "--manifest", os.path.join(ROOT, "AndroidManifest.xml"),
         "--java", os.path.join(OUT, "gen"),
         "--min-sdk-version", "21", "--target-sdk-version", "29",
         "--auto-add-overlay",
         os.path.join(OUT, "res.zip")])

    # 3. javac
    srcs = glob.glob(os.path.join(ROOT, "src/**/*.java"), recursive=True) + \
           glob.glob(os.path.join(OUT, "gen/**/*.java"), recursive=True)
    run(["javac", "-nowarn", "--release", "8", "-classpath", AJAR,
         "-encoding", "UTF-8", "-d", os.path.join(OUT, "obj")] + srcs)

    # 4. d8
    classes = glob.glob(os.path.join(OUT, "obj/**/*.class"), recursive=True)
    run(["java", "-cp", os.path.join(BT, "lib/d8.jar"), "com.android.tools.r8.D8",
         "--min-api", "21", "--lib", AJAR,
         "--output", OUT] + classes)

    # 5. 打包
    apk = os.path.join(OUT, "apk-unsigned.apk")
    shutil.copy(os.path.join(OUT, "base.apk"), apk)
    with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(OUT, "classes.dex"), "classes.dex")

    # 6. 对齐
    aligned = os.path.join(OUT, "apk-aligned.apk")
    run([tool("zipalign"), "-f", "4", apk, aligned])

    # 7. 签名
    if not os.path.exists(KS):
        run([KEYTOOL, "-genkeypair", "-keystore", KS, "-storepass", "android",
             "-keypass", "android", "-alias", "debug", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=LCS, OU=Dev, O=LCS, L=CN, S=CN, C=CN"])
    signed = os.path.join(ROOT, "SxGuard.apk")
    if os.path.exists(signed):
        os.remove(signed)
    run([tool("apksigner"), "sign", "--ks", KS, "--ks-pass", "pass:android",
         "--ks-key-alias", "debug", "--out", signed, aligned])
    run([tool("apksigner"), "verify", signed])

    # 8. 打包 Magisk 开机兜底模块（sxboot/ 源码 -> magisk/sxguard-boot.zip）
    mzip = os.path.join(ROOT, "magisk", "sxguard-boot.zip")
    moddir = os.path.join(ROOT, "magisk", "sxboot")
    if os.path.isdir(moddir):
        if os.path.exists(mzip):
            os.remove(mzip)
        with zipfile.ZipFile(mzip, "w", zipfile.ZIP_DEFLATED) as z:
            for name in ("module.prop", "service.sh"):
                z.write(os.path.join(moddir, name), name)
        print("OK ->", mzip, os.path.getsize(mzip), "bytes")

    print("OK ->", signed, os.path.getsize(signed), "bytes")


if __name__ == "__main__":
    main()
