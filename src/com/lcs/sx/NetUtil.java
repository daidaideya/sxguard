package com.lcs.sx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

/** 网络环境探测：本机 IP / MAC / 认证后的 nasip、wlanacname、portal 地址 / 在线判断 */
public class NetUtil {

    /** 本机 IPv4（wlan0 / eth0 优先） */
    public static String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String fallback = "";
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.indexOf(':') >= 0) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) return ip;
                    if (fallback.isEmpty()) fallback = ip;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return "";
        }
    }

    /** wlan0 MAC，格式 b4-c4-fc-67-c7-32；失败返回空 */
    public static String wifiMac() {
        String[] names = {"wlan0", "eth0"};
        for (String n : names) {
            try {
                NetworkInterface ni = NetworkInterface.getByName(n);
                if (ni == null) continue;
                byte[] hw = ni.getHardwareAddress();
                if (hw == null || hw.length < 6) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : hw) {
                    if (sb.length() > 0) sb.append('-');
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Throwable ignore) { }
        }
        return "";
    }

    /** 是否为 2xx/204 这类“未被劫持”的状态 */
    public static boolean isOnline() {
        String[] canaries = {
                "http://connect.rom.miui.com/generate_204",
                "http://www.gstatic.com/generate_204",
                "http://connectivitycheck.platform.hicloud.com/generate_204"
        };
        for (String url : canaries) {
            int code = httpStatus(url, 1500);
            if (code == 204 || code == 200) return true;
        }
        return false;
    }

    /** 只读状态行，不跟随跳转 */
    public static int httpStatus(String url, int timeoutMs) {
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String host = u.getHost();
            String path = u.getFile() == null || u.getFile().isEmpty() ? "/" : u.getFile();
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), timeoutMs);
                s.setSoTimeout(timeoutMs);
                String req = "GET " + path + " HTTP/1.1\r\nHost: " + host
                        + "\r\nConnection: close\r\nUser-Agent: okhttp/3.9.1\r\n\r\n";
                s.getOutputStream().write(req.getBytes("UTF-8"));
                s.getOutputStream().flush();
                InputStream in = s.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while (bos.size() < 8192 && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
                String head = new String(bos.toByteArray(), "UTF-8");
                int p = head.indexOf(' ');
                if (p > 0) {
                    int q = head.indexOf(' ', p + 1);
                    if (q > p) return Integer.parseInt(head.substring(p + 1, q).trim());
                }
            } finally {
                try { s.close(); } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
        return -1;
    }

    /** 读取一次跳转 Location，拿到 captive portal 参数 */
    public static PortalInfo probePortal() {
        String[] probes = {
                "http://connect.rom.miui.com/generate_204",
                "http://www.baidu.com/",
                "http://1.1.1.1/"
        };
        for (String url : probes) {
            PortalInfo pi = probeOne(url);
            if (pi != null) return pi;
        }
        return null;
    }

    private static PortalInfo probeOne(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String host = u.getHost();
            String path = u.getFile() == null || u.getFile().isEmpty() ? "/" : u.getFile();
            Socket s = new Socket();
            String head;
            try {
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setSoTimeout(3000);
                String req = "GET " + path + " HTTP/1.1\r\nHost: " + host
                        + "\r\nConnection: close\r\nUser-Agent: okhttp/3.9.1\r\n\r\n";
                s.getOutputStream().write(req.getBytes("UTF-8"));
                s.getOutputStream().flush();
                InputStream in = s.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while (bos.size() < 16384 && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
                head = new String(bos.toByteArray(), "UTF-8");
            } finally {
                try { s.close(); } catch (Throwable ignore) { }
            }
            String loc = null;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase().startsWith("location:")) {
                    loc = line.substring(9).trim();
                    break;
                }
            }
            if (loc == null) return null;
            PortalInfo pi = new PortalInfo();
            pi.location = loc;
            int q = loc.indexOf('?');
            pi.portalHost = "";
            pi.portalPort = 0;
            try {
                java.net.URL lu = new java.net.URL(loc);
                pi.portalHost = lu.getHost();
                pi.portalPort = lu.getPort() > 0 ? lu.getPort() : 80;
            } catch (Throwable ignore) { }
            if (q > 0) {
                for (String kv : loc.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e < 0) continue;
                    String k = kv.substring(0, e);
                    String v = kv.substring(e + 1);
                    if ("userip".equals(k)) pi.userIp = v;
                    else if ("nasip".equals(k)) pi.nasIp = v;
                    else if ("usermac".equals(k)) pi.userMac = v;
                    else if ("wlanacname".equals(k)) pi.wlanAcName = v;
                }
            }
            if (pi.portalHost == null || pi.portalHost.isEmpty()) return null;
            // 只有真正的 captive portal 跳转才可信：带认证参数，或端口不是 80/443 常规站点
            boolean looksPortal = (!pi.userIp.isEmpty() || !pi.nasIp.isEmpty() || !pi.userMac.isEmpty())
                    || pi.location.toLowerCase().contains("userip=");
            if (!looksPortal) return null;
            return pi;
        } catch (Throwable t) {
            return null;
        }
    }

    public static class PortalInfo {
        public String location = "";
        public String portalHost = "";
        public int portalPort = 0;
        public String userIp = "";
        public String userMac = "";
        public String nasIp = "";
        public String wlanAcName = "";
    }
}
