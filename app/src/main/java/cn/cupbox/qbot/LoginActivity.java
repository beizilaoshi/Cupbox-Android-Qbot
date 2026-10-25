package cn.cupbox.qbot;

import android.Manifest;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** State-driven login: idle -> preparing -> QR -> confirmed. */
public final class LoginActivity extends AppCompatActivity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView status, detail, fact, measured, steps, elapsed;
  private LinearLayout qrCard, waitCard;
  private ImageView qr;
  private MaterialButton primary, secondary, stop;
  private ProgressBar progress;
  private ProgressBar qrWait;
  private String last = "";
  private Bitmap current;
  private boolean dark;
  private int bg, ink, muted, accent, surface;
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          update();
          handler.postDelayed(this, 1500);
        }
      };

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density + .5f);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private TextView text(String v, int size, int color) {
    TextView t = new TextView(this);
    t.setText(v);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  private void add(LinearLayout p, View v, int margin) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(margin);
    p.addView(v, lp);
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout c = column();
    c.setPadding(dp(22), dp(22), dp(22), dp(22));
    GradientDrawable d = new GradientDrawable();
    d.setColor(surface);
    d.setCornerRadius(dp(24));
    c.setBackground(d);
    add(parent, c, 18);
    return c;
  }

  private MaterialButton button(String label, boolean filled, Runnable action) {
    MaterialButton b = new MaterialButton(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setCornerRadius(dp(16));
    b.setMinHeight(dp(52));
    b.setTextColor(filled ? (dark ? bg : Color.WHITE) : accent);
    b.setBackgroundTintList(
        ColorStateList.valueOf(filled ? accent : (dark ? 0xff344c3b : 0xffe4eddf)));
    b.setOnClickListener(v -> action.run());
    return b;
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
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(bg);
    LinearLayout body = column();
    body.setPadding(dp(24), dp(16), dp(24), dp(30));
    scroll.addView(body);
    setContentView(scroll);
    body.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(
                dp(24) + bars.left, dp(16) + bars.top, dp(24) + bars.right, dp(30) + bars.bottom);
          }
          return insets;
        });
    add(body, button("‹ 返回首页", false, this::finish), 0);
    TextView title = text("把 QQ 搬进口袋", 29, ink);
    title.setTypeface(null, Typeface.BOLD);
    add(body, title, 24);
    add(body, text("一次准备，以后直接登录。", 14, muted), 8);
    LinearLayout state = card(body);
    steps = text("01 准备环境 → 02 启动 QQ → 03 扫码", 11, accent);
    add(state, steps, 0);
    status = text("准备好你的 QQ 搭子", 20, ink);
    status.setTypeface(null, Typeface.BOLD);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    add(state, status, 18);
    detail = text("", 14, muted);
    detail.setTextIsSelectable(true);
    add(state, detail, 12);
    waitCard = column();
    add(state, waitCard, 14);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(ColorStateList.valueOf(accent));
    progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
    add(waitCard, progress, 0);
    measured = text("", 12, muted);
    add(waitCard, measured, 8);
    elapsed = text("", 12, muted);
    add(waitCard, elapsed, 8);
    qrCard = card(body);
    qr = new ImageView(this);
    qr.setContentDescription("QQ 登录二维码");
    qr.setBackgroundColor(Color.WHITE);
    qr.setAdjustViewBounds(true);
    qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
    qrCard.addView(qr, new LinearLayout.LayoutParams(-1, dp(240)));
    qrWait = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    qrWait.setIndeterminate(true);
    qrWait.setIndeterminateTintList(ColorStateList.valueOf(accent));
    add(qrCard, qrWait, 8);
    add(qrCard, text("用 QQ 扫码，再在 QQ 中确认登录", 12, muted), 12);
    primary = button("准备并启动本机 QQ", true, this::start);
    add(body, primary, 20);
    secondary =
        button(
            "刷新二维码",
            false,
            () -> {
              if (RuntimeService.instance != null) {
                secondary.setEnabled(false);
                RuntimeService.instance.refreshQr();
              }
            });
    add(body, secondary, 6);
    stop =
        button(
            "停止本机 QQ",
            false,
            () -> {
              stop.setEnabled(false);
              stopService(new Intent(this, RuntimeService.class));
            });
    add(body, stop, 6);
    LinearLayout trivia = card(body);
    add(trivia, text("等一下，也长点知识", 12, accent), 0);
    fact = text(WaitFacts.at(0), 14, ink);
    add(trivia, fact, 10);
    update();
  }

  private void update() {
    boolean running = RuntimeService.instance != null, logged = RuntimeService.loggedIn;
    String value = RuntimeService.qr;
    boolean hasQr = !value.isEmpty() && !logged, waiting = running && !hasQr && !logged;
    waitCard.setVisibility(waiting ? View.VISIBLE : View.GONE);
    qrCard.setVisibility(hasQr ? View.VISIBLE : View.GONE);
    status.setText(
        logged
            ? "QQ 已登录 · " + RuntimeService.account
            : running
                ? RuntimeService.state
                : RuntimeService.error.isEmpty() ? "准备好你的 QQ 搭子" : "准备遇到问题");
    detail.setText(
        !RuntimeService.error.isEmpty()
            ? RuntimeService.error
            : logged
                ? "登录完成！返回首页配置模型和回复范围，再开启机器人。"
                : hasQr
                    ? "切到 QQ 扫码时，本机服务会继续运行。请勿分享登录二维码。"
                    : running
                        ? "可返回首页先配置模型。完成的环境会保留，重试时会复用。"
                        : "基础 Ubuntu 环境已内置。首次还需联网下载 QQ、NapCat 及系统依赖，请预留至少 3 GB 空间。");
    steps.setText(
        logged
            ? "✓ 环境就绪    ✓ QQ 已启动    ✓ 登录完成"
            : hasQr ? "✓ 环境就绪    ✓ QQ 已启动    03 扫码确认" : "01 准备环境 → 02 启动 QQ → 03 扫码");
    RuntimeProgress p = RuntimeService.progress;
    progress.setIndeterminate(p.percent() < 0);
    if (p.percent() >= 0) progress.setProgress(p.percent());
    measured.setText(p.detail());
    long duration = running ? SystemClock.elapsedRealtime() - RuntimeService.startedAt : 0;
    elapsed.setText("已等待 " + duration / 60000 + " 分 " + duration / 1000 % 60 + " 秒 · 可随时停止");
    fact.setText(WaitFacts.at(duration));
    ((View) fact.getParent())
        .setVisibility(waiting || RuntimeService.refreshingQr ? View.VISIBLE : View.GONE);
    qrWait.setVisibility(RuntimeService.refreshingQr ? View.VISIBLE : View.GONE);
    primary.setText(
        logged
            ? "登录完成 · 返回首页"
            : running ? "返回首页，先配置模型" : RuntimeService.error.isEmpty() ? "准备并启动本机 QQ" : "重试准备环境");
    primary.setEnabled(true);
    secondary.setVisibility(hasQr ? View.VISIBLE : View.GONE);
    secondary.setText(RuntimeService.refreshingQr ? "正在刷新二维码…" : "刷新二维码");
    secondary.setEnabled(hasQr && !RuntimeService.refreshingQr);
    stop.setVisibility(running ? View.VISIBLE : View.GONE);
    stop.setEnabled(running);
    if (!last.equals(value)) {
      last = value;
      qr.setImageDrawable(null);
      if (current != null) {
        current.recycle();
        current = null;
      }
      if (hasQr)
        try {
          BitMatrix bits = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640);
          int[] pixels = new int[640 * 640];
          for (int y = 0; y < 640; y++)
            for (int x = 0; x < 640; x++)
              pixels[y * 640 + x] = bits.get(x, y) ? Color.BLACK : Color.WHITE;
          current = Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888);
          qr.setImageBitmap(current);
        } catch (Exception e) {
          detail.setText("二维码生成失败，请刷新重试。");
        }
    }
  }

  private void start() {
    if (RuntimeService.loggedIn || RuntimeService.instance != null) {
      finish();
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 8);
    startForegroundService(new Intent(this, RuntimeService.class));
    primary.setEnabled(false);
    handler.postDelayed(this::update, 200);
  }

  @Override
  public void onResume() {
    super.onResume();
    handler.post(refresh);
  }

  @Override
  public void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (current != null) {
      qr.setImageDrawable(null);
      current.recycle();
    }
    super.onDestroy();
  }
}
