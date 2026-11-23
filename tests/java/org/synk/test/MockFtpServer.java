package org.synk.test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Embedded loopback RFC 959 FTP server for automated FtpBackend end-to-end testing.
 */
public final class MockFtpServer implements Closeable {

    private final File rootDir;
    private final String user;
    private final String pass;
    private final ServerSocket serverSocket;
    private volatile boolean running = true;

    public MockFtpServer(File rootDir, String user, String pass) throws IOException {
        this.rootDir = rootDir;
        this.user = user;
        this.pass = pass;
        if (!rootDir.exists()) rootDir.mkdirs();
        this.serverSocket = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket c = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handleClient(c); }
                        }).start();
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "mock-ftp");
        t.setDaemon(true);
        t.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private File resolvePath(String arg) {
        String p = arg == null ? "" : arg.trim();
        while (p.startsWith("/")) p = p.substring(1);
        return p.isEmpty() ? rootDir : new File(rootDir, p);
    }

    private void handleClient(Socket sock) {
        ServerSocket dataServer = null;
        try (Socket s = sock) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            reply(out, "220 SynkMockFTP Ready");

            String gotUser = "";
            String line;
            while ((line = in.readLine()) != null) {
                int sp = line.indexOf(' ');
                String cmd = (sp > 0 ? line.substring(0, sp) : line).toUpperCase(Locale.ROOT);
                String arg = sp > 0 ? line.substring(sp + 1) : "";

                if ("USER".equals(cmd)) {
                    gotUser = arg;
                    reply(out, "331 Password required");
                } else if ("PASS".equals(cmd)) {
                    if (user.equals(gotUser) && pass.equals(arg)) {
                        reply(out, "230 Logged in");
                    } else {
                        reply(out, "530 Login incorrect");
                    }
                } else if ("TYPE".equals(cmd) || "OPTS".equals(cmd)) {
                    reply(out, "200 OK");
                } else if ("PWD".equals(cmd)) {
                    reply(out, "257 \"/\" is current directory");
                } else if ("PASV".equals(cmd)) {
                    if (dataServer != null) try { dataServer.close(); } catch (Exception ignored) {}
                    dataServer = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                    int dp = dataServer.getLocalPort();
                    reply(out, "227 Entering Passive Mode (127,0,0,1," + (dp >> 8) + "," + (dp & 0xFF) + ")");
                } else if ("MLSD".equals(cmd)) {
                    File dir = resolvePath(arg);
                    if (!dir.isDirectory() || dataServer == null) {
                        reply(out, "550 Not a directory");
                    } else {
                        reply(out, "150 Opening data connection");
                        try (Socket ds = dataServer.accept();
                             BufferedWriter dw = new BufferedWriter(new OutputStreamWriter(ds.getOutputStream(), StandardCharsets.UTF_8))) {
                            File[] kids = dir.listFiles();
                            if (kids != null) {
                                for (File f : kids) {
                                    String type = f.isDirectory() ? "dir" : "file";
                                    dw.write("type=" + type + ";size=" + (f.isDirectory() ? 0 : f.length())
                                            + ";modify=" + ftpTime(f.lastModified()) + "; " + f.getName() + "\r\n");
                                }
                            }
                            dw.flush();
                        }
                        reply(out, "226 Transfer complete");
                    }
                } else if ("RETR".equals(cmd)) {
                    File f = resolvePath(arg);
                    if (!f.isFile() || dataServer == null) {
                        reply(out, "550 File not found");
                    } else {
                        reply(out, "150 Opening binary data connection");
                        try (Socket ds = dataServer.accept();
                             FileInputStream fis = new FileInputStream(f);
                             OutputStream dos = ds.getOutputStream()) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = fis.read(buf)) > 0) dos.write(buf, 0, n);
                            dos.flush();
                        }
                        reply(out, "226 Transfer complete");
                    }
                } else if ("STOR".equals(cmd)) {
                    File f = resolvePath(arg);
                    if (dataServer == null) {
                        reply(out, "425 Use PASV first");
                    } else {
                        File parent = f.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        reply(out, "150 Ready to receive");
                        try (Socket ds = dataServer.accept();
                             InputStream dis = ds.getInputStream();
                             FileOutputStream fos = new FileOutputStream(f)) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = dis.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                        reply(out, "226 Transfer complete");
                    }
                } else if ("MKD".equals(cmd)) {
                    File d = resolvePath(arg);
                    d.mkdirs();
                    reply(out, "257 \"" + arg + "\" created");
                } else if ("DELE".equals(cmd) || "RMD".equals(cmd)) {
                    File f = resolvePath(arg);
                    if (f.exists() && f.delete()) reply(out, "250 Deleted");
                    else reply(out, "550 Delete failed");
                } else if ("MDTM".equals(cmd)) {
                    File f = resolvePath(arg);
                    if (f.isFile()) reply(out, "213 " + ftpTime(f.lastModified()));
                    else reply(out, "550 Not found");
                } else if ("QUIT".equals(cmd)) {
                    reply(out, "221 Bye");
                    break;
                } else {
                    reply(out, "502 Command not implemented");
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (dataServer != null) try { dataServer.close(); } catch (Exception ignored) {}
        }
    }

    private static void reply(BufferedWriter out, String msg) throws IOException {
        out.write(msg);
        out.write("\r\n");
        out.flush();
    }

    private static String ftpTime(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
    }
}
