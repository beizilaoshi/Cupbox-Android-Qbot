package cn.cupbox.qbot;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.JSONArray;

public final class MainActivity extends Activity {
  private Store store;
  private Config config;
  private LinearLayout root, body, nav;
  private int page = 0;
  private int bg, surface, raised, txt, dim, line, accent;
  private boolean dark;
  private boolean dirty = false, rendering = false;
  private final Map<String, EditText> fields = new HashMap<>();
  private final Map<String, Switch> switches = new HashMap<>();
  private Spinner scope;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView status, stats, logs;
  private final ExecutorService checks = Executors.newSingleThreadExecutor();
  private final OkHttpClient tester =
      new OkHttpClient.Builder()
          .callTimeout(30, TimeUnit.SECONDS)
          .followRedirects(false)
          .followSslRedirects(false)
          .build();
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (status != null)
            status.setText(
                (BotService.online ? "●  " : "○  ")
                    + BotService.state
                    + (BotService.account.isBlank() ? "" : " · " + BotService.account));
          if (stats != null)
            stats.setText(
                "已触发  "
                    + BotService.received
                    + "     /     已发送  "
                    + BotService.sent
                    + "     /     "
                    + (BotService.reply ? "回复中" : "已暂停"));
          if (logs != null) {
            List<String> list = BotService.logs();
            logs.setText(list.isEmpty() ? "还没有运行记录。连接后，这里会显示状态与错误。" : String.join("\n\n", list));
          }
          handler.postDelayed(this, 1500);
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    store = new Store(this);
    try {
      config = store.load();
    } catch (Exception e) {
      config = new Config();
      new AlertDialog.Builder(this)
          .setTitle("配置无法解密")
          .setMessage("原密钥可能已失效。请重新填写配置；原文件不会自动覆盖。")
          .setPositiveButton("知道了", null)
          .show();
    }
    dark = getPreferences(0).getBoolean("dark", false);
    if (b != null) page = b.getInt("page", 0);
    Object retained = getLastNonConfigurationInstance();
    if (retained instanceof Object[] draft) {
      config = (Config) draft[0];
      dirty = (Boolean) draft[1];
    }
    render();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    capture();
    return new Object[] {config, dirty};
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    capture();
    out.putInt("page", page);
    super.onSaveInstanceState(out);
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
    handler.removeCallbacks(refresh);
    checks.shutdownNow();
    tester.dispatcher().cancelAll();
    store.close();
    super.onDestroy();
  }

  int dp(float v) {
    return (int) (v * getResources().getDisplayMetrics().density + .5f);
  }

  private int color(String s) {
    return Color.parseColor(s);
  }

  private GradientDrawable shape(int fill, int stroke, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  private void add(LinearLayout parent, View child, int top) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(top);
    parent.addView(child, p);
  }

  private void note(LinearLayout l, String s) {
    add(l, text(s, 12, dim), 8);
  }

  private TextView title(String s, int size) {
    TextView t = text(s, size, txt);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  private Button button(String label, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTextColor(primary ? bg : accent);
    b.setBackground(shape(primary ? accent : raised, 0, 12));
    b.setMinHeight(dp(48));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private LinearLayout card(String heading) {
    LinearLayout c = column();
    c.setPadding(dp(18), dp(18), dp(18), dp(18));
    c.setBackground(shape(surface, line, 18));
    add(c, title(heading, 16), 0);
    add(body, c, 16);
    return c;
  }

  private void heading(String eyebrow, String title, String sub) {
    add(body, text(eyebrow, 10, accent), 6);
    add(body, title(title, 28), 8);
    add(body, text(sub, 14, dim), 8);
  }

  private EditText field(
      LinearLayout c, String label, String key, String value, boolean secret, boolean multi) {
    add(c, text(label, 13, txt), 18);
    EditText e = new EditText(this);
    e.setTextSize(14);
    e.setTextColor(txt);
    e.setHintTextColor(dim);
    e.setText(value);
    e.setPadding(dp(12), dp(12), dp(12), dp(12));
    e.setBackground(shape(bg, line, 10));
    e.setSingleLine(!multi);
    e.setMinHeight(dp(48));
    e.setSelectAllOnFocus(false);
    if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    else if (multi) {
      e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
      e.setMinLines(3);
      e.setGravity(Gravity.TOP);
    } else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    if (Set.of("cooldown", "perMinute", "context", "maxTokens").contains(key))
      e.setInputType(InputType.TYPE_CLASS_NUMBER);
    if (key.equals("temperature"))
      e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    e.setContentDescription(label);
    e.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (!rendering) dirty = true;
          }

          public void afterTextChanged(android.text.Editable s) {}
        });
    fields.put(key, e);
    add(c, e, 7);
    return e;
  }

  private void toggle(LinearLayout c, String label, String detail, String key, boolean value) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextSize(14);
    s.setTextColor(txt);
    s.setChecked(value);
    s.setPadding(0, dp(10), 0, dp(10));
    s.setMinHeight(dp(48));
    s.setThumbTintList(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {accent, dim}));
    s.setOnCheckedChangeListener(
        (v, on) -> {
          if (!rendering) dirty = true;
        });
    switches.put(key, s);
    add(c, s, 10);
    if (!detail.isBlank()) note(c, detail);
  }

  private void render() {
    setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
    rendering = true;
    fields.clear();
    switches.clear();
    scope = null;
    status = null;
    stats = null;
    logs = null;
    bg = color(dark ? "#202120" : "#fafaf7");
    surface = color(dark ? "#272827" : "#ffffff");
    raised = color(dark ? "#30312f" : "#e9ece3");
    txt = color(dark ? "#ededeb" : "#272c26");
    dim = color(dark ? "#a6a8a2" : "#687064");
    line = color(dark ? "#393b36" : "#dce0d5");
    accent = color(dark ? "#c9d2bd" : "#425e47");
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(surface);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    root = column();
    root.setBackgroundColor(bg);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          }
          return insets;
        });
    setContentView(root);
    LinearLayout top = row();
    top.setPadding(dp(22), dp(14), dp(22), dp(14));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.mipmap.ic_launcher);
    logo.setContentDescription("Cupbox 原版 Logo");
    top.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
    LinearLayout brand = column();
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
    bp.leftMargin = dp(10);
    top.addView(brand, bp);
    brand.addView(title("Cupbox", 17));
    brand.addView(text("QQ 机器人 · Android", 11, dim));
    TextView theme = text(dark ? "浅色" : "深色", 13, accent);
    theme.setGravity(Gravity.CENTER);
    theme.setMinHeight(dp(48));
    theme.setMinWidth(dp(48));
    theme.setOnClickListener(
        v -> {
          capture();
          dark = !dark;
          getPreferences(0).edit().putBoolean("dark", dark).apply();
          render();
        });
    top.addView(theme);
    root.addView(top);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    body = column();
    body.setPadding(dp(22), dp(12), dp(22), dp(28));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    switch (page) {
      case 0 -> overview();
      case 1 -> connection();
      case 2 -> rules();
      case 3 -> persona();
      case 4 -> memory();
      default -> overview();
    }
    nav = row();
    nav.setBackgroundColor(surface);
    String[] labels = {"概览", "连接", "规则", "人格", "记忆"};
    for (int i = 0; i < labels.length; i++) {
      final int selected = i;
      TextView n = text(labels[i], 13, i == page ? accent : dim);
      n.setTypeface(null, i == page ? Typeface.BOLD : Typeface.NORMAL);
      n.setGravity(Gravity.CENTER);
      n.setMinHeight(dp(58));
      if (i == page) n.setBackground(shape(raised, 0, 12));
      n.setOnClickListener(
          v -> {
            capture();
            page = selected;
            render();
          });
      nav.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
    }
    nav.setPadding(dp(8), dp(6), dp(8), dp(6));
    root.addView(nav);
    rendering = false;
  }

  private void overview() {
    heading("A LITTLE COMPANY", "让聊天，有个回应。", "熟悉的 Cupbox，只留下你的 QQ 搭子。");
    LinearLayout c = card("你的机器人");
    status = text("○  " + BotService.state, 14, accent);
    add(c, status, 14);
    stats =
        text(
            "已触发  "
                + BotService.received
                + "     /     已发送  "
                + BotService.sent
                + "     /     "
                + (BotService.reply ? "回复中" : "已暂停"),
            12,
            dim);
    add(c, stats, 10);
    add(
        c,
        button(
            BotService.instance == null ? "连接机器人" : "停止连接",
            true,
            () -> {
              if (BotService.instance != null) {
                stopService(new Intent(this, BotService.class));
                handler.postDelayed(this::render, 180);
              } else startBot();
            }),
        18);
    add(
        c,
        button(
            BotService.reply ? "立即暂停回复" : "开启自动回复",
            false,
            () -> {
              if (BotService.reply) {
                if (BotService.instance != null) BotService.instance.pause();
                render();
                return;
              }
              enableReply();
            }),
        10);
    note(c, "连接成功后仍需手动开启回复。切到后台可继续运行；系统省电策略可能断开连接。");
    LinearLayout steps = card("三步，开始陪伴");
    step(steps, "01", "连接 QQ 协议端", "填写已登录 QQ 的 OneBot 正向 WebSocket 地址。");
    step(steps, "02", "选择模型与人格", "连接兼容 Chat Completions 的模型，写下聊天语气。");
    step(steps, "03", "圈定回复范围", "默认群白名单为空、私聊关闭，先设置规则再开启。");
    LinearLayout activity = card("运行动态");
    logs =
        text(
            BotService.logs().isEmpty()
                ? "还没有运行记录。连接后，这里会显示状态与错误。"
                : String.join("\n\n", BotService.logs()),
            12,
            dim);
    logs.setTextIsSelectable(true);
    add(activity, logs, 14);
    note(body, "Cupbox Android 0.1.0 · 原生轻量版\n不在手机上加载本地大模型，也不附带 Windows 协议端。");
  }

  private void step(LinearLayout c, String number, String label, String sub) {
    LinearLayout r = row();
    TextView n = text(number, 12, accent);
    r.addView(n, new LinearLayout.LayoutParams(dp(36), -2));
    LinearLayout t = column();
    t.addView(title(label, 14));
    add(t, text(sub, 12, dim), 5);
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    add(c, r, 18);
  }

  private void connection() {
    heading("STAY CONNECTED", "连上，才有下一句。", "手机处理 AI 对话，协议端负责 QQ 登录与消息收发。");
    LinearLayout c = card("QQ · OneBot v11");
    field(c, "正向 WebSocket 地址", "ws", config.ws, false, false).setHint("ws://192.168.1.10:3001");
    field(c, "Access Token", "token", config.token, true, false);
    note(
        c,
        "在 NapCat 等协议端开启 OneBot 正向 WebSocket，消息格式选 array，并开启心跳。局域网填写协议端设备的 IP；127.0.0.1"
            + " 仅指手机本身。公网请使用 wss://。");
    LinearLayout m = card("对话模型");
    field(m, "API 基础地址（含 /v1，不含 /chat/completions）", "base", config.base, false, false);
    field(m, "API Key", "key", config.key, true, false);
    field(m, "模型名称", "model", config.model, false, false).setHint("填写服务商提供的准确模型 ID");
    add(m, button("测试模型连接", false, this::testModel), 16);
    note(m, "测试会向当前草稿中的模型发送一句问候，可能产生少量 API 费用。支持无需 Key 的自建接口。公网请使用 HTTPS。");
    add(body, button("保存连接设置", true, this::save), 20);
    note(body, "密钥与配置由 Android Keystore 加密保存；应用不备份密钥。连接设置变更后需重新启动机器人。");
  }

  private void rules() {
    heading("A COMFORTABLE PACE", "有分寸地接话。", "触发规则由程序执行，人设和群聊文字不能改变范围。");
    LinearLayout c = card("回复对象");
    add(c, text("群聊范围", 13, txt), 16);
    scope = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"白名单", "黑名单", "所有群"}) {
          public View getView(int pos, View convert, android.view.ViewGroup parent) {
            TextView t = (TextView) super.getView(pos, convert, parent);
            t.setTextColor(txt);
            t.setTextSize(14);
            return t;
          }
        };
    scope.setAdapter(adapter);
    scope.setSelection(config.scope.equals("白名单") ? 0 : config.scope.equals("黑名单") ? 1 : 2);
    scope.setMinimumHeight(dp(48));
    add(c, scope, 5);
    scope.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onItemSelected(android.widget.AdapterView<?> a, View v, int p, long id) {
            if (!config.scope.equals(a.getItemAtPosition(p))) dirty = true;
          }

          public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });
    field(c, "群号（逗号或换行分隔）", "groups", config.groups, false, true);
    note(c, "白名单为空时不回复任何群；黑名单为空时允许所有群进入触发判断。");
    toggle(c, "回应群内 @", "只认真实的 @ 消息段，不认文字伪造的 CQ 代码。", "mention", config.mention);
    field(c, "群聊触发关键词", "keywords", config.keywords, false, true);
    note(c, "多个关键词用逗号或换行分隔，与 @ 是“或”的关系。两项都不设置则不触发群回复。");
    toggle(c, "允许私聊回复", "开启后处理该机器人账号收到的私聊。", "privateReply", config.privateReply);
    toggle(c, "群回复时 @ 对方", "在发送的群消息中加入真实 @。", "replyAt", config.replyAt);
    LinearLayout pace = card("聊天节奏");
    field(pace, "每个会话冷却（秒，1–300）", "cooldown", "" + config.cooldown, false, false);
    field(pace, "每分钟最多回复（1–60）", "perMinute", "" + config.perMinute, false, false);
    note(pace, "同一时间只处理一条模型请求，忙碌期间不积压消息。每轮最多 1800 字；发送结果不明时不会自动重发。");
    add(body, button("保存回复规则", true, this::save), 20);
  }

  private void persona() {
    heading("YOUR KIND OF COMPANY", "用你的方式，交流。", "少一点机械，多一点恰到好处的回应。");
    LinearLayout c = card("聊天人格");
    LinearLayout presets = row();
    String[] names = {"温柔搭子", "机灵搭子", "自定义"};
    for (int i = 0; i < names.length; i++) {
      final int p = i;
      Button b =
          button(
              names[i],
              false,
              () -> {
                String[] prompts = {
                  "你是温柔、有主见的聊天搭子。用简短自然的中文接住话题，不假装真人，不编造事实。",
                  "你是机灵、俏皮而有分寸的中文聊天搭子。回答简短，偶尔幽默，尊重对方，不刷屏，不编造事实。",
                  ""
                };
                EditText e = fields.get("prompt");
                if (e != null) e.setText(prompts[p]);
              });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.setMargins(dp(2), 0, dp(2), 0);
      presets.addView(b, lp);
    }
    add(c, presets, 14);
    field(c, "自定义设定（最多 8000 字）", "prompt", config.prompt, false, true).setMinLines(7);
    field(c, "温度（0–2）", "temperature", "" + config.temperature, false, false);
    field(c, "每次输出 token 上限（64–2048）", "maxTokens", "" + config.maxTokens, false, false);
    note(c, "模型必须支持 temperature 与 max_tokens 参数。部分专用推理接口可能不兼容，可先用连接测试核对。");
    add(body, button("保存人格设置", true, this::save), 20);
  }

  private void memory() {
    heading("WHAT WE REMEMBER", "记得住，也管得住。", "只记录已触发、且成功投递的对话。不收集旁听消息。");
    LinearLayout c = card("对话记忆");
    note(c, store.summary());
    toggle(c, "使用并保存会话记忆", "按机器人 QQ + 群 / 私聊分别隔离；关闭后不读取，也不新增。", "memory", config.memory);
    field(c, "每次参考历史条数（0–20）", "context", "" + config.context, false, false);
    note(c, "最多保存 32 个会话，每会话最近 20 条。记忆保存在应用私有数据库，为本机明文；开启后相关历史随请求提交给模型。卸载会删除。");
    add(
        c,
        button(
            "查看已保存记忆",
            false,
            () -> {
              new AlertDialog.Builder(this)
                  .setTitle("本地记忆")
                  .setMessage(memoryDetail())
                  .setPositiveButton("关闭", null)
                  .show();
            }),
        18);
    add(
        c,
        button(
            "清空所有会话记忆",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("清空所有记忆？")
                    .setMessage("将停止机器人并永久删除此手机保存的会话记录。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "停止并清空",
                        (d, w) -> {
                          stopService(new Intent(this, BotService.class));
                          handler.postDelayed(
                              () -> {
                                store.clear();
                                render();
                              },
                              250);
                        })
                    .show()),
        10);
    add(body, button("保存记忆设置", true, this::save), 20);
    LinearLayout about = card("关于这个轻量版");
    note(
        about,
        "沿用原版 Cupbox Logo、深浅配色与安静的卡片风格。Android 8.0 及以上，无 Electron、WebView、Node 或本地大模型。\n\n"
            + "手机需连接已登录的 QQ 协议端；不内置扫码登录。非官方 QQ 接入可能受到平台限制，建议使用专用机器人账号。");
    add(
        about,
        button(
            "后台运行说明",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("后台运行")
                    .setMessage(
                        "运行时保留常驻通知。若手机系统限制后台，请在系统设置中允许 Cupbox"
                            + " 自启动与后台活动，并连接稳定网络。系统强制停止、重启手机后需手动重新连接；本应用不会自动恢复发言。")
                    .setPositiveButton("知道了", null)
                    .show()),
        14);
  }

  private String memoryDetail() {
    StringBuilder out = new StringBuilder();
    try (android.database.Cursor c =
        store
            .getReadableDatabase()
            .rawQuery("SELECT session,role,body FROM history ORDER BY id DESC LIMIT 40", null)) {
      while (c.moveToNext()) {
        String value = c.getString(2);
        out.append(c.getString(0))
            .append(" · ")
            .append(c.getString(1).equals("user") ? "对方" : "机器人")
            .append("\n")
            .append(value.length() > 250 ? value.substring(0, 250) + "…" : value)
            .append("\n\n");
      }
    }
    return out.length() == 0 ? "暂无记忆。" : out.toString();
  }

  private String val(String k, String old) {
    return fields.containsKey(k) ? fields.get(k).getText().toString().trim() : old;
  }

  private boolean flag(String k, boolean old) {
    return switches.containsKey(k) ? switches.get(k).isChecked() : old;
  }

  private int number(String k, int old) {
    try {
      return Integer.parseInt(val(k, "" + old));
    } catch (Exception e) {
      return -1;
    }
  }

  private void capture() {
    config.ws = val("ws", config.ws);
    config.token = val("token", config.token);
    config.base = val("base", config.base);
    config.key = val("key", config.key);
    config.model = val("model", config.model);
    config.prompt = val("prompt", config.prompt);
    config.groups = val("groups", config.groups);
    config.keywords = val("keywords", config.keywords);
    if (scope != null) config.scope = scope.getSelectedItem().toString();
    config.mention = flag("mention", config.mention);
    config.privateReply = flag("privateReply", config.privateReply);
    config.replyAt = flag("replyAt", config.replyAt);
    config.memory = flag("memory", config.memory);
    config.cooldown = number("cooldown", config.cooldown);
    config.perMinute = number("perMinute", config.perMinute);
    config.context = number("context", config.context);
    config.maxTokens = number("maxTokens", config.maxTokens);
    try {
      config.temperature = Double.parseDouble(val("temperature", "" + config.temperature));
    } catch (Exception e) {
      config.temperature = -1;
    }
  }

  private void validate() {
    config.validate(false);
    if (config.cooldown < 1
        || config.cooldown > 300
        || config.perMinute < 1
        || config.perMinute > 60
        || config.context < 0
        || config.context > 20
        || config.maxTokens < 64
        || config.maxTokens > 2048
        || !Double.isFinite(config.temperature)
        || config.temperature < 0
        || config.temperature > 2) throw new IllegalArgumentException("数值超出范围，请检查各项标注的上下限");
  }

  private void save() {
    capture();
    try {
      validate();
      store.save(config);
      dirty = false;
      if (BotService.instance != null) stopService(new Intent(this, BotService.class));
      toast("已保存；重新连接后生效");
    } catch (Exception e) {
      error(e.getMessage());
    }
  }

  private void startBot() {
    if (dirty) {
      error("有未保存的草稿，请先保存设置。");
      return;
    }
    try {
      Config saved = store.load();
      saved.validate(true);
      if (Build.VERSION.SDK_INT >= 33
          && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              != android.content.pm.PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 7);
      startForegroundService(new Intent(this, BotService.class));
      handler.postDelayed(this::render, 250);
    } catch (Exception e) {
      error(e.getMessage());
    }
  }

  private void enableReply() {
    if (BotService.instance == null || !BotService.online) {
      toast("请先连接机器人");
      return;
    }
    if (dirty) {
      error("请先保存草稿并重新连接，以便核对实际回复范围。");
      return;
    }
    try {
      Config c = store.load();
      new AlertDialog.Builder(this)
          .setTitle("开启自动回复？")
          .setMessage(
              "群聊范围："
                  + c.scope
                  + "\n群号："
                  + (c.groups.isBlank() ? "空" : c.groups)
                  + "\n私聊："
                  + (c.privateReply ? "允许" : "关闭")
                  + "\n模型："
                  + c.model
                  + "\n\n触发的消息"
                  + (c.memory ? "与本会话历史" : "")
                  + "将发送给已配置的模型服务。自动回复会实际发送到 QQ。")
          .setNegativeButton("取消", null)
          .setPositiveButton(
              "开启回复",
              (d, w) -> {
                if (BotService.instance != null) BotService.instance.enable();
                render();
              })
          .show();
    } catch (Exception e) {
      error("读取配置失败");
    }
  }

  private void testModel() {
    capture();
    try {
      validate();
      if (config.model.isBlank()) throw new IllegalArgumentException("请填写模型名称");
      Config draft = Config.from(config.json());
      toast("正在测试模型连接…");
      checks.execute(
          () -> {
            try (Response r =
                tester
                    .newCall(ModelClient.request(draft, new JSONArray(), "请只回复：连接成功"))
                    .execute()) {
              ModelClient.answer(r);
              runOnUiThread(() -> toast("模型连接成功"));
            } catch (Exception e) {
              runOnUiThread(
                  () ->
                      error(
                          "模型测试失败："
                              + (e.getMessage() != null && e.getMessage().startsWith("模型")
                                  ? e.getMessage()
                                  : "请检查网络、地址、密钥和模型名称")));
            }
          });
    } catch (Exception e) {
      error(e.getMessage());
    }
  }

  private void error(String value) {
    new AlertDialog.Builder(this)
        .setTitle("请检查一下")
        .setMessage(value == null ? "操作失败，请重试" : value)
        .setPositiveButton("知道了", null)
        .show();
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onBackPressed() {
    if (dirty)
      new AlertDialog.Builder(this)
          .setTitle("还有未保存的设置")
          .setMessage("离开后本次草稿将丢失。")
          .setNegativeButton("继续编辑", null)
          .setPositiveButton("放弃并离开", (d, w) -> finish())
          .show();
    else super.onBackPressed();
  }
}
