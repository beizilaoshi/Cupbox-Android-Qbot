package cn.cupbox.qbot;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

public final class BotService extends Service {
  public static final String STOP = "cn.cupbox.qbot.STOP", PAUSE = "cn.cupbox.qbot.PAUSE";
  public static volatile BotService instance;
  public static volatile String state = "尚未连接", account = "";
  public static volatile boolean online = false, reply = false;
  public static volatile int received = 0, sent = 0;
  private static final ArrayDeque<String> logs = new ArrayDeque<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(12, TimeUnit.SECONDS)
          .readTimeout(60, TimeUnit.SECONDS)
          .callTimeout(75, TimeUnit.SECONDS)
          .pingInterval(25, TimeUnit.SECONDS)
          .followRedirects(false)
          .followSslRedirects(false)
          .build();
  private WebSocket socket;
  private Store store;
  private Config config;
  private Call active;
  private boolean busy = false;
  private volatile boolean stopped = false;
  private volatile int epoch = 0;
  private int retries = 0;
  private String pendingEcho = "";
  private Runnable ackTimeout;
  private Rules.Message pendingMessage;
  private String pendingAnswer;
  private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
  private final LinkedHashMap<String, Long> cooldown = new LinkedHashMap<>();
  private final ArrayDeque<Long> rate = new ArrayDeque<>();

  public static synchronized List<String> logs() {
    return new ArrayList<>(logs);
  }

