package cn.cupbox.qbot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.File;

final class AppInfo {
  static final String REPOSITORY = "beizilaoshi/Cupbox-Android-Qbot";
  static final String GITHUB = "https://github.com/" + REPOSITORY;
  static final String GROUP = "638500178";

  static PackageInfo installed(Context context) {
    try {
      return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  static String version(Context context) {
    return installed(context).versionName;
  }

  static long code(Context context) {
    PackageInfo info = installed(context);
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  static String size(Context context) {
    long bytes = new File(context.getApplicationInfo().sourceDir).length();
    String[] splits = context.getApplicationInfo().splitSourceDirs;
    if (splits != null) for (String split : splits) bytes += new File(split).length();
    return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576d);
  }

  static String diagnostics(Context context) {
    return "Cupbox Android "
        + version(context)
        + " ("
        + code(context)
        + ")\n包名："
        + context.getPackageName()
        + "\n系统：Android "
        + Build.VERSION.RELEASE
        + " / API "
        + Build.VERSION.SDK_INT
        + "\n架构："
        + Build.SUPPORTED_ABIS[0]
        + "\n安装包："
        + size(context)
        + "\n项目："
        + GITHUB;
  }
}
