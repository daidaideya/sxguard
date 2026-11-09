package com.lcs.sx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

/**
 * 闪讯 (WeNet / Xinli Portal) REST 协议客户端
 * <p>
 * 服务器: X-PWS-Scheme 头里 apiuri + host + port
 * 鉴权:   X-Xinli-Auth: Digest nonce, response, client_id, signature(hmac-sha1), signature_method, timestamp, version
 * 关键坑: Content-Type 必须是 application/x-www-form-urlencoded，
 * 否则服务端不解析 body，签名串里的 entity 变成空串 -> 永远 401/109。
 */
public class SxProto {

    public static final String CLIENT_ID = "jportal";
    public static final String SECRET = "s3cr3t";
    public static final String APP_VERSION = "1.3.6";
    public static final String DEFAULT_MAC = "02-00-00-00-00-00";

    private final String host;
    private final int port;
    private final String user;
    private final String pass;

    /** 上一次成功登录返回的内容 */
    public String lastSessionId = "";
    public String lastClientToken = "";
    public String lastSessionToken = "";
    public String lastContext = "";
    public String lastError = "";
    public int lastCode = -1;

    public SxProto(String host, int port, String user, String pass) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
    }

    public String hostName() { return host; }

    public int portNum() { return port; }

    // ---------------------------------------------------------------- 协议动作

    /** 连接（登录）。成功返回 true */
    public boolean connect(String userIp, String nasIp, String wlanAcName, String userMac) throws Exception {
        Map<String, String> a = authorize(userIp);
        String nonce = a.get("nonce");
        String challenge = a.get("challenge");
        String redirect = "http://" + host + ":" + port + "?userip=" + userIp
                + "&wlanacname=" + wlanAcName + "&nasip=" + nasIp + "&usermac=" + userMac;

        Map<String, String> body = new TreeMap<>();
        body.put("username", user);
        body.put("password", pass);
        body.put("os", "android");
        body.put("version", APP_VERSION);
        body.put("user_ip", userIp);
        body.put("user_mac", DEFAULT_MAC);
        body.put("redirect_url", redirect);

        Map<String, String> digest = new LinkedHashMap<>();
        digest.put("nonce", nonce);
        digest.put("response", hmac(challenge));
        digest.put("client_id", CLIENT_ID);
        digest.put("signature_method", "HMAC-SHA1");
        digest.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        digest.put("version", "1.0");

        Resp r = call("POST", "/portal/v1.0/sessions", digest, body, null);
        lastCode = r.code;
        if (r.code == 200) {
            JSONObject auth = r.json.optJSONObject("authorization");
            JSONObject sess = r.json.optJSONObject("session");
            if (auth != null) lastClientToken = auth.optString("token", "");
            if (sess != null) {
                lastSessionId = sess.optString("id", "");
                lastSessionToken = sess.optString("token", "");
                lastContext = sess.optString("context", "");
            }
            lastError = "";
            return true;
        }
        lastError = r.text();
        return false;
    }

    /** 注销当前会话（需要 client_token） */
    public boolean disconnect() throws Exception {
        if (lastSessionId.isEmpty() || lastClientToken.isEmpty()) return false;
        Map<String, String> digest = baseDigest();
        digest.put("client_token", lastClientToken);
        digest.put("session_token", lastSessionToken);
        Resp r = call("DELETE", "/portal/v1.0/sessions/" + lastSessionId, digest, null, null);
        lastCode = r.code;
        if (r.code == 200) {
            lastSessionId = "";
            return true;
        }
        lastError = r.text();
        return false;
    }

    /** 查询当前会话；success 时填充 session 信息 */
    public boolean getSession() throws Exception {
        Map<String, String> digest = baseDigest();
        digest.put("client_token", lastClientToken);
        Resp r = call("GET", "/portal/v1.0/sessions", digest, null, null);
        lastCode = r.code;
        if (r.code == 200) {
            JSONObject sess = r.json.optJSONObject("session");
            JSONObject auth = r.json.optJSONObject("authorization");
            if (auth != null) lastClientToken = auth.optString("token", lastClientToken);
            if (sess != null) {
                lastSessionId = sess.optString("id", lastSessionId);
                lastContext = sess.optString("context", lastContext);
            }
            return true;
        }
        lastError = r.text();
        return false;
    }

    /** 用保存的 context 找回会话，成功会拿回新的 authorization token */
    public boolean findSession(String userIp, String context) throws Exception {
        if (context == null || context.isEmpty()) return false;
        Map<String, String> a = authorize(userIp);
        Map<String, String> digest = new LinkedHashMap<>();
        digest.put("nonce", a.get("nonce"));
        digest.put("response", hmac(a.get("challenge")));
        digest.put("client_id", CLIENT_ID);
        digest.put("signature_method", "HMAC-SHA1");
        digest.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        digest.put("version", "1.0");

        Map<String, String> body = new TreeMap<>();
        body.put("context", context);
        body.put("user_ip", userIp);
        body.put("user_mac", DEFAULT_MAC);

        Resp r = call("POST", "/portal/v1.0/sessions/find", digest, body, null);
        lastCode = r.code;
        if (r.code == 200) {
            JSONObject auth = r.json.optJSONObject("authorization");
            JSONObject sess = r.json.optJSONObject("session");
            if (auth != null) lastClientToken = auth.optString("token", "");
            if (sess != null) {
                lastSessionId = sess.optString("id", "");
                lastSessionToken = sess.optString("token", "");
                lastContext = sess.optString("context", context);
            }
            return true;
        }
        lastError = r.text();
        return false;
    }

    /** 探测 portal 是否可达，顺便拿 client 注册信息 */
    public boolean pingApi() {
        try {
            Resp r = call("GET", "/portal/api", null, null, null);
            return r.code == 200;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, String> baseDigest() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("client_id", CLIENT_ID);
        d.put("signature_method", "HMAC-SHA1");
        d.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        d.put("version", "1.0");
        return d;
    }

    private Map<String, String> authorize(String userIp) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "challenge");
        q.put("client_id", CLIENT_ID);
        q.put("scope", "portal-rest-api");
        q.put("require_token", "true");
        q.put("need_refresh_token", "false");
        q.put("user_ip", userIp);
        q.put("user_mac", DEFAULT_MAC);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        Resp r = call("GET", "/portal/v1.0/authorize?" + sb, null, null, null);
        if (r.code != 200) throw new IllegalStateException("authorize http " + r.code);
        JSONObject auth = r.json.getJSONObject("authentication");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("nonce", auth.getString("nonce"));
        out.put("challenge", auth.getString("challenge"));
        return out;
    }

    private static final String[] DIGEST_ORDER = {
            "nonce", "response", "client_token", "client_id",
            "signature", "signature_method", "timestamp", "session_token", "version"
    };

    private String digestString(Map<String, String> d, boolean withSignature) {
        StringBuilder sb = new StringBuilder("Digest ");
        boolean first = true;
        for (String k : DIGEST_ORDER) {
            if (!withSignature && "signature".equals(k)) continue;
            String v = d.get(k);
            if (v == null || v.isEmpty()) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(k).append("=\"").append(v).append('"');
        }
        return sb.toString();
    }

    private static String entityString(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private Resp call(String method, String path, Map<String, String> digest,
                      Map<String, String> params, String contentType) throws Exception {
        Map<String, String> d = digest == null ? new LinkedHashMap<String, String>() : digest;
        String entity = params == null ? "" : entityString(params);
        String target = "/portal/v1.0/authorize".equals(path) || path.startsWith("/portal/api")
                ? path : path;
        String msg = method + "&" + enc(target) + "&" + digestString(d, false) + "&" + entity;
        if (digest != null) {
            d.put("signature", hmac(msg));
        }

        final String CRLF = "" + (char) 13 + (char) 10;
        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        req.append("Connection: close\r\n");
        if (digest != null) {
            req.append("X-Xinli-Auth: ").append(digestString(d, true)).append("\r\n");
        }
        String body = params == null ? "" : entity;
        if (params == null && !"GET".equals(method)) {
            req.append("Content-Length: 0" + CRLF);
        }
        if (params != null) {
            req.append("Content-Type: ").append(
                    contentType == null ? "application/x-www-form-urlencoded" : contentType).append("\r\n");
            req.append("Content-Length: ").append(body.getBytes("UTF-8").length).append("\r\n");
        }
        req.append("Host: ").append(host).append(':').append(port).append("\r\n");
        req.append("User-Agent: okhttp/3.9.1\r\n");
        req.append("\r\n");
        if (params != null) req.append(body);

        String raw = rawRequest(req.toString());
        return Resp.parse(raw);
    }

    private String rawRequest(String req) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setSoTimeout(8000);
            s.getOutputStream().write(req.getBytes("UTF-8"));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try { s.close(); } catch (Throwable ignore) { }
        }
    }

    public static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(SECRET.getBytes("UTF-8"), "HmacSHA1"));
            byte[] out = mac.doFinal(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------------------- 响应封装

    public static class Resp {
        public int code;
        public String raw = "";
        public String body = "";
        public JSONObject json;

        public String text() {
            return code + " " + (body.length() > 220 ? body.substring(0, 220) : body);
        }

        static Resp parse(String raw) {
            Resp r = new Resp();
            r.raw = raw;
            if (raw == null || raw.isEmpty()) { r.code = -1; return r; }
            int p = raw.indexOf("\r\n");
            String status = p > 0 ? raw.substring(0, p) : raw;
            try {
                String[] sp = status.split(" ");
                r.code = Integer.parseInt(sp[1]);
            } catch (Throwable t) {
                r.code = -1;
            }
            int hdrEnd = raw.indexOf("\r\n\r\n");
            if (hdrEnd < 0) return r;
            String head = raw.substring(0, hdrEnd).toLowerCase();
            String body = raw.substring(hdrEnd + 4);
            if (head.contains("transfer-encoding: chunked")) {
                body = dechunk(body);
            }
            r.body = body;
            int i = body.indexOf('{');
            int j = body.lastIndexOf('}');
            if (i >= 0 && j > i) {
                try {
                    r.json = new JSONObject(body.substring(i, j + 1));
                } catch (Throwable ignore) { }
            }
            return r;
        }

        static String dechunk(String s) {
            StringBuilder out = new StringBuilder();
            int pos = 0;
            while (pos < s.length()) {
                int eol = s.indexOf("\r\n", pos);
                if (eol < 0) break;
                String hex = s.substring(pos, eol).trim();
                int size;
                try {
                    size = Integer.parseInt(hex, 16);
                } catch (Throwable t) {
                    break;
                }
                if (size <= 0) break;
                int start = eol + 2;
                int end = Math.min(start + size, s.length());
                out.append(s, start, end);
                pos = end + 2;
            }
            return out.toString();
        }
    }
}
