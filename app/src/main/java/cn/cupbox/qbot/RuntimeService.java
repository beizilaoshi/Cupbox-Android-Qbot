package cn.cupbox.qbot;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

public final class RuntimeService extends Service {
  public static final String STOP = "cn.cupbox.qbot.RUNTIME_STOP";
  public static volatile RuntimeService instance;
  public static volatile String state = "尚未准备本机环境", qr = "", account = "", error = "";
  public static volatile boolean loggedIn = false;
  public static volatile boolean refreshingQr = false;
  public static volatile RuntimeProgress progress = new RuntimeProgress("等待开始", 0, -1);
  public static volatile long startedAt;
  public final String botToken = UUID.randomUUID().toString() + UUID.randomUUID();
  public final int botPort = availablePort(), webPort = availablePort();

  private static int availablePort() {
    try (java.net.ServerSocket socket =
        new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("无法分配本机端口", e);
    }
  }

  private final String webToken = UUID.randomUUID().toString() + UUID.randomUUID();
  private String credential = "";
  private volatile boolean stopped = false;
  private boolean started = false, quickTried = false;
  private LocalRuntime runtime;
  private PowerManager.WakeLock wakeLock;
  private final Runnable renewWake =
      new Runnable() {
        public void run() {
          if (!stopped && wakeLock != null) {
            wakeLock.acquire(10 * 60 * 1000L);
            main.postDelayed(this, 5 * 60 * 1000L);
          }
        }
      };
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor();
  private final OkHttpClient http =
      new OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).followRedirects(false).build();

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    qr = "";
    error = "";
    account = "";
    loggedIn = false;
    refreshingQr = false;
    startedAt = SystemClock.elapsedRealtime();
    progress = new RuntimeProgress("检查本机环境", 0, -1);
    wakeLock =
        getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cupbox:QQRuntime");
    wakeLock.setReferenceCounted(false);
    main.post(renewWake);
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel("runtime", "本机 QQ 登录", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    showNotification(false);
    if (started) return START_NOT_STICKY;
    started = true;
    worker.execute(
        () -> {
          try {
            runtime =
                new LocalRuntime(
                    this,
                    new LocalRuntime.Progress() {
                      public void update(String s) {
                        state = s;
                        progress = new RuntimeProgress(s, 0, -1);
                        main.post(() -> showNotification(false));
                      }

                      public void transfer(String s, long bytes, long total) {
                        state = s;
                        progress = new RuntimeProgress(s, bytes, total);
                        main.post(() -> showNotification(false));
                      }
                    });
            runtime.install();
            if (stopped) return;
            state = "正在启动本机 QQ";
            progress = new RuntimeProgress(state, 0, -1);
            main.post(() -> showNotification(true));
            polling.scheduleWithFixedDelay(this::poll, 2, 2, TimeUnit.SECONDS);
            runtime.start(botToken, webToken, botPort, webPort);
            if (!stopped) throw new java.io.IOException("QQ 进程已退出");
          } catch (Exception e) {
            if (!stopped) {
              error =
                  (e.getMessage() == null ? "未知错误" : e.getMessage())
                      .replace(botToken, "[hidden]")
                      .replace(webToken, "[hidden]");
              state = "本机运行失败，可重试";
              main.post(this::stopSelf);
            }
          }
        });
    return START_NOT_STICKY;
  }

  private void showNotification(boolean messaging) {
    if (stopped) return;
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            10,
            new Intent(this, LoginActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            11,
            new Intent(this, RuntimeService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE);
    Notification n =
        new Notification.Builder(this, "runtime")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Cupbox · 本机 QQ")
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止本机 QQ", stop).build())
            .build();
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(
          2,
          n,
          messaging
              ? ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
              : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(2, n);
  }

  private JSONObject api(String route, JSONObject body) throws Exception {
    Request.Builder b =
        new Request.Builder()
            .url("http://127.0.0.1:" + webPort + "/api/" + route)
            .post(RequestBody.create(body.toString(), MediaType.get("application/json")));
    if (!credential.isEmpty()) b.header("Authorization", "Bearer " + credential);
    try (Response r = http.newCall(b.build()).execute()) {
      if (r.body() == null || r.body().contentLength() > 128 * 1024)
        throw new java.io.IOException("登录接口异常");
      String text = r.body().string();
      if (text.length() > 128 * 1024) throw new java.io.IOException("登录响应过大");
      JSONObject j = new JSONObject(text);
      if (!r.isSuccessful() || j.optInt("code", -1) != 0) {
        credential = "";
        throw new java.io.IOException("等待登录接口就绪");
      }
      JSONObject data = j.optJSONObject("data");
      return data == null ? new JSONObject().put("value", j.opt("data")) : data;
    }
  }

  private void poll() {
    if (stopped) return;
    try {
      if (credential.isEmpty()) {
        String hash =
            LocalRuntime.hex(
                MessageDigest.getInstance("SHA-256")
                    .digest((webToken + ".napcat").getBytes(StandardCharsets.UTF_8)));
        JSONObject login = api("auth/login", new JSONObject().put("hash", hash));
        credential = login.optString("Credential");
        if (credential.isEmpty()) return;
      }
      JSONObject status = api("QQLogin/CheckLoginStatus", new JSONObject());
      boolean was = loggedIn;
      loggedIn = status.optBoolean("isLogin");
      if (loggedIn) {
        if (!was)
          api(
              "OB11Config/SetConfig",
              new JSONObject().put("config", LocalRuntime.onebot(botToken, botPort).toString()));
        qr = "";
        state = "QQ 已登录";
        JSONObject info = api("QQLogin/GetQQLoginInfo", new JSONObject());
        account = info.optString("uin", info.optString("user_id", ""));
        if (account.matches("[0-9]{4,20}"))
          getSharedPreferences("runtime", 0).edit().putString("account", account).apply();
      } else {
        if (was) {
          main.post(this::stopLocalBot);
        }
        String value = status.optString("qrcodeurl", "");
        qr = value.length() < 10000 ? value : "";
        state =
            status.optBoolean("isOffline")
                ? "QQ 已离线，请重新扫码"
                : status.optString("loginPhase").equals("qrcode_scanned")
                    ? "已扫码，请在 QQ 确认"
                    : "请使用 QQ 扫码登录";
        if (!quickTried) {
          String remembered = getSharedPreferences("runtime", 0).getString("account", "");
          quickTried = true;
          if (!remembered.isEmpty())
            try {
              api("QQLogin/SetQuickLogin", new JSONObject().put("uin", remembered));
              state = "尝试恢复已登录账号";
            } catch (Exception ignored) {
            }
        }
      }
      main.post(() -> showNotification(true));
    } catch (Exception ignored) {
      if (!stopped && loggedIn) {
        loggedIn = false;
        qr = "";
        state = "登录接口暂时不可用";
        main.post(this::stopLocalBot);
      }
    }
  }

  public void refreshQr() {
    if (stopped || refreshingQr) return;
    refreshingQr = true;
    try {
      polling.execute(
          () -> {
            try {
              api("QQLogin/RefreshQRcode", new JSONObject());
            } catch (Exception ignored) {
            }
            try {
              poll();
            } finally {
              refreshingQr = false;
            }
          });
    } catch (RejectedExecutionException e) {
      refreshingQr = false;
    }
  }

  @Override
  public void onDestroy() {
    stopped = true;
    refreshingQr = false;
    loggedIn = false;
    qr = "";
    account = "";
    instance = null;
    if (error.isEmpty()) state = "本机 QQ 已停止";
    stopLocalBot();
    main.removeCallbacksAndMessages(null);
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    polling.shutdownNow();
    if (runtime != null) runtime.stop();
    worker.shutdownNow();
    http.dispatcher().cancelAll();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void stopLocalBot() {
    BotService bot = BotService.instance;
    if (bot != null && bot.usesLocal()) stopService(new Intent(this, BotService.class));
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    state = "系统已限制本次初始化，请返回应用重试";
    stopSelf();
  }
}
