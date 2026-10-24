package cn.cupbox.qbot;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;

/** Runs in a disposable Android device, with local fake OneBot and model servers only. */
@SuppressWarnings("deprecation")
public class ServiceIntegrationTest {
  private Context app;
  private Store store;
  private final Handler main = new Handler(Looper.getMainLooper());

  @Before
  public void setUp() throws Exception {
    app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    store = new Store(app);
    app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  @After
  public void tearDown() throws Exception {
    app.stopService(new Intent(app, BotService.class));
    waitFor(() -> BotService.instance == null);
    store.clear();
    app.getSharedPreferences("vault", 0).edit().clear().commit();
    store.close();
  }

  private void waitFor(BooleanSupplier check) throws Exception {
    long until = System.currentTimeMillis() + 15000;
    while (!check.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(100);
    assertTrue("Timed out waiting for state", check.getAsBoolean());
  }

  @Test
  public void testEncryptedConfigAndBoundedMemory() throws Exception {
    Config c = new Config();
    c.key = "secret-must-not-be-plaintext";
    store.save(c);
    assertEquals(c.key, store.load().key);
    assertFalse(app.getSharedPreferences("vault", 0).getString("config", "").contains(c.key));
    for (int i = 0; i < 35; i++)
      for (int j = 0; j < 12; j++) store.remember("session" + i, "input" + j, "output" + j);
    assertEquals(20, store.history("session34", 100).length());
    assertEquals(0, store.history("session0", 20).length());
    assertEquals(0, store.history("session34", 0).length());
  }

  @Test
  public void testOneBotToModelDeliveryPauseAndFailure() throws Exception {
    CountDownLatch login = new CountDownLatch(1), send = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<WebSocket> peer =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Throwable> serverError =
        new java.util.concurrent.atomic.AtomicReference<>();
    try (MockWebServer onebot = new MockWebServer();
        MockWebServer model = new MockWebServer()) {
      onebot.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response r) {
                      peer.set(ws);
                    }

                    @Override
                    public void onMessage(WebSocket ws, String raw) {
                      try {
                        JSONObject request = new JSONObject(raw);
                        String action = request.optString("action");
                        JSONObject response =
                            new JSONObject()
                                .put("status", "ok")
                                .put("retcode", 0)
                                .put("echo", request.get("echo"));
                        if (action.equals("get_login_info")) {
                          response.put("data", new JSONObject().put("user_id", 12345));
                          ws.send(response.toString());
                          login.countDown();
                        } else if (action.equals("send_group_msg")) {
                          assertEquals(
                              "88888", request.getJSONObject("params").optString("group_id"));
                          assertEquals(
                              "你好，测试成功。",
                              request
                                  .getJSONObject("params")
                                  .getJSONArray("message")
                                  .getJSONObject(0)
                                  .getJSONObject("data")
                                  .getString("text"));
                          ws.send(
                              response
                                  .put("data", new JSONObject().put("message_id", 2))
                                  .toString());
                          send.countDown();
                        }
                      } catch (Throwable t) {
                        serverError.set(t);
                        send.countDown();
                      }
                    }
                  }));
      model.enqueue(
          new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"你好，测试成功。\"}}]}"));
      onebot.start();
      model.start();
      Config c = new Config();
      c.ws = onebot.url("/").toString().replace("http:", "ws:");
      c.base = model.url("/v1").toString();
      c.model = "mock";
      c.groups = "88888";
      c.cooldown = 1;
      store.save(c);
      app.startForegroundService(new Intent(app, BotService.class));
      assertTrue(login.await(15, TimeUnit.SECONDS));
      waitFor(() -> BotService.online);
      assertFalse(BotService.reply);
      main.post(() -> BotService.instance.enable());
      waitFor(() -> BotService.reply);
      JSONObject event =
          new JSONObject()
              .put("post_type", "message")
              .put("message_type", "group")
              .put("self_id", 12345)
              .put("user_id", 54321)
              .put("group_id", 88888)
              .put("message_id", 101)
              .put(
                  "message",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("type", "at")
                              .put("data", new JSONObject().put("qq", "12345")))
                      .put(
                          new JSONObject()
                              .put("type", "text")
                              .put("data", new JSONObject().put("text", "你好"))));
      int before = BotService.sent;
      peer.get().send(event.toString());
      assertTrue(send.await(15, TimeUnit.SECONDS));
      if (serverError.get() != null) throw new AssertionError(serverError.get());
      waitFor(() -> BotService.sent == before + 1);
      assertEquals(2, store.history("12345:g:88888", 20).length());
      RecordedRequest req = model.takeRequest(5, TimeUnit.SECONDS);
      assertNotNull(req);
      assertEquals("/v1/chat/completions", req.getPath());
      peer.get().send(event.toString());
      Thread.sleep(300);
      assertEquals(1, model.getRequestCount());
      main.post(() -> BotService.instance.pause());
      waitFor(() -> !BotService.reply);
      assertEquals(before + 1, BotService.sent);
    }
  }
}