  public static synchronized void log(String s) {
    logs.addFirst(
        new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s);
    while (logs.size() > 80) logs.removeLast();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    store = new Store(this);
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel("bot", "QQ 机器人运行", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (intent != null && PAUSE.equals(intent.getAction())) {
      if (config == null) {
        stopSelf();
        return START_NOT_STICKY;
      }
      pause();
      return START_NOT_STICKY;
    }
    startForeground(1, notification());
    if (config != null) return START_NOT_STICKY;
    try {
      config = store.load();
      config.validate(true);
      connect();
    } catch (Exception e) {
      log("无法启动，请检查已保存设置");
      state = "设置不完整";
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent pause =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, BotService.class).setAction(PAUSE),
            PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            2,
            new Intent(this, BotService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "bot")
        .setSmallIcon(cn.cupbox.qbot.R.drawable.ic_notification)
        .setContentTitle("Cupbox · " + state)
        .setContentText(reply ? "自动回复已启用" : "自动回复已暂停")
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "暂停回复", pause).build())
        .addAction(new Notification.Action.Builder(null, "停止", stop).build())
        .build();
  }

  private void update() {
    getSystemService(NotificationManager.class).notify(1, notification());
  }

  private void connect() {
    if (stopped) return;
    state = "正在连接";
    update();
    int current = ++epoch;
    Request.Builder r = new Request.Builder().url(config.ws);
    if (!config.token.isBlank()) r.header("Authorization", "Bearer " + config.token);
    socket =
        http.newWebSocket(
            r.build(),
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket ws, Response response) {
                main.post(
                    () -> {
                      if (epoch != current || stopped) {
                        ws.cancel();
                        return;
                      }
                      state = "验证 QQ 登录中";
                      update();
                      sendAction("get_login_info", new JSONObject(), "login");
                      main.postDelayed(
                          () -> {
                            if (epoch == current && !online) disconnected(current);
                          },
                          15000);
                    });
              }

              @Override
              public void onMessage(WebSocket ws, String text) {
                if (text.length() > 65536) return;
                main.post(
                    () -> {
                      if (epoch == current && !stopped) message(text);
                    });
              }

              @Override
              public void onFailure(WebSocket ws, Throwable t, Response response) {
                main.post(() -> disconnected(current));
              }

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {
                main.post(() -> disconnected(current));
              }

              @Override
              public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, null);
              }
            });
  }

  private void disconnected(int current) {
    if (stopped || epoch != current) return;
    ++epoch;
    online = false;
    account = "";
    cancelWork();
    if (socket != null) socket.cancel();
    state = "连接中断 · 等待重连";
    log("协议端离线，自动回复已暂停");
    reply = false;
    update();
    main.postDelayed(this::connect, Math.min(60000, 2000L << Math.min(retries++, 5)));
  }

  private boolean sendAction(String action, JSONObject params, String echo) {
    try {
      return socket != null
          && socket.send(
              new JSONObject()
                  .put("action", action)
                  .put("params", params)
                  .put("echo", echo)
                  .toString());
    } catch (Exception e) {
      return false;
    }
  }

  private void message(String text) {
    try {
      JSONObject j = new JSONObject(text);
      String echo = j.optString("echo");
      if (j.optString("meta_event_type").equals("heartbeat")) {
        JSONObject health = j.optJSONObject("status");
        if (online && health != null && !health.optBoolean("online", true)) {
          disconnected(epoch);
        }
        return;
      }
      if (echo.equals("login")) {
        JSONObject data = j.optJSONObject("data");
        if (j.optInt("retcode", -1) == 0 && data != null && !data.optString("user_id").isEmpty()) {
          account = data.optString("user_id");
          online = true;
          retries = 0;
          state = "QQ 已连接";
          log("QQ 登录状态已确认");
          update();
        }
        return;
      }
      if (!pendingEcho.isEmpty() && echo.equals(pendingEcho)) {
        if (ackTimeout != null) main.removeCallbacks(ackTimeout);
        boolean success = j.optInt("retcode", -1) == 0 && j.optString("status").equals("ok");
        if (success) {
          sent++;
          if (config.memory)
            store.remember(pendingMessage.conversation(), pendingMessage.text(), pendingAnswer);
          log("回复投递成功 · " + pendingAnswer.length() + " 字");
        } else log("QQ 拒绝发送，未记录为成功");
        clearPending();
        busy = false;
        return;
      }
      Rules.Message m = Rules.parse(j);
      if (m == null || !online || !m.account().equals(account) || !Rules.allowed(config, m)) return;
      received++;
      String id = m.account() + ":" + m.id();
      if (m.id().isBlank() || seen.containsKey(id)) return;
      seen.put(id, true);
      trim(seen, 512);
      if (!reply || busy) return;
      long now = SystemClock.elapsedRealtime();
      Long last = cooldown.get(m.conversation());
      if (last != null && now - last < config.cooldown * 1000L) return;
      while (!rate.isEmpty() && now - rate.peekFirst() > 60000) rate.removeFirst();
      if (rate.size() >= config.perMinute) {
        log("已达每分钟回复上限");
        return;
      }
      cooldown.put(m.conversation(), now);
      trim(cooldown, 64);
      rate.addLast(now);
      busy = true;
      int current = epoch;
      log("规则命中 · 正在生成回复");
      worker.execute(() -> generate(m, current));
    } catch (Exception e) {
      log("忽略无效协议消息");
    }
  }

  private static void trim(Map<?, ?> map, int max) {
    while (map.size() > max) map.remove(map.keySet().iterator().next());
  }

  private void generate(Rules.Message m, int current) {
    try {
      JSONArray history =
          config.memory ? store.history(m.conversation(), config.context) : new JSONArray();
      Call call = http.newCall(ModelClient.request(config, history, m.text()));
      synchronized (this) {
        if (stopped || epoch != current || !reply) return;
        active = call;
      }
      String answer;
      try (Response response = call.execute()) {
        answer = ModelClient.answer(response);
      }
      main.post(
          () -> {
            if (stopped || epoch != current || !reply) {
              busy = false;
              return;
            }
            deliver(m, answer);
          });
    } catch (Exception e) {
      main.post(
          () -> {
            if (epoch == current) {
              busy = false;
              log("模型请求失败，请检查模型、网络及额度；未向 QQ 发送错误内容");
            }
          });
    }
  }

  private void deliver(Rules.Message m, String answer) {
    try {
      JSONArray parts = new JSONArray();
      if (config.replyAt && !m.group().isEmpty())
        parts.put(
            new JSONObject().put("type", "at").put("data", new JSONObject().put("qq", m.user())));
      parts.put(
          new JSONObject().put("type", "text").put("data", new JSONObject().put("text", answer)));
      JSONObject params = new JSONObject().put("message", parts);
      boolean group = !m.group().isEmpty();
      params.put(group ? "group_id" : "user_id", group ? m.group() : m.user());
      pendingEcho = "send-" + UUID.randomUUID();
      pendingMessage = m;
      pendingAnswer = answer;
      if (!sendAction(group ? "send_group_msg" : "send_private_msg", params, pendingEcho)) {
        clearPending();
        busy = false;
        log("连接不可用，回复未发送");
        return;
      }
      ackTimeout =
          () -> {
            if (!pendingEcho.isEmpty()) {
              clearPending();
              busy = false;
              log("投递确认超时，结果未知；不自动重发");
            }
          };
      main.postDelayed(ackTimeout, 15000);
    } catch (Exception e) {
      clearPending();
      busy = false;
      log("回复构建失败");
    }
  }

  private void clearPending() {
    pendingEcho = "";
    pendingMessage = null;
    pendingAnswer = null;
  }

  private synchronized void cancelWork() {
    ++epoch;
    if (active != null) active.cancel();
    active = null;
    if (ackTimeout != null) main.removeCallbacks(ackTimeout);
    clearPending();
    busy = false;
  }

  public void pause() {
    reply = false;
    cancelWork();
    if (socket != null) socket.cancel();
    online = false;
    state = "回复已暂停 · 重新连接";
    log("已暂停，取消未发送的回复");
    update();
    main.removeCallbacksAndMessages(null);
    connect();
  }

  public void enable() {
    if (online) {
      reply = true;
      log("自动回复已启用");
      update();
    }
  }

  @Override
  public void onDestroy() {
    stopped = true;
    reply = false;
    online = false;
    account = "";
    cancelWork();
    main.removeCallbacksAndMessages(null);
    if (socket != null) socket.cancel();
    worker.shutdownNow();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
    state = "已停止";
    instance = null;
    log("机器人已停止");
    store.close();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
