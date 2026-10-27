package cn.cupbox.qbot;

import static org.junit.Assert.*;

import android.app.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.Test;

public class UiSmokeTest {
  private TextView find(View v, String label) {
    if (v instanceof TextView t && label.contentEquals(t.getText())) return t;
    if (v instanceof ViewGroup g)
      // Navigation sits after the content; prefer it when a page heading has the same label.
      for (int i = g.getChildCount() - 1; i >= 0; i--) {
        TextView found = find(g.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private void shot(String name) throws Exception {
    Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    inst.waitForIdleSync();
    Thread.sleep(200);
    Bitmap b = inst.getUiAutomation().takeScreenshot();
    File folder = new File(inst.getTargetContext().getExternalFilesDir(null), "screenshots");
    assertTrue(folder.exists() || folder.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
  }

  private void click(TextView view) {
    View target = view;
    while (!target.isClickable() && target.getParent() instanceof View)
      target = (View) target.getParent();
    if (target instanceof CompoundButton toggle) {
      boolean previous = toggle.isChecked();
      target.performClick();
      assertNotEquals(previous, toggle.isChecked());
    } else assertTrue(target.performClick());
  }

  @Test
  public void guidedPagesAndBothThemes() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(
          a ->
              assertEquals(
                  android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                  a.getRequestedOrientation()));
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      Thread.sleep(1100);
      scenario.onActivity(
          a ->
              assertEquals(
                  android.content.res.Configuration.ORIENTATION_PORTRAIT,
                  a.getResources().getConfiguration().orientation));
      String[] tabs = {"首页", "人格", "设置"};
      String[] titles = {"今天，也有回应。", "选一个聊天搭子", "机器人设置"};
      for (int i = 0; i < tabs.length; i++) {
        final int pos = i;
        scenario.onActivity(
            a -> {
              click(find(a.getWindow().getDecorView(), tabs[pos]));
              assertNotNull(find(a.getWindow().getDecorView(), titles[pos]));
            });
        shot("page-" + i + "-light");
      }
      String[] settings = {"QQ 登录与连接", "对话模型", "回复范围与节奏", "会话记忆"};
      String[] detailTitles = {"01 / 登录 QQ", "02 / 对话模型", "03 / 回复范围", "会话记忆"};
      for (int i = 0; i < settings.length; i++) {
        final int pos = i;
        scenario.onActivity(
            a -> {
              click(find(a.getWindow().getDecorView(), "设置"));
              click(find(a.getWindow().getDecorView(), settings[pos]));
              assertNotNull(find(a.getWindow().getDecorView(), detailTitles[pos]));
            });
        shot("detail-" + i);
      }
      scenario.onActivity(
          a -> {
            click(find(a.getWindow().getDecorView(), "人格"));
            click(find(a.getWindow().getDecorView(), "蓝色大肥鱼"));
            assertNotNull(find(a.getWindow().getDecorView(), "自由演绎"));
            assertNull(find(a.getWindow().getDecorView(), "附加设定（仅自由演绎时生效）"));
            click(find(a.getWindow().getDecorView(), "自由演绎"));
            assertNotNull(find(a.getWindow().getDecorView(), "附加设定（仅自由演绎时生效）"));
            click(find(a.getWindow().getDecorView(), "自由演绎"));
            assertEquals(
                View.VISIBLE,
                find(a.getWindow().getDecorView(), "保存修改").getParent() instanceof View parent
                    ? parent.getVisibility()
                    : View.GONE);
          });
      shot("whale-persona");
      scenario.onActivity(
          a -> {
            click(find(a.getWindow().getDecorView(), "首页"));
            click(find(a.getWindow().getDecorView(), "深色"));
          });
      shot("overview-dark");
      scenario.onActivity(a -> click(find(a.getWindow().getDecorView(), "浅色")));
    }
  }

  @Test
  public void aboutShowsVersionPropertiesContactAndBundledLicenses() throws Exception {
    try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
      scenario.onActivity(
          a -> {
            assertEquals(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                a.getRequestedOrientation());
            assertEquals("1.0.1", AppInfo.version(a));
            assertEquals(5, AppInfo.code(a));
            for (String label :
                new String[] {
                  "关于 Cupbox", "版本与更新", "应用属性", "1.0.1", "638500178", "检查更新", "开源、素材与致谢"
                }) assertNotNull(label, find(a.getWindow().getDecorView(), label));
            click(find(a.getWindow().getDecorView(), "复制 QQ 群号"));
            assertEquals(
                "638500178",
                a.getSystemService(android.content.ClipboardManager.class)
                    .getPrimaryClip()
                    .getItemAt(0)
                    .getText()
                    .toString());
          });
      shot("about-light");
      scenario.onActivity(
          a -> {
            find(a.getWindow().getDecorView(), "应用属性")
                .requestRectangleOnScreen(new android.graphics.Rect(0, 0, 100, 500));
          });
      shot("about-properties");
    }
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    for (String asset :
        new String[] {
          "licenses/Cupbox-LICENSE.txt",
          "licenses/NOTICES.txt",
          "licenses/UBUNTU-BASE.txt",
          "licenses/GPL-2.0.txt",
          "licenses/GPL-3.0.txt",
          "whale/LICENSE"
        })
      try (InputStream in = context.getAssets().open(asset)) {
        assertTrue(in.read() != -1);
      }
    context.getSharedPreferences("MainActivity", 0).edit().putBoolean("dark", true).commit();
    try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
      shot("about-dark");
    } finally {
      context.getSharedPreferences("MainActivity", 0).edit().putBoolean("dark", false).commit();
    }
  }

  @Test
  public void loginPageIsPortrait() throws Exception {
    try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
      scenario.onActivity(
          a -> {
            assertEquals(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                a.getRequestedOrientation());
            assertNotNull(find(a.getWindow().getDecorView(), "准备并启动本机 QQ"));
          });
    }
  }
}
