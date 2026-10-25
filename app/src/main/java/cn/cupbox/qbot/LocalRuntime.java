package cn.cupbox.qbot;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** App-private PRoot userland; no root, adb, Termux installation or external server required. */
public final class LocalRuntime {
  public interface Progress {
    void update(String stage);

    default void transfer(String label, long bytes, long total) {
      update(label);
    }
  }

  private final Context context;
  public final File home, root, bin, tmp;
  private final Progress progress;
  private volatile Process process;
  private volatile File processPid;
  private volatile boolean cancelled;
  private volatile Call download;
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(25, TimeUnit.SECONDS)
          .readTimeout(90, TimeUnit.SECONDS)
          .build();

  public LocalRuntime(Context c, Progress listener) throws Exception {
    context = c.getApplicationContext();
    progress = listener;
    home = new File(c.getFilesDir(), "qq-runtime");
    root = new File(home, "ubuntu");
    bin = new File(home, "bin");
    tmp = new File(home, "tmp");
    if (!home.isDirectory() && !home.mkdirs()
        || !bin.isDirectory() && !bin.mkdirs()
        || !tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("无法创建运行目录");
    setupLinks();
  }

  public static String architecture() {
    for (String abi : Build.SUPPORTED_ABIS) {
      if (abi.equals("arm64-v8a")) return "aarch64";
      if (abi.equals("x86_64")) return "x86_64";
    }
    throw new IllegalStateException("本机登录需要 64 位 ARM 或 x86 Android");
  }

  private void setupLinks() throws Exception {
    String nativeDir = context.getApplicationInfo().nativeLibraryDir;
    for (String[] p :
        new String[][] {
          {"proot", "libproot.so"},
          {"loader", "libloader.so"},
          {"busybox", "libbusybox.so"},
          {"libtalloc.so.2", "libtalloc.so"},
          {"libbusybox.so.1", "libbusybox-core.so"},
          {"libbusybox.so.1.38.0", "libbusybox-core.so"}
        }) {
      File file = new File(bin, p[0]);
      if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath()))
        java.nio.file.Files.delete(file.toPath());
      Os.symlink(nativeDir + "/" + p[1], file.getAbsolutePath());
    }
    for (String name : new String[] {"xz", "tar", "sh", "gzip"}) {
      File link = new File(bin, name);
      if (!link.exists())
        Os.symlink(new File(bin, "busybox").getAbsolutePath(), link.getAbsolutePath());
    }
  }

  public boolean installed() {
    return new File(root, "root/.cupbox-runtime-v2").isFile();
  }

  public String probe() throws Exception {
    return execute(List.of(new File(bin, "proot").toString(), "--version"), 30);
  }

  public void install() throws Exception {
    if (installed()) return;
    if (home.getUsableSpace() < 3L * 1024 * 1024 * 1024) throw new IOException("请至少预留 3 GB 可用空间");
    String arch = architecture();
    boolean arm = arch.equals("aarch64");
    File archive = new File(home, "ubuntu.tar.xz");
    progress.update("准备内置基础环境");
    fetch(
        "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-"
            + arch
            + "-pd-v4.18.0.tar.xz",
        arm
            ? "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"
            : "3a769bce23985effb504140d43b6ddd73dac9e261d1932894afa31de81e45414",
        archive,
        80L * 1024 * 1024);
    if (!new File(home, "rootfs.ready").isFile()) {
      progress.update("解压运行环境");
      if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建 Ubuntu 目录");
      execute(
          List.of(
              new File(bin, "busybox").toString(),
              "tar",
              "xJf",
              archive.toString(),
              "--strip-components=1",
              "-C",
              root.toString()),
          300);
      writeString(new File(home, "rootfs.ready").toPath(), "v4.18.0");
    }
    new File(root, "root").mkdirs();
    new File(root, "tmp").mkdirs();
    new File(root, "dev/shm").mkdirs();
    java.nio.file.Files.deleteIfExists(new File(root, "etc/resolv.conf").toPath());
    writeString(
        new File(root, "etc/resolv.conf").toPath(), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
    // Host Android /proc permissions differ; Linux tools only need a stable meminfo view.
    writeString(
        new File(home, "meminfo").toPath(),
        "MemTotal: 4194304 kB\n"
            + "MemFree: 2097152 kB\n"
            + "MemAvailable: 2097152 kB\n"
            + "SwapTotal: 0 kB\n"
            + "SwapFree: 0 kB\n");
    File payload = new File(root, "root/cupbox-payload");
    payload.mkdirs();
    progress.update("下载 QQ 与 NapCat");
    fetch(
        "https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.41/NapCat.Shell.zip",
        "6801f93e533846cea05f4f531874a0e84371ab2331c5585b57cd7e3b869cf96b",
        new File(payload, "napcat.zip"),
        50L * 1024 * 1024);
    fetch(
        "https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_3.2.34-53644_"
            + (arm ? "arm64" : "amd64")
            + ".deb",
        arm
            ? "181b895cb573548e396e54bb0548cca92ffdc1b1a4b018fa4551bb062385fdca"
            : "436c65e1dd28422e128a21cbddb5fe4e2e6c079d7f27fec2541e014d38cc336e",
        new File(payload, "qq.deb"),
        250L * 1024 * 1024);
    fetch(
        RuntimePins.LAUNCHER_URL,
        RuntimePins.LAUNCHER_SHA,
        new File(payload, "launcher.cpp"),
        1024 * 1024);
    copyAsset("runtime/install.sh", new File(root, "root/cupbox-install.sh"));
    copyAsset("runtime/start.sh", new File(root, "root/cupbox-start.sh"));
    progress.update("安装 QQ 依赖，首次可能需要数分钟");
    execute(guest("/bin/bash", "/root/cupbox-install.sh"), 1800);
    if (!installed()) throw new IOException("运行环境未完成安装");
    // Download cache can be recreated. Never remove QQ's profile or account credentials here.
    archive.delete();
    new File(payload, "qq.deb").delete();
    new File(payload, "napcat.zip").delete();
    progress.update("本机环境已就绪");
  }

  public void start(String botToken, String webToken, int botPort, int webPort) throws Exception {
    if (!installed()) throw new IOException("请先准备本机环境");
    File cfg = new File(root, "root/napcat/config");
    cfg.mkdirs();
    writeString(
        new File(cfg, "webui.json").toPath(),
        new JSONObject()
            .put("host", "127.0.0.1")
            .put("port", webPort)
            .put("token", webToken)
            .put("loginRate", 30)
            .toString());
    writeString(new File(cfg, "onebot11.json").toPath(), onebot(botToken, botPort).toString());
    // Keep account-specific settings consistent after a prior successful login.
    File[] saved =
        cfg.listFiles((dir, name) -> name.startsWith("onebot11_") && name.endsWith(".json"));
    if (saved != null)
      for (File f : saved) writeString(f.toPath(), onebot(botToken, botPort).toString());
    writeString(new File(cfg, "napcat.json").toPath(), "{\"fileLog\":false,\"consoleLog\":false}");
    copyAsset("runtime/start.sh", new File(root, "root/cupbox-start.sh"));
    execute(guest("/bin/bash", "/root/cupbox-start.sh"), 0);
  }

  public static JSONObject onebot(String token, int port) throws JSONException {
    JSONObject server =
        new JSONObject()
            .put("name", "cupbox-local")
            .put("enable", true)
            .put("host", "127.0.0.1")
            .put("port", port)
            .put("messagePostFormat", "array")
            .put("reportSelfMessage", false)
            .put("token", token)
            .put("heartInterval", 15000);
    return new JSONObject()
        .put(
            "network",
            new JSONObject()
                .put("httpServers", new JSONArray())
                .put("httpClients", new JSONArray())
                .put("websocketClients", new JSONArray())
                .put("websocketServers", new JSONArray().put(server)))
        .put("enableLocalFile2Url", false)
        .put("parseMultMsg", false);
  }

  public List<String> guest(String... command) {
    List<String> args =
        new ArrayList<>(
            List.of(
                new File(bin, "proot").toString(),
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                root.toString(),
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                tmp + ":/dev/shm",
                "-b",
                tmp + ":" + tmp,
                "-w",
                "/root"));
    File memory = new File(home, "meminfo");
    if (memory.isFile()) {
      args.add("-b");
      args.add(memory + ":/proc/meminfo");
    }
    args.addAll(
        List.of(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp"));
    args.addAll(List.of(command));
    return args;
  }

  private String execute(List<String> args, long seconds) throws Exception {
    if (cancelled) throw new InterruptedIOException("操作已取消");
    File pidFile = new File(tmp, "process-" + UUID.randomUUID() + ".pid");
    List<String> grouped =
        new ArrayList<>(
            List.of(
                "/system/bin/sh",
                "-c",
                "echo $$ > \"$1\"; shift; exec \"$@\"",
                "cupbox-process",
                pidFile.toString(),
                new File(bin, "busybox").toString(),
                "setsid"));
    grouped.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(grouped).directory(home).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.clear();
    env.put("PATH", bin + ":/system/bin");
    env.put("HOME", home.toString());
    env.put("TMPDIR", tmp.toString());
    env.put("PROOT_TMP_DIR", tmp.toString());
    env.put("PROOT_LOADER", new File(bin, "loader").toString());
    env.put("LD_LIBRARY_PATH", bin + ":" + context.getApplicationInfo().nativeLibraryDir);
    env.put("PROOT_NO_SECCOMP", "1");
    Process child = builder.start();
    process = child;
    processPid = pidFile;
    StringBuilder tail = new StringBuilder();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader r =
                  new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                  if (line.startsWith("CUPBOX_STAGE:")) progress.update(line.substring(13));
                  synchronized (tail) {
                    tail.append(line).append('\n');
                    if (tail.length() > 6000) tail.delete(0, tail.length() - 6000);
                  }
                }
              } catch (IOException ignored) {
              }
            },
            "cupbox-runtime-output");
    reader.start();
    boolean finished =
        seconds == 0 ? waitIndefinitely(child) : child.waitFor(seconds, TimeUnit.SECONDS);
    if (!finished) {
      terminate(child, pidFile);
      throw new IOException("运行步骤超时，可重试");
    }
    reader.join(2000);
    if (!cancelled) pidFile.delete();
    if (cancelled) throw new InterruptedIOException("操作已取消");
    if (child.exitValue() != 0)
      throw new IOException("运行步骤失败 (" + child.exitValue() + ")\n" + tail);
    return tail.toString();
  }

  private boolean waitIndefinitely(Process p) throws InterruptedException {
    p.waitFor();
    return true;
  }

  private void copyAsset(String name, File to) throws IOException {
    try (InputStream in = context.getAssets().open(name);
        OutputStream out = new FileOutputStream(to)) {
      byte[] buffer = new byte[32768];
      int size;
      while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
    }
  }

  public void fetch(String url, String expected, File output, long maxBytes) throws Exception {
    if (output.isFile() && sha(output).equals(expected)) return;
    if (cancelled) throw new InterruptedIOException("操作已取消");
    if (output.getName().equals("ubuntu.tar.xz")) {
      String asset = "runtime/bundles/ubuntu-" + architecture() + ".tar.xz";
      try (InputStream in = context.getAssets().open(asset)) {
        File part = new File(output + ".part");
        long total;
        try (android.content.res.AssetFileDescriptor descriptor =
            context.getAssets().openFd(asset)) {
          total = descriptor.getLength();
        }
        long count = 0, last = 0;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (OutputStream out = new FileOutputStream(part)) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = in.read(buffer)) != -1) {
            if (cancelled) throw new InterruptedIOException("操作已取消");
            count += n;
            if (count > maxBytes) throw new IOException("内置文件超出大小限制");
            out.write(buffer, 0, n);
            hash.update(buffer, 0, n);
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - last > 150) {
              progress.transfer("释放内置 Ubuntu 环境", count, total);
              last = now;
            }
          }
        }
        if (!hex(hash.digest()).equals(expected)) {
          part.delete();
          throw new IOException("内置环境校验失败");
        }
        java.nio.file.Files.move(
            part.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        progress.transfer("内置基础环境已校验", total, total);
        return;
      } catch (FileNotFoundException missing) {
        /* A slim build can use the same verified network source. */
      }
    }
    File part = new File(output + ".part");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    download = http.newCall(new Request.Builder().url(url).build());
    try (Response response = download.execute()) {
      if (!response.isSuccessful() || response.body() == null)
        throw new IOException("下载失败 HTTP " + response.code());
      long count = 0, lastReport = 0;
      long total = response.body().contentLength();
      String label =
          output.getName().startsWith("ubuntu")
              ? "Ubuntu 运行环境"
              : output.getName().equals("qq.deb") ? "QQ 登录组件" : "NapCat 组件";
      try (InputStream in = response.body().byteStream();
          OutputStream out = new FileOutputStream(part)) {
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) != -1) {
          if (cancelled) throw new InterruptedIOException("操作已取消");
          count += n;
          if (count > maxBytes) throw new IOException("下载文件超出大小限制");
          digest.update(buf, 0, n);
          out.write(buf, 0, n);
          long now = android.os.SystemClock.elapsedRealtime();
          if (now - lastReport > 2000) {
            progress.transfer("下载" + label, count, total);
            lastReport = now;
          }
        }
      }
    }
    if (!hex(digest.digest()).equals(expected)) {
      part.delete();
      throw new IOException("下载校验失败，请重试");
    }
    java.nio.file.Files.move(
        part.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  private static String sha(File f) throws Exception {
    MessageDigest m = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(f)) {
      byte[] b = new byte[32768];
      int n;
      while ((n = in.read(b)) != -1) m.update(b, 0, n);
    }
    return hex(m.digest());
  }

  private static void writeString(java.nio.file.Path path, String value) throws IOException {
    java.nio.file.Files.write(path, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public static String hex(byte[] bytes) {
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  public void stop() {
    cancelled = true;
    if (download != null) download.cancel();
    Process p = process;
    if (p != null) terminate(p, processPid);
    http.dispatcher().cancelAll();
  }

  private void signalGroup(File pidFile, int signal) {
    if (pidFile == null || !pidFile.isFile()) return;
    try {
      String value =
          new String(
                  java.nio.file.Files.readAllBytes(pidFile.toPath()),
                  java.nio.charset.StandardCharsets.UTF_8)
              .trim();
      int pid = Integer.parseInt(value);
      if (pid > 1) Os.kill(-pid, signal);
    } catch (Exception ignored) {
    }
  }

  private void terminate(Process child, File pidFile) {
    signalGroup(pidFile, OsConstants.SIGTERM);
    child.destroy();
    new Thread(
            () -> {
              try {
                child.waitFor(2, TimeUnit.SECONDS);
                Thread.sleep(500);
                signalGroup(pidFile, OsConstants.SIGKILL);
                if (child.isAlive()) child.destroyForcibly();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                signalGroup(pidFile, OsConstants.SIGKILL);
                child.destroyForcibly();
              } finally {
                if (pidFile != null) pidFile.delete();
              }
            },
            "cupbox-runtime-stop")
        .start();
  }
}
