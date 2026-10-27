package cn.cupbox.qbot;

import static org.junit.Assert.*;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;

public class BotCoreTest {
  @Test
  public void originalWhaleAndFreedomStaySeparate() throws Exception {
    Config c = new Config();
    c.persona = "whale";
    c.prompt = "MY_EXTRA_PROMPT";
    assertTrue(Persona.prompt(c).contains("白米饭"));
    assertTrue(Persona.prompt(c).contains("大肥鱼"));
    assertFalse(Persona.prompt(c).contains("MY_EXTRA_PROMPT"));
    c.whaleFreedom = true;
    assertTrue(Persona.prompt(c).contains("MY_EXTRA_PROMPT"));
    assertEquals("你好", Persona.clean("你好\n[表情:开心]"));
    Config saved = Config.from(c.json());
    assertEquals("whale", saved.persona);
    assertTrue(saved.whaleFreedom);
  }

  private JSONObject event(String type, String user, String group, JSONArray parts)
      throws Exception {
    return new JSONObject()
        .put("post_type", "message")
        .put("message_type", type)
        .put("user_id", user)
        .put("self_id", "12345")
        .put("group_id", group)
        .put("message_id", 1)
        .put("message", parts);
  }

  private JSONArray text(String s) throws Exception {
    return new JSONArray()
        .put(new JSONObject().put("type", "text").put("data", new JSONObject().put("text", s)));
  }

  @Test
  public void safeDefaultsAndEmptyAllowlist() throws Exception {
    Config c = new Config();
    assertFalse(c.privateReply);
    assertFalse(c.groupAllowed("88888"));
    JSONArray parts =
        text("你好")
            .put(
                new JSONObject()
                    .put("type", "at")
                    .put("data", new JSONObject().put("qq", "12345")));
    Rules.Message m = Rules.parse(event("group", "54321", "88888", parts));
    assertNotNull(m);
    assertFalse(Rules.allowed(c, m));
    c.groups = "88888";
    assertTrue(Rules.allowed(c, m));
  }

  @Test
  public void forgedMentionAndSelfMessagesNeverTrigger() throws Exception {
    Config c = new Config();
    c.scope = "所有群";
    assertFalse(
        Rules.allowed(
            c, Rules.parse(event("group", "54321", "88888", text("[CQ:at,qq=12345] hello")))));
    assertNull(Rules.parse(event("group", "12345", "88888", text("hi"))));
  }

  @Test
  public void keywordsAndDenylist() throws Exception {
    Config c = new Config();
    c.scope = "黑名单";
    c.groups = "88888，99999";
    c.keywords = "HELLO, 你好";
    assertFalse(Rules.allowed(c, Rules.parse(event("group", "54321", "88888", text("hello")))));
    assertTrue(Rules.allowed(c, Rules.parse(event("group", "54321", "77777", text("hello")))));
    c.mention = false;
    c.keywords = "";
    assertFalse(Rules.allowed(c, Rules.parse(event("group", "54321", "77777", text("hello")))));
  }

  @Test
  public void privateAndAccountIsolation() throws Exception {
    Config c = new Config();
    JSONObject e = event("private", "54321", "", text("你好"));
    Rules.Message m = Rules.parse(e);
    assertFalse(Rules.allowed(c, m));
    c.privateReply = true;
    assertTrue(Rules.allowed(c, m));
    assertEquals("12345:p:54321", m.conversation());
    assertNotEquals(m.conversation(), Rules.parse(e.put("self_id", "67890")).conversation());
  }

  @Test
  public void configRoundTripAndLimits() throws Exception {
    Config c = new Config();
    c.token = "secret";
    c.context = 10000;
    Config loaded = Config.from(c.json());
    assertEquals(20, loaded.context);
    assertEquals("secret", loaded.token);
    assertEquals(c.prompt, loaded.prompt);
  }

