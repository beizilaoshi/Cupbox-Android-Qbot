package cn.cupbox.qbot;

import static org.junit.Assert.*;

import android.content.*;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.*;
import org.junit.*;

/** Opt-in network/large-disk acceptance, deliberately excluded from the regular fast suite. */
public class LocalLoginDeviceTest {
  @Test
  public void bootstrapAndShowRealQr() throws Exception {
    Assume.assumeTrue(
        "true".equals(InstrumentationRegistry.getArguments().getString("fullRuntime")));
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    LocalRuntime runtime = new LocalRuntime(app, s -> {});
    File cache = new File(app.getExternalFilesDir(null), "runtime-cache");
    File payload = new File(runtime.root, "root/cupbox-payload");
    payload.mkdirs();
    if (!runtime.installed())
      for (String name : new String[] {"ubuntu.tar.xz", "qq.deb", "napcat.zip", "launcher.cpp"}) {
        File source = new File(cache, name);
        if (source.isFile())
          Files.copy(
              source.toPath(),
              new File(name.equals("ubuntu.tar.xz") ? runtime.home : payload, name).toPath(),
              StandardCopyOption.REPLACE_EXISTING);
      }
    app.startActivity(new Intent(app, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    app.startForegroundService(new Intent(app, RuntimeService.class));
    long end = System.currentTimeMillis() + 25 * 60 * 1000;
    String previous = "";
    while (System.currentTimeMillis() < end
        && RuntimeService.qr.isEmpty()
        && RuntimeService.error.isEmpty()) {
      String state = RuntimeService.state;
      if (!previous.equals(state)) {
        Bundle b = new Bundle();
        b.putString("stream", state + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, b);
        previous = state;
      }
      Thread.sleep(1000);
    }
    assertTrue(RuntimeService.error, RuntimeService.error.isEmpty());
    assertFalse("QQ 没有生成真实登录二维码：" + RuntimeService.state, RuntimeService.qr.isEmpty());
    app.stopService(new Intent(app, RuntimeService.class));
    long stoppedBy = System.currentTimeMillis() + 6000;
    while (RuntimeService.instance != null && System.currentTimeMillis() < stoppedBy)
      Thread.sleep(100);
    assertNull(RuntimeService.instance);
    Thread.sleep(3500);
    Process ps = new ProcessBuilder("/system/bin/ps", "-A", "-o", "UID,NAME").start();
    try (java.io.BufferedReader r =
        new java.io.BufferedReader(new java.io.InputStreamReader(ps.getInputStream()))) {
      String line;
      while ((line = r.readLine()) != null) {
        String[] values = line.trim().split("\\s+");
        if (values.length > 1 && values[0].equals(String.valueOf(android.os.Process.myUid())))
          assertFalse(
              "停止后仍有 QQ 子进程：" + line,
              java.util.Set.of("qq", "Xvfb", "proot").contains(values[values.length - 1]));
      }
    }
    app.startForegroundService(new Intent(app, RuntimeService.class));
    long restartBy = System.currentTimeMillis() + 60000;
    while (RuntimeService.qr.isEmpty()
        && RuntimeService.error.isEmpty()
        && System.currentTimeMillis() < restartBy) Thread.sleep(200);
    assertFalse("重启后没有登录二维码：" + RuntimeService.error, RuntimeService.qr.isEmpty());
    // Leave the login service running for visual QA; do not scan, log in, or send messages.
  }
}
