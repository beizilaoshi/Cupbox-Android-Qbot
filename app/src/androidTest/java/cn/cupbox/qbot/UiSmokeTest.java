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
      for (int i = 0; i < g.getChildCount(); i++) {
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

  @Test
  public void fivePagesAndBothThemes() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(
          a ->
              a.setRequestedOrientation(
                  android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      Thread.sleep(500);
      String[] tabs = {"概览", "连接", "规则", "人格", "记忆"};
      String[] titles = {"让聊天，有个回应。", "连上，才有下一句。", "有分寸地接话。", "用你的方式，交流。", "记得住，也管得住。"};
      for (int i = 0; i < tabs.length; i++) {
        final int pos = i;
        scenario.onActivity(
            a -> {
              TextView target = find(a.getWindow().getDecorView(), tabs[pos]);
              assertNotNull(target);
              target.performClick();
              assertNotNull(find(a.getWindow().getDecorView(), titles[pos]));
            });
        shot("page-" + i + "-light");
      }
      scenario.onActivity(
          a -> {
            find(a.getWindow().getDecorView(), "概览").performClick();
            find(a.getWindow().getDecorView(), "深色").performClick();
          });
      shot("overview-dark");
      scenario.onActivity(a -> find(a.getWindow().getDecorView(), "浅色").performClick());
    }
  }
}
