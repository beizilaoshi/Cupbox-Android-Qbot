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
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.JSONArray;

public final class MainActivity extends AppCompatActivity {
  private Store store;
  private Config config;
  private LinearLayout root, body, nav;
  private LinearLayout draftBar;
  private int page = 0;
  private int bg, surface, raised, txt, dim, line, accent;
  private boolean dark;
  private boolean dirty = false, rendering = false;
  private final Map<String, EditText> fields = new HashMap<>();
  private final Map<String, CompoundButton> switches = new HashMap<>();
  private Spinner scope;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView status, stats, logs;
  private TextView connectionFact;
  private static boolean introShown;
  private String homeState = "";
  private android.app.Dialog modelWait;
  private TextView modelFact;
  private long modelStarted;
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
          if (page == 0) {
            String next =
                RuntimeService.loggedIn
                    + ":"
                    + BotService.online
                    + ":"
                    + BotService.reply
                    + ":"
                    + (BotService.instance != null);
            if (!next.equals(homeState)) {
              homeState = next;
              render();
            }
          }
          if (modelFact != null)
            modelFact.setText(WaitFacts.at(SystemClock.elapsedRealtime() - modelStarted));
          if (connectionFact != null)
            connectionFact.setText(WaitFacts.at(SystemClock.elapsedRealtime()));
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
      new MaterialAlertDialogBuilder(this)
          .setTitle("配置无法解密")
          .setMessage("原密钥可能已失效。请重新填写配置；原文件不会自动覆盖。")
          .setPositiveButton("知道了", null)
          .show();
    }
    dark = getPreferences(0).getBoolean("dark", false);
    if (b != null) page = b.getInt("page", 0);
    Object retained = getLastCustomNonConfigurationInstance();
    if (retained instanceof Object[] draft) {
      config = (Config) draft[0];
      dirty = (Boolean) draft[1];
    }
    render();
    if (b == null && !introShown) {
      introShown = true;
      showIntro();
    }
  }

  @Override
  public Object onRetainCustomNonConfigurationInstance() {
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
    if (modelWait != null) modelWait.dismiss();
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
    MaterialButton b = new MaterialButton(this);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTextColor(primary ? bg : accent);
    b.setBackgroundTintList(ColorStateList.valueOf(primary ? accent : raised));
    b.setCornerRadius(dp(16));
    b.setElevation(0);
    b.setMinHeight(dp(48));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private LinearLayout card(String heading) {
    LinearLayout c = column();
    c.setPadding(dp(16), dp(16), dp(16), dp(16));
    c.setBackground(shape(surface, line, 22));
    add(c, title(heading, 16), 0);
    add(body, c, 16);
    return c;
  }

  private void heading(String eyebrow, String title, String sub) {

    add(body, title(title, 30), 8);
    add(body, text(sub, 14, dim), 8);
  }

  private EditText field(
      LinearLayout c, String label, String key, String value, boolean secret, boolean multi) {
    add(c, text(label, 13, txt), 18);
    TextInputLayout box =
        new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
    box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    box.setBoxCornerRadii(dp(16), dp(16), dp(16), dp(16));
    box.setHintEnabled(false);
    TextInputEditText e = new TextInputEditText(box.getContext());
    e.setTextSize(14);
    e.setTextColor(txt);
    e.setHintTextColor(dim);
    e.setText(value);
    e.setPadding(dp(12), dp(12), dp(12), dp(12));
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
            if (!rendering) {
              dirty = true;
              updateDraftBar();
            }
          }

          public void afterTextChanged(android.text.Editable s) {}
        });
    fields.put(key, e);
    box.addView(e, new LinearLayout.LayoutParams(-1, -2));
    if (secret) box.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
    add(c, box, 7);
    return e;
  }

  private void toggle(LinearLayout c, String label, String detail, String key, boolean value) {
    SwitchMaterial s = new SwitchMaterial(this);
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
          if (!rendering) {
            dirty = true;
            updateDraftBar();
          }
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
    connectionFact = null;
    bg = color(dark ? "#141b17" : "#f5f6f2");
    surface = color(dark ? "#202a23" : "#ffffff");
    raised = color(dark ? "#344c3b" : "#e4eddf");
    txt = color(dark ? "#e0e6da" : "#192019");
    dim = color(dark ? "#c2c9bb" : "#42493f");
    line = color(dark ? "#344038" : "#e8ece4");
    accent = color(dark ? "#accfa8" : "#46664b");
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
    top.setPadding(dp(22), dp(8), dp(22), dp(8));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.mipmap.ic_launcher);
    logo.setContentDescription("Cupbox 原版 Logo");
    top.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
    LinearLayout brand = column();
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
    bp.leftMargin = dp(10);
    top.addView(brand, bp);
    brand.addView(title("Cupbox", 17));
    brand.addView(text("给聊天留一点温度", 11, dim));
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
    body.setPadding(dp(22), dp(4), dp(22), dp(28));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    switch (page) {
      case 0 -> overview();
      case 1 -> connection();
      case 2 -> rules();
      case 3 -> persona();
      case 4 -> memory();
      case 5 -> model();
      case 6 -> settings();
      default -> overview();
    }
    draftBar = row();
    draftBar.setPadding(dp(22), 0, dp(22), 0);
    draftBar.setBackgroundColor(raised);
    draftBar.addView(text("有修改尚未保存", 12, txt), new LinearLayout.LayoutParams(0, -2, 1));
    draftBar.addView(button("保存修改", true, this::save));
    root.addView(draftBar, new LinearLayout.LayoutParams(-1, -2));
    updateDraftBar();
    BottomNavigationView bottom = new BottomNavigationView(this);
    bottom.setBackgroundColor(surface);
    bottom.setElevation(0);
    bottom.setLabelVisibilityMode(
        com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
    String[] labels = {"首页", "人格", "设置"};
    int[] pages = {0, 3, 6};
    int[] icons = {R.drawable.ic_home, R.drawable.ic_persona, R.drawable.ic_rules};
    bottom.setItemIconTintList(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {accent, dim}));
    bottom.setItemTextColor(bottom.getItemIconTintList());
    bottom.setItemActiveIndicatorColor(ColorStateList.valueOf(raised));
    for (int i = 0; i < labels.length; i++)
      bottom.getMenu().add(0, pages[i] + 1, i, labels[i]).setIcon(icons[i]);
    bottom.setSelectedItemId(page == 0 || page == 3 ? page + 1 : 7);
    bottom.setOnItemSelectedListener(
        item -> {
          go(item.getItemId() - 1);
          return true;
        });
    root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
    rendering = false;
  }

  private void go(int target) {
    capture();
    page = target;
    render();
  }

  private void updateDraftBar() {
    if (draftBar != null) draftBar.setVisibility(dirty ? View.VISIBLE : View.GONE);
  }

  private boolean hasScope() {
    return config.privateReply
        || ((!config.scope.equals("白名单") || !config.groups.isBlank())
            && (config.mention || !config.keywords.isBlank()));
  }

  private void overview() {
    LinearLayout hero = row();
    LinearLayout words = column();
    words.addView(text("CUPBOX / QQ COMPANION", 10, accent));
    add(words, title("今天，也有回应。", 24), 10);
    add(words, text("让你的 QQ 搭子，随时在场。", 13, dim), 8);
    hero.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    hero.addView(new BrandView(this), new LinearLayout.LayoutParams(dp(80), dp(88)));
    add(body, hero, 0);
    LinearLayout c = card(BotService.reply ? "正在陪伴" : "机器人待命");
    status = text("○  " + BotService.state, 14, accent);
    add(c, status, 12);
    stats = text("已触发 0     ·     已发送 0", 12, dim);
    add(c, stats, 8);
    boolean qqReady = config.useLocal ? RuntimeService.loggedIn : !config.ws.isBlank();
    String action =
        dirty
            ? "保存设置，继续下一步"
            : !qqReady
                ? "第一步 · 登录 QQ"
                : config.model.isBlank()
                    ? "第二步 · 配置模型"
                    : !hasScope()
                        ? "第三步 · 设置回复范围"
                        : BotService.instance == null
                            ? "连接机器人"
                            : !BotService.online
                                ? "正在连接 · 点击查看记录"
                                : BotService.reply ? "暂停自动回复" : "开启自动回复";
    add(
        c,
        button(
            action,
            true,
            () -> {
              if (dirty) {
                save();
                render();
              } else if (!qqReady) go(1);
              else if (config.model.isBlank()) go(5);
              else if (!hasScope()) go(2);
              else if (BotService.instance == null) startBot();
              else if (!BotService.online) showLogs();
              else if (BotService.reply) {
                BotService.instance.pause();
                render();
              } else enableReply();
            }),
        16);
    if (BotService.instance != null && !BotService.online) {
      ProgressBar busy = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      busy.setIndeterminate(true);
      add(c, busy, 8);
      connectionFact = text(WaitFacts.at(SystemClock.elapsedRealtime()), 12, dim);
      add(c, connectionFact, 8);
    }
    if (BotService.instance != null)
      add(
          c,
          button(
              "断开机器人",
              false,
              () -> {
                stopService(new Intent(this, BotService.class));
                handler.postDelayed(this::render, 300);
              }),
          4);
    note(c, BotService.reply ? "只在你允许的范围内回应。" : "连接后先待命，由你决定何时开始回复。");
    LinearLayout guide = card("开始之前 · 3 个步骤");
    entry(
        guide,
        qqReady ? "01  QQ 已就绪" : "01  登录 QQ",
        config.useLocal
            ? (RuntimeService.loggedIn ? RuntimeService.account : "本机扫码，无需电脑常驻")
            : "使用远程 OneBot",
        1);
    entry(
        guide,
        config.model.isBlank() ? "02  选择对话模型" : "02  " + config.model,
        "填写接口 → 测试连接 → 保存",
        5);
    entry(
        guide,
        hasScope() ? "03  回复范围已设置" : "03  设置回复范围",
        config.privateReply ? "私聊已开启 · 群聊按规则触发" : "选择群聊，设置 @ 或关键词触发",
        2);
    LinearLayout persona = card("今天由谁来接话");
    entry(
        persona,
        config.persona.equals("whale")
            ? "蓝色大肥鱼"
            : config.persona.equals("custom") ? "自定义人格" : "温柔搭子",
        "内置原版鲸鱼娘，也可以写下你的设定",
        3);
    add(body, button("查看运行记录", false, this::showLogs), 16);
    note(body, "Cupbox Android " + AppInfo.version(this) + " · 本机准备环境后可在后台运行");
  }

  private void entry(LinearLayout parent, String label, String detail, int target) {
    LinearLayout r = row();
    r.setPadding(0, dp(14), 0, dp(14));
    LinearLayout copy = column();
    copy.addView(title(label, 15));
    add(copy, text(detail, 12, dim), 5);
    r.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    TextView arrow = text("›", 26, accent);
    arrow.setPadding(dp(12), 0, 0, 0);
    r.addView(arrow);
    r.setBackgroundResource(android.R.drawable.list_selector_background);
    r.setOnClickListener(v -> go(target));
    r.setContentDescription(label + "，" + detail);
    add(parent, r, 4);
  }

  private void showLogs() {
    new MaterialAlertDialogBuilder(this)
        .setTitle("运行记录")
        .setMessage(
            BotService.logs().isEmpty()
                ? "还没有运行记录，连接机器人后会显示在这里。"
                : String.join("\n\n", BotService.logs()))
        .setPositiveButton("关闭", null)
        .show();
  }

  private void backLink() {
    add(body, button("‹ 返回首页", false, () -> go(0)), 0);
  }

  private void connection() {
    backLink();
    heading("", "01 / 登录 QQ", "先登录账号，再配置模型与回复范围。");
    LinearLayout local = card("本机扫码登录");
    note(local, "基础环境已内置。应用自动处理端口、协议配置和启动脚本，你只需扫码确认。");
    add(
        local,
        button(
            "进入本机登录",
            true,
            () -> {
              capture();
              if (!config.useLocal) {
                config.useLocal = true;
                dirty = true;
              }
              startActivity(new Intent(this, LoginActivity.class));
            }),
        14);
    note(local, RuntimeService.state);
    LinearLayout remote = card("已有协议端？");
    toggle(remote, "使用本机 QQ", "关闭后才显示远程连接设置。", "useLocal", config.useLocal);
    switches
        .get("useLocal")
        .setOnCheckedChangeListener(
            (v, on) -> {
              capture();
              config.useLocal = on;
              dirty = true;
              render();
            });
    if (!config.useLocal) {
      field(remote, "WebSocket 地址", "ws", config.ws, false, false)
          .setHint("ws://192.168.1.10:3001");
      field(remote, "Access Token", "token", config.token, true, false);
      note(remote, "在协议端开启 OneBot 正向 WebSocket，消息格式选 array。局域网填写协议端设备 IP。");
    }
    add(
        body,
        button(
            "保存登录方式 · 返回首页",
            true,
            () -> {
              save();
              if (!dirty) go(0);
            }),
        20);
  }

  private void model() {
    backLink();
    heading("", "02 / 对话模型", "QQ 负责收发消息，模型负责想好怎么回答。");
    LinearLayout m = card("连接你的模型服务");
    field(m, "API 基础地址", "base", config.base, false, false);
    note(m, "填写到 /v1，不要附加 /chat/completions。");
    field(m, "API Key", "key", config.key, true, false);
    field(m, "模型名称", "model", config.model, false, false).setHint("服务商提供的模型 ID");
    add(m, button("测试模型连接", false, this::testModel), 16);
    note(m, "测试会发送一句问候，可能产生少量 API 费用。支持 OpenAI 兼容接口与无需 Key 的自建接口。");
    add(
        body,
        button(
            "保存模型 · 返回首页",
            true,
            () -> {
              save();
              if (!dirty) go(0);
            }),
        20);
    note(body, "API Key 经 Android Keystore 加密保存在本机。");
  }

  private void settings() {
    heading("", "设置", "常用操作放在前面，细节按需调整。");
    LinearLayout c = card("机器人设置");
    entry(c, "QQ 登录与连接", config.useLocal ? "本机扫码登录" : "远程 OneBot", 1);
    entry(c, "对话模型", config.model.isBlank() ? "尚未配置" : config.model, 5);
    entry(c, "回复范围与节奏", "群聊、私聊、触发方式和冷却时间", 2);
    entry(c, "会话记忆", "查看历史、调整上下文与清空记忆", 4);
    LinearLayout about = card("Cupbox Android");
    note(about, "版本 " + AppInfo.version(this) + " · 原生 Android 界面\n保留原版 Logo、柔和配色与强制竖屏。");
    add(
        about,
        button(
            "关于 Cupbox · 属性与更新",
            false,
            () -> {
              capture();
              startActivity(new Intent(this, AboutActivity.class));
            }),
        12);
    add(
        about,
        button("管理本机 QQ 运行环境", false, () -> startActivity(new Intent(this, LoginActivity.class))),
        12);
    if (dirty) add(body, button("保存所有修改", true, this::save), 18);
  }

  private void rules() {
    backLink();
    heading("", "03 / 回复范围", "先选择回复对象，再选择触发方式。");
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
            if (!config.scope.equals(a.getItemAtPosition(p))) {
              dirty = true;
              updateDraftBar();
            }
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
    heading("", "选一个聊天搭子", "人格已经内置，选好后保存即可使用。");
    LinearLayout c = card("聊天人格");
    LinearLayout presets = column();
    String[] names = {"温柔搭子", "蓝色大肥鱼", "自定义"};
    String[] modes = {"companion", "whale", "custom"};
    for (int i = 0; i < names.length; i++) {
      final int p = i;
      LinearLayout choice = row();
      choice.setPadding(dp(14), dp(13), dp(14), dp(13));
      boolean selected = config.persona.equals(modes[i]);
      choice.setBackground(shape(selected ? raised : surface, selected ? accent : line, 16));
      TextView marker = text(selected ? "●" : "○", 20, accent);
      choice.addView(marker, new LinearLayout.LayoutParams(dp(36), -2));
      LinearLayout copy = column();
      copy.addView(title(names[i], 15));
      String[] descriptions = {"温柔自然 · 简短接话", "嘴贫有主见 · 原版鲸鱼娘", "自己定义语气与性格"};
      add(copy, text(descriptions[i], 12, dim), 3);
      choice.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
      choice.setOnClickListener(
          v -> {
            capture();
            config.persona = modes[p];
            dirty = true;
            render();
          });
      choice.setContentDescription(names[i] + (selected ? "，已选择" : "，未选择"));
      add(presets, choice, 8);
    }
    add(c, presets, 14);
    if (config.persona.equals("whale")) {
      note(c, "已选择：蓝色大肥鱼 · 原版鲸鱼娘。嘴贫、有主见、平级群友，爱吃白饭，叫她大肥鱼就会炸毛。原版模式不叠加自定义设定。");
      add(
          c,
          button(
              "查看原版鲸鱼娘人设",
              false,
              () ->
                  new MaterialAlertDialogBuilder(this)
                      .setTitle("蓝色大肥鱼 · 原版人设")
                      .setMessage(Persona.WHALE)
                      .setPositiveButton("关闭", null)
                      .show()),
          10);
      toggle(c, "自由演绎", "关闭时严格使用原项目引入的人设；开启后使用鲸鱼娘风格与下方附加设定。", "whaleFreedom", config.whaleFreedom);
    }
    if (!config.persona.equals("whale") || config.whaleFreedom)
      field(
              c,
              config.persona.equals("whale") ? "附加设定（仅自由演绎时生效）" : "自定义设定（最多 8000 字）",
              "prompt",
              config.prompt,
              false,
              true)
          .setMinLines(5);
    if (config.persona.equals("whale"))
      switches
          .get("whaleFreedom")
          .setOnCheckedChangeListener(
              (v, on) -> {
                capture();
                config.whaleFreedom = on;
                dirty = true;
                render();
              });
    field(c, "温度（0–2）", "temperature", "" + config.temperature, false, false);
    field(c, "每次输出 token 上限（64–2048）", "maxTokens", "" + config.maxTokens, false, false);
    note(c, "模型必须支持 temperature 与 max_tokens 参数。部分专用推理接口可能不兼容，可先用连接测试核对。");
    add(body, button("保存人格设置", true, this::save), 20);
  }

  private void memory() {
    backLink();
    heading("", "会话记忆", "只记录成功投递的对话，不保存旁听消息。");
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
              new MaterialAlertDialogBuilder(this)
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
                new MaterialAlertDialogBuilder(this)
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
    LinearLayout about = card("关于 Cupbox Android");
    note(
        about,
        "Android 12 / Material 3 风格，沿用 Cupbox Logo。可在本机准备 Ubuntu、Linux QQ 与 NapCat 后扫码登录，也可连接远程"
            + " OneBot。AI 仍由配置的模型服务提供。非官方 QQ 接入可能受平台限制，建议使用专用账号。");
    add(
        about,
        button(
            "后台运行说明",
            false,
            () ->
                new MaterialAlertDialogBuilder(this)
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
    config.useLocal = flag("useLocal", config.useLocal);
    config.whaleFreedom = flag("whaleFreedom", config.whaleFreedom);
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
      updateDraftBar();
      if (BotService.instance != null) stopService(new Intent(this, BotService.class));
      toast("设置已保存");
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
      if (saved.useLocal && !RuntimeService.loggedIn) {
        startActivity(new Intent(this, LoginActivity.class));
        return;
      }
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
      new MaterialAlertDialogBuilder(this)
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
      if (modelWait != null && modelWait.isShowing()) return;
      modelStarted = SystemClock.elapsedRealtime();
      LinearLayout waiting = column();
      waiting.setPadding(dp(24), dp(16), dp(24), dp(16));
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setIndeterminate(true);
      add(waiting, progress, 8);
      note(waiting, "正在发送测试消息，最多等待 30 秒。");
      modelFact = text(WaitFacts.at(0), 13, dim);
      add(waiting, modelFact, 18);
      modelWait =
          new MaterialAlertDialogBuilder(this)
              .setTitle("正在测试模型")
              .setView(waiting)
              .setNegativeButton("取消测试", (d, w) -> tester.dispatcher().cancelAll())
              .create();
      modelWait.setCancelable(false);
      modelWait.show();
      android.app.Dialog requestDialog = modelWait;
      requestDialog.setOnDismissListener(
          d -> {
            if (modelWait == requestDialog) modelFact = null;
          });
      checks.execute(
          () -> {
            try (Response r =
                tester
                    .newCall(ModelClient.request(draft, new JSONArray(), "请只回复：连接成功"))
                    .execute()) {
              ModelClient.answer(r);
              runOnUiThread(
                  () -> {
                    if (isDestroyed() || modelWait != requestDialog || !requestDialog.isShowing())
                      return;
                    modelWait.dismiss();
                    modelFact = null;
                    toast("模型连接成功，可以保存了");
                  });
            } catch (Exception e) {
              runOnUiThread(
                  () -> {
                    if (isDestroyed() || modelWait != requestDialog || !requestDialog.isShowing())
                      return;
                    modelWait.dismiss();
                    modelFact = null;
                    error(
                        "模型测试失败："
                            + (e.getMessage() != null && e.getMessage().startsWith("模型")
                                ? e.getMessage()
                                : "请检查网络、地址、密钥和模型名称"));
                  });
            }
          });
    } catch (Exception e) {
      error(e.getMessage());
    }
  }

  private void showIntro() {
    if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
    android.app.Dialog intro =
        new android.app.Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout splash = column();
    splash.setGravity(Gravity.CENTER);
    splash.setBackgroundColor(bg);
    BrandView mark = new BrandView(this);
    splash.addView(mark, new LinearLayout.LayoutParams(dp(180), dp(180)));
    TextView name = title("Cupbox", 30);
    add(splash, name, 20);
    name.setGravity(Gravity.CENTER);
    TextView tagline = text("把陪伴，装进口袋。", 14, dim);
    add(splash, tagline, 12);
    tagline.setGravity(Gravity.CENTER);
    intro.setContentView(splash);
    intro.setCancelable(false);
    if (intro.getWindow() != null) {
      intro.getWindow().setStatusBarColor(bg);
      intro.getWindow().setNavigationBarColor(bg);
    }
    intro.show();
    intro.getWindow().setLayout(-1, -1);
    intro
        .getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    mark.setScaleX(.7f);
    mark.setScaleY(.7f);
    mark.setAlpha(0);
    mark.animate()
        .scaleX(1)
        .scaleY(1)
        .alpha(1)
        .rotation(6)
        .setDuration(550)
        .withEndAction(() -> mark.animate().rotation(0).setDuration(180).start())
        .start();
    handler.postDelayed(
        () -> {
          if (intro.isShowing()) intro.dismiss();
        },
        850);
    getLifecycle()
        .addObserver(
            new androidx.lifecycle.DefaultLifecycleObserver() {
              @Override
              public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                intro.dismiss();
              }
            });
  }

  private void error(String value) {
    new MaterialAlertDialogBuilder(this)
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
    if (page != 0) {
      go(0);
      return;
    }
    if (dirty)
      new MaterialAlertDialogBuilder(this)
          .setTitle("还有未保存的设置")
          .setMessage("离开后本次草稿将丢失。")
          .setNegativeButton("继续编辑", null)
          .setPositiveButton("放弃并离开", (d, w) -> finish())
          .show();
    else super.onBackPressed();
  }
}
