package com.gathin.tvhome;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RtspAuthProxy {

    private static final List<ProxyThread> activeProxies = new ArrayList<>();

    public static synchronized void ensureRunning() {
        startAll();
    }

    public static synchronized void startAll() {
        boolean needRestart = false;
        if (activeProxies.isEmpty()) {
            needRestart = true;
        } else {
            for (ProxyThread p : activeProxies) {
                if (!p.isAlive() || !p.isOpen()) {
                    needRestart = true;
                    break;
                }
            }
        }
        if (!needRestart) {
            return;
        }
        stopAll();
        // 4 cameras mapped to 8554 - 8557
        activeProxies.add(new ProxyThread(8554, "192.168.1.9", "olavoh18", "Olavo@123"));
        activeProxies.add(new ProxyThread(8555, "192.168.1.10", "olavoh18", "Olavo@123"));
        activeProxies.add(new ProxyThread(8556, "192.168.1.11", "olavoh", "Olavo@123"));
        activeProxies.add(new ProxyThread(8557, "192.168.1.12", "olavoh", "Olavo@123"));
        for (ProxyThread p : activeProxies) {
            p.start();
        }
    }

    public static synchronized void stopAll() {
        for (ProxyThread p : activeProxies) {
            p.shutdown();
        }
        activeProxies.clear();
    }

    private static String md5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static class ProxyThread extends Thread {
        private final int localPort;
        private final String targetIp;
        private final String user;
        private final String pwd;
        private ServerSocket serverSocket;
        private volatile boolean running = true;

        ProxyThread(int localPort, String targetIp, String user, String pwd) {
            this.localPort = localPort;
            this.targetIp = targetIp;
            this.user = user;
            this.pwd = pwd;
        }

        boolean isOpen() {
            return running && serverSocket != null && !serverSocket.isClosed();
        }

        void shutdown() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(localPort);
                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleConnection(clientSocket)).start();
                }
            } catch (Exception ignored) {
            } finally {
                shutdown();
            }
        }

        private void handleConnection(Socket clientSocket) {
            Socket targetSocket = null;
            try {
                targetSocket = new Socket(targetIp, 554);
                final Socket finalTarget = targetSocket;
                final String[] authInfo = new String[2]; // 0: realm, 1: nonce

                // Target to Client Thread
                Thread t2c = new Thread(() -> {
                    try {
                        InputStream in = finalTarget.getInputStream();
                        OutputStream out = clientSocket.getOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (n > 0) {
                                String s = new String(buf, 0, Math.min(n, 1024), StandardCharsets.ISO_8859_1);
                                if (s.contains("401 Unauthorized")) {
                                    Matcher mr = Pattern.compile("realm=\"([^\"]+)\"").matcher(s);
                                    Matcher mn = Pattern.compile("nonce=\"([^\"]+)\"").matcher(s);
                                    if (mr.find() && mn.find()) {
                                        authInfo[0] = mr.group(1);
                                        authInfo[1] = mn.group(1);
                                    }
                                }
                                out.write(buf, 0, n);
                                out.flush();
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try { clientSocket.close(); } catch (Exception ignored) {}
                        try { finalTarget.close(); } catch (Exception ignored) {}
                    }
                });
                t2c.setDaemon(true);
                t2c.start();

                // Client to Target
                InputStream in = clientSocket.getInputStream();
                OutputStream out = targetSocket.getOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (n > 0) {
                        if (buf[0] != '$') { // Not an interleaved binary RTP packet
                            String text = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                            if (text.startsWith("OPTIONS") || text.startsWith("DESCRIBE")
                                    || text.startsWith("SETUP") || text.startsWith("PLAY")
                                    || text.startsWith("TEARDOWN")) {
                                // Rewrite localhost to target IP
                                text = text.replaceAll("127\\.0\\.0\\.1:\\d+", targetIp + ":554");

                                if (text.contains("Authorization: Digest") && authInfo[0] != null && authInfo[1] != null) {
                                    String realm = authInfo[0];
                                    String nonce = authInfo[1];
                                    Matcher mReq = Pattern.compile("^([A-Z]+)\\s+([^\\s]+)\\s+RTSP/1\\.0", Pattern.MULTILINE).matcher(text);
                                    if (mReq.find()) {
                                        String method = mReq.group(1);
                                        String targetUri = mReq.group(2);
                                        String ha1 = md5(user + ":" + realm + ":" + pwd);
                                        String ha2 = md5(method + ":" + targetUri);
                                        String correctResp = md5(ha1 + ":" + nonce + ":" + ha2);

                                        text = text.replaceAll("nonce=\"[^\"]+\"", "nonce=\"" + nonce + "\"");
                                        text = text.replaceAll("response=\"[^\"]+\"", "response=\"" + correctResp + "\"");
                                        android.util.Log.d("RtspProxy", "Rewrote " + method + " response for " + targetIp + " -> " + correctResp);
                                    }
                                }
                                byte[] rewritten = text.getBytes(StandardCharsets.ISO_8859_1);
                                out.write(rewritten);
                                out.flush();
                                continue;
                            }
                        }
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { clientSocket.close(); } catch (Exception ignored) {}
                if (targetSocket != null) {
                    try { targetSocket.close(); } catch (Exception ignored) {}
                }
            }
        }
    }
}

