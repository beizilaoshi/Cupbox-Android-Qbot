package cn.cupbox.qbot;

import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** About, useful application properties, contact details and a manual GitHub update check. */
public final class AboutActivity extends AppCompatActivity {
  private int bg, surface, ink, muted, accent, raised;
  private boolean dark, busy;
  private LinearLayout body;
  private TextView updateStatus, updateDetail, lastCheck, fact;
  private ProgressBar progress;
  private MaterialButton check, cancel, release;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private UpdateChecker checker;
  private int generation;
  private long started;
  private String releaseUrl = AppInfo.GITHUB + "/releases";
  private final Runnable rotate =
      new Runnable() {
        public void run() {
          if (busy) {
            fact.setText(WaitFacts.at(SystemClock.elapsedRealtime() - started));
            main.postDelayed(this, 9000);
          }
        }
      };

  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  private void add(LinearLayout parent, View child, int gap) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(gap);
    parent.addView(child, p);
  }

  private void note(LinearLayout parent, String value) {
    add(parent, text(value, 13, muted), 10);
  }

  private MaterialButton button(String label, boolean primary, Runnable action) {
    MaterialButton b = new MaterialButton(this);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setCornerRadius(dp(16));
    b.setMinHeight(dp(48));
    b.setTextColor(primary ? (dark ? bg : Color.WHITE) : accent);
    b.setBackgroundTintList(ColorStateList.valueOf(primary ? accent : raised));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private LinearLayout card(String title) {
    LinearLayout c = column();
    c.setPadding(dp(20), dp(20), dp(20), dp(20));
    GradientDrawable d = new GradientDrawable();
    d.setColor(surface);
    d.setCornerRadius(dp(22));
    c.setBackground(d);
    TextView heading = text(title, 17, ink);
    heading.setTypeface(null, Typeface.BOLD);
    add(c, heading, 0);
    add(body, c, 18);
    return c;
  }

  private void property(LinearLayout c, String name, String value) {
    LinearLayout r = new LinearLayout(this);
    r.setGravity(Gravity.TOP);
    TextView label = text(name, 13, muted), content = text(value, 13, ink);
    content.setTextIsSelectable(true);
    r.addView(label, new LinearLayout.LayoutParams(dp(90), -2));
    r.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
    add(c, r, 13);
  }

  @Override
  public void onCreate(Bundle saved) {
    dark = getSharedPreferences("MainActivity", 0).getBoolean("dark", false);
    setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
    super.onCreate(saved);
    bg = Color.parseColor(dark ? "#141b17" : "#f5f6f2");
    surface = Color.parseColor(dark ? "#202a23" : "#ffffff");
    ink = Color.parseColor(dark ? "#e0e6da" : "#192019");
    muted = Color.parseColor(dark ? "#c2c9bb" : "#59635b");
    accent = Color.parseColor(dark ? "#accfa8" : "#46664b");
    raised = Color.parseColor(dark ? "#344c3b" : "#e4eddf");
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root = column();
    root.setBackgroundColor(bg);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          }
          return insets;
        });
    setContentView(root);
    LinearLayout toolbar = new LinearLayout(this);
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setPadding(dp(20), dp(8), dp(20), dp(8));
    toolbar.addView(button("‹ 返回", false, this::finish));
    TextView title = text("关于 Cupbox", 20, ink);
    title.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
    tp.leftMargin = dp(16);
    toolbar.addView(title, tp);
    root.addView(toolbar);
    ScrollView scroll = new ScrollView(this);
    body = column();
    body.setPadding(dp(22), dp(8), dp(22), dp(32));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout hero = column();
    hero.setGravity(Gravity.CENTER_HORIZONTAL);
    BrandView logo = new BrandView(this);
    hero.addView(logo, new LinearLayout.LayoutParams(dp(110), dp(110)));
    TextView name = text("Cupbox Android", 27, ink);
    name.setTypeface(null, Typeface.BOLD);
    hero.addView(name);
    TextView version = text("版本 " + AppInfo.version(this) + "  ·  QQ 机器人", 13, accent);
    version.setPadding(0, dp(10), 0, dp(6));
    hero.addView(version);
    hero.addView(text("把陪伴，装进口袋。", 14, muted));
    add(body, hero, 8);
    updates();
    LinearLayout changes = card(AppInfo.version(this) + " · 这次的新变化");
    note(changes, "修复：推理模型输出未闭合的思考段时，思考内容会被当成正文发到 QQ。");
    note(changes, "修复：暂停或忙碌期间被丢弃的消息也被计入「已触发」，统计偏高。");
    note(changes, "修复：回复成功后在主线程写会话记忆，长时间使用可能出现卡顿。");
    note(changes, "修复：切换人格、登录方式时重建界面，存在控件被销毁的闪退风险。");
    note(changes, "优化：群号与关键词支持「、」「；」和空格分隔，并增加长度上限提示。");
    note(changes, "优化：记忆数据库开启 WAL；运行记录支持一键复制；二维码等待阶段不再显示旧进度。");
    properties();
    LinearLayout capabilities = card("一只口袋里的 QQ 搭子");
    note(capabilities, "本机登录 · 内置 Ubuntu 基础环境与运行工具，自动配置端口和协议；准备完成后扫码确认。也可以连接已有的远程 OneBot。");
    note(capabilities, "聊天人格 · 内置温柔搭子与蓝色大肥鱼，支持自定义语气；鲸鱼娘提供原版与自由演绎两种模式。");
    note(capabilities, "回复规则 · 群白名单、黑名单、真实 @、关键词、私聊开关、冷却与频率限制。连接后先待命，由你手动开启回复。");
    note(capabilities, "会话记忆 · 按机器人账号与群聊 / 私聊隔离，只保存触发且成功投递的对话，可随时查看或清空。");
    LinearLayout guide = card("第一次使用，按这条路走");
    note(
        guide,
        "01  登录 QQ：准备环境 → 用 QQ 扫码 → 确认登录。\n\n"
            + "02  配置模型：填写兼容接口、API Key 与模型名称 → 测试 → 保存。\n\n"
            + "03  回复范围：选择允许回复的群或私聊，设置 @ / 关键词触发 → 保存。\n\n"
            + "04  回到首页：连接机器人 → 核对范围 → 开启自动回复。");
    note(guide, "首次准备仍需联网下载 QQ、NapCat 及系统依赖，至少预留 3 GB 空间。停止回复与停止本机 QQ 是两个独立操作；系统强制停止或重启后需手动恢复。");
    LinearLayout contact = card("联系与反馈");
    property(contact, "QQ 交流群", AppInfo.GROUP);
    note(contact, "交流使用经验、反馈问题。复制群号后，在 QQ 中搜索群聊加入。");
    add(contact, button("复制 QQ 群号", true, () -> copy("Cupbox QQ 交流群", AppInfo.GROUP)), 12);
    property(contact, "GitHub", AppInfo.REPOSITORY);
    add(contact, button("打开 GitHub 仓库", false, () -> open(AppInfo.GITHUB)), 12);
    add(contact, button("前往 Issues 反馈问题", false, () -> open(AppInfo.GITHUB + "/issues")), 4);
    note(contact, "反馈时可附上应用信息、复现步骤和错误截图。请遮住 API Key、登录二维码与私人聊天内容。");
    LinearLayout data = card("你的数据与权限");
    note(data, "模型配置与密钥通过 Android Keystore 加密保存在本机。会话历史保存在应用私有数据库中；开启记忆时，相关历史会随触发消息发送给你配置的模型服务。");
    note(data, "QQ 登录资料保存在应用私有环境内，清空聊天记忆不会退出 QQ。卸载或清除应用数据会删除配置、历史、登录资料和运行环境。");
    note(
        data,
        "联网用于模型对话、QQ 连接、初始化下载和手动检查更新；通知用于显示运行状态，运行期间的唤醒锁用于减少休眠中断。检查更新不携带模型密钥、QQ"
            + " 账号或聊天记录，不会自动下载安装包。");
    LinearLayout credits = card("开源、素材与致谢");
    note(credits, "延续 Cupbox 的原版 Logo 与柔和配色。Android 客户端使用原生界面，专注 QQ 机器人功能。");
    note(
        credits,
        "感谢 Android / AndroidX、Material Components、OkHttp、ZXing、Termux / PRoot、BusyBox、Ubuntu、Linux"
            + " QQ 与 NapCat 等项目。各组件与素材遵循各自许可，Cupbox Logo 的权利声明沿用原项目。");
    note(
        credits,
        "蓝色大肥鱼 / 鲸鱼娘的人设原文与 MIT 许可随应用保留。登录路线参考 AstrBot Android 与 Android DIY"
            + " Sandbox，客户端与安装管理由本项目独立实现。");
    add(credits, button("查看许可证与组件声明", false, this::licenses), 12);
    note(body, "Cupbox Android " + AppInfo.version(this) + "\n愿每一句话，都有恰到好处的回应。");
  }

  private void updates() {
    LinearLayout c = card("版本与更新");
    updateStatus = text("当前版本 " + AppInfo.version(this), 16, accent);
    updateStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    add(c, updateStatus, 12);
    updateDetail = text("手动检查此项目的正式发行版本，确认后再前往 GitHub 更新。", 13, muted);
    updateDetail.setTextIsSelectable(true);
    add(c, updateDetail, 10);
    lastCheck = text("", 11, muted);
    add(c, lastCheck, 10);
    showLastCheck();
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
    add(c, progress, 12);
    fact = text("", 12, muted);
    add(c, fact, 10);
    check = button("检查更新", true, this::checkUpdate);
    add(c, check, 12);
    cancel = button("取消检查", false, this::cancelUpdate);
    add(c, cancel, 4);
    release = button("前往 GitHub 发行页", false, () -> open(releaseUrl));
    add(c, release, 4);
    setBusy(false);
    note(c, "更新来源：" + AppInfo.REPOSITORY + "\n仅检查公开正式 Release；不将测试版或普通代码提交视为新版本。");
  }

  private void properties() {
    LinearLayout c = card("应用属性");
    property(c, "版本号", AppInfo.version(this));
    property(c, "构建号", String.valueOf(AppInfo.code(this)));
    property(c, "应用包名", getPackageName());
    property(c, "系统要求", "Android 8.0 及以上 · 64 位 ARM / x86");
    property(c, "屏幕方向", "竖屏");
    property(c, "当前系统", "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
    property(c, "设备架构", Build.SUPPORTED_ABIS[0]);
    property(c, "安装包大小", AppInfo.size(this));
    property(c, "目标 SDK", String.valueOf(getApplicationInfo().targetSdkVersion));
    property(c, "最近安装", date(AppInfo.installed(this).lastUpdateTime));
    property(
        c,
        "构建类型",
        (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            ? "调试版"
            : "签名发布版");
    boolean prepared =
        new File(getFilesDir(), "qq-runtime/ubuntu/root/.cupbox-runtime-v2").isFile();
    property(c, "本机环境", prepared ? "已完成准备" : "尚未完成准备");
    add(c, button("复制应用信息", false, () -> copy("Cupbox 应用信息", AppInfo.diagnostics(this))), 14);
  }

  private void setBusy(boolean value) {
    busy = value;
    check.setEnabled(!value);
    check.setText(value ? "正在检查…" : "检查更新");
    progress.setVisibility(value ? View.VISIBLE : View.GONE);
    fact.setVisibility(value ? View.VISIBLE : View.GONE);
    cancel.setVisibility(value ? View.VISIBLE : View.GONE);
    release.setEnabled(!value);
    main.removeCallbacks(rotate);
    if (value) {
      started = SystemClock.elapsedRealtime();
      main.post(rotate);
    }
  }

  private void checkUpdate() {
    if (busy) return;
    int request = ++generation;
    checker = new UpdateChecker();
    UpdateChecker running = checker;
    setBusy(true);
    updateStatus.setText("正在连接 GitHub");
    updateDetail.setText("正在读取正式发行版本，请稍候。最多等待约 40 秒，可随时取消。");
    worker.execute(
        () -> {
          try {
            UpdateChecker.Result result = running.check(AppInfo.version(this));
            main.post(
                () -> {
                  if (isDestroyed() || isFinishing() || request != generation) return;
                  setBusy(false);
                  releaseUrl = result.url();
                  String heading =
                      switch (result.state()) {
                        case NEWER -> "发现新版本 " + result.version();
                        case CURRENT -> "已是仓库最新正式版本";
                        case LOCAL_NEWER -> "当前安装版本领先于仓库";
                        case NO_RELEASE -> "仓库尚未发布正式版本";
                      };
                  updateStatus.setText(heading);
                  String message =
                      result.state() == UpdateChecker.State.NO_RELEASE
                          ? result.notes()
                          : "当前 "
                              + AppInfo.version(this)
                              + " · 仓库 "
                              + result.version()
                              + "\n\n"
                              + result.title()
                              + "\n\n"
                              + result.notes();
                  if (result.state() == UpdateChecker.State.NEWER
                      && result.detailsAvailable()
                      && !result.hasApk()) message += "\n\n此 Release 尚未附带 APK，请在发行页确认下载安排。";
                  updateDetail.setText(message);
                  release.setText(
                      result.state() == UpdateChecker.State.NEWER ? "前往 GitHub 查看更新" : "查看仓库发行页");
                  getPreferences(0)
                      .edit()
                      .putLong("checkedAt", System.currentTimeMillis())
                      .putString("lastResult", heading)
                      .apply();
                  showLastCheck();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (isDestroyed() || isFinishing() || request != generation) return;
                  setBusy(false);
                  updateStatus.setText("检查未完成");
                  updateDetail.setText(
                      e instanceof IOException
                              && e.getMessage() != null
                              && e.getMessage().matches("(?s).*[\u4e00-\u9fff].*")
                          ? e.getMessage()
                          : "网络连接失败或超时，请检查网络后重试，也可直接打开 GitHub 发行页。");
                });
          }
        });
  }

  private void cancelUpdate() {
    generation++;
    if (checker != null) checker.cancel();
    setBusy(false);
    updateStatus.setText("已取消检查");
    updateDetail.setText("需要时可重新检查，也可直接前往 GitHub 发行页。");
  }

  private void showLastCheck() {
    long at = getPreferences(0).getLong("checkedAt", 0);
    lastCheck.setText(
        at == 0
            ? "尚未检查 · 仅在点击时联网"
            : "上次成功检查：" + date(at) + "\n" + getPreferences(0).getString("lastResult", ""));
  }

  private String date(long at) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(at));
  }

  private void copy(String label, String value) {
    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, value));
    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
  }

  private void open(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      new MaterialAlertDialogBuilder(this)
          .setTitle("没有可用的浏览器")
          .setMessage("可以复制链接，在其他设备或浏览器中打开。")
          .setNegativeButton("关闭", null)
          .setPositiveButton("复制链接", (d, w) -> copy("Cupbox 链接", url))
          .show();
    }
  }

  private void licenses() {
    String[] names = {
      "Cupbox 许可与素材声明", "组件与来源声明", "Ubuntu 基础环境", "GNU GPL 2.0", "GNU GPL 3.0", "鲸鱼娘人设 MIT"
    };
    String[] files = {
      "licenses/Cupbox-LICENSE.txt",
      "licenses/NOTICES.txt",
      "licenses/UBUNTU-BASE.txt",
      "licenses/GPL-2.0.txt",
      "licenses/GPL-3.0.txt",
      "whale/LICENSE"
    };
    new MaterialAlertDialogBuilder(this)
        .setTitle("许可证与组件声明")
        .setItems(
            names,
            (d, pos) -> {
              try (InputStream stream = getAssets().open(files[pos]);
                  ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
                TextView content = text(bytes.toString("UTF-8"), 12, ink);
                content.setTextIsSelectable(true);
                content.setPadding(dp(22), dp(16), dp(22), dp(16));
                ScrollView scroll = new ScrollView(this);
                scroll.addView(content);
                new MaterialAlertDialogBuilder(this)
                    .setTitle(names[pos])
                    .setView(scroll)
                    .setPositiveButton("关闭", null)
                    .show();
              } catch (IOException e) {
                Toast.makeText(this, "无法读取声明，请查看项目仓库。", Toast.LENGTH_LONG).show();
              }
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  @Override
  public void onDestroy() {
    generation++;
    if (checker != null) checker.cancel();
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    super.onDestroy();
  }
}