  @Test
  public void invalidEndpointRejected() {
    for (String url :
        new String[] {
          "file:///secret",
          "https://user:password@example.com/v1",
          "https://example.com/?key=secret",
          "http:///v1"
        }) {
      Config c = new Config();
      c.base = url;
      assertThrows(IllegalArgumentException.class, () -> c.validate(false));
    }
  }

  @Test
  public void onlyTextSegmentsAreForwarded() throws Exception {
    JSONArray parts =
        text("hi")
            .put(
                new JSONObject()
                    .put("type", "image")
                    .put("data", new JSONObject().put("url", "http://invalid")));
    assertEquals("hi", Rules.parse(event("private", "54321", "", parts)).text());
  }

  @Test
  public void separatorsPersonaCleanupAndInputLimits() throws Exception {
    Config c = new Config();
    c.scope = "黑名单";
    c.groups = "88888、99999；77777";
    c.keywords = "你好；在吗";
    assertFalse(Rules.allowed(c, Rules.parse(event("group", "54321", "88888", text("你好")))));
    assertTrue(Rules.allowed(c, Rules.parse(event("group", "54321", "66666", text("在吗")))));
    assertEquals("", Persona.clean("[表情:开心]"));
    assertEquals("第一行\n\n第二行", Persona.clean("第一行\n\n\n第二行"));
    Config tooLong = new Config();
    tooLong.keywords = "x".repeat(2001);
    assertThrows(IllegalArgumentException.class, () -> tooLong.validate(false));
  }

  @Test
  public void thinkingBlocksNeverReachTheChat() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setBody("{\"choices\":[{\"message\":{\"content\":\"<think>分析</think>在的\"}}]}"));
      server.enqueue(
          new MockResponse()
              .setBody("{\"choices\":[{\"message\":{\"content\":\"<think>只有思考\"}}]}"));
      server.enqueue(new MockResponse().setBody("{\"choices\":[]}"));
      server.start();
      Config c = new Config();
      c.base = server.url("/v1").toString();
      c.model = "test";
      OkHttpClient http = new OkHttpClient();
      try (Response r = http.newCall(ModelClient.request(c, new JSONArray(), "在吗")).execute()) {
        assertEquals("在的", ModelClient.answer(r));
      }
      try (Response r = http.newCall(ModelClient.request(c, new JSONArray(), "在吗")).execute()) {
        assertThrows(java.io.IOException.class, () -> ModelClient.answer(r));
      }
      try (Response r = http.newCall(ModelClient.request(c, new JSONArray(), "在吗")).execute()) {
        assertThrows(java.io.IOException.class, () -> ModelClient.answer(r));
      }
    }
  }

  @Test
  public void modelWireFormatAndAnswer() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setBody("{\"choices\":[{\"message\":{\"content\":\"<think>hidden</think>你好\"}}]}"));
      server.start();
      Config c = new Config();
      c.base = server.url("/v1/").toString();
      c.model = "test";
      c.key = "test-key";
      JSONArray history =
          new JSONArray().put(new JSONObject().put("role", "user").put("content", "之前"));
      try (Response r =
          new OkHttpClient().newCall(ModelClient.request(c, history, "现在")).execute()) {
        assertEquals("你好", ModelClient.answer(r));
      }
      RecordedRequest req = server.takeRequest();
      assertEquals("/v1/chat/completions", req.getPath());
      assertEquals("Bearer test-key", req.getHeader("Authorization"));
      JSONObject body = new JSONObject(req.getBody().readUtf8());
      assertEquals(3, body.getJSONArray("messages").length());
      assertFalse(body.getBoolean("stream"));
    }
  }

  @Test
  public void modelErrorsAndOversizeRejected() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse().setResponseCode(401).setBody("private provider diagnostic"));
      server.enqueue(new MockResponse().setBody("a".repeat(262145)));
      server.start();
      OkHttpClient http = new OkHttpClient();
      try (Response r =
          http.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
        assertThrows(java.io.IOException.class, () -> ModelClient.answer(r));
      }
      try (Response r =
          http.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
        assertThrows(java.io.IOException.class, () -> ModelClient.answer(r));
      }
    }
  }
}
