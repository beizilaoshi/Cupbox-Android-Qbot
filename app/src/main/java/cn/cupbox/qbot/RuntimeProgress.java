package cn.cupbox.qbot;

/** Immutable snapshot. Percentage only describes a measured file transfer, never install time. */
public final class RuntimeProgress {
  public final String label;
  public final long bytes, total;

  public RuntimeProgress(String label, long bytes, long total) {
    this.label = label;
    this.bytes = bytes;
    this.total = total;
  }

  public int percent() {
    return total > 0 ? (int) Math.min(100, bytes * 100 / total) : -1;
  }

  public String detail() {
    return total > 0
        ? String.format(
            java.util.Locale.ROOT,
            "%.1f / %.1f MB · %d%%",
            bytes / 1048576d,
            total / 1048576d,
            percent())
        : "此阶段耗时取决于设备与网络，请稍候";
  }
}
