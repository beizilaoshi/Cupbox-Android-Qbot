package cn.cupbox.qbot;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class NativeRuntimeTest {
  @Test
  public void bundledUbuntuNeedsNoDownloadAndReportsMeasuredProgress() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    java.util.concurrent.atomic.AtomicLong measured = new java.util.concurrent.atomic.AtomicLong();
    LocalRuntime runtime =
        new LocalRuntime(
            context,
            new LocalRuntime.Progress() {
              public void update(String stage) {}

              public void transfer(String label, long bytes, long total) {
                if (total > 0) measured.set(bytes);
              }
            });
    java.io.File directory = new java.io.File(context.getCacheDir(), "bundle-check");
    directory.mkdirs();
    java.io.File file = new java.io.File(directory, "ubuntu.tar.xz");
    try {
      boolean arm = LocalRuntime.architecture().equals("aarch64");
      runtime.fetch(
          "http://127.0.0.1:1/unreachable",
          arm
              ? "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"
              : "3a769bce23985effb504140d43b6ddd73dac9e261d1932894afa31de81e45414",
          file,
          80L * 1024 * 1024);
      assertTrue(file.length() > 60L * 1024 * 1024);
      assertEquals(file.length(), measured.get());
    } finally {
      runtime.stop();
      file.delete();
      directory.delete();
    }
  }

  @Test
  public void nativeProotCanExecute() throws Exception {
    LocalRuntime runtime =
        new LocalRuntime(InstrumentationRegistry.getInstrumentation().getTargetContext(), s -> {});
    try {
      String output = runtime.probe();
      assertTrue(output, output.toLowerCase(java.util.Locale.ROOT).contains("proot"));
    } finally {
      runtime.stop();
    }
  }
}
