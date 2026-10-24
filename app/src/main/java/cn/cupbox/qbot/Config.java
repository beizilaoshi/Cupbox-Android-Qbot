package cn.cupbox.qbot;

import java.net.URI;
import java.util.*;
import org.json.*;

public final class Config {
  public String ws = "",
      token = "",
      base = "https://api.openai.com/v1",
      key = "",
      model = "",
      prompt = "你是温柔、有主见的聊天搭子。用简短自然的中文接住话题，不假装真人，不编造事实。",
      groups = "",
      keywords = "",
      scope = "白名单";
  public boolean privateReply = false, mention = true, memory = true, replyAt = false;
  public int cooldown = 5, perMinute = 10, context = 12, maxTokens = 512;
  public double temperature = 0.8;

  public JSONObject json() throws JSONException {
    return new JSONObject()
        .put("ws", ws)
        .put("token", token)
        .put("base", base)
        .put("key", key)
        .put("model", model)
        .put("prompt", prompt)
        .put("groups", groups)
        .put("keywords", keywords)
        .put("scope", scope)
        .put("privateReply", privateReply)
        .put("mention", mention)
        .put("memory", memory)
        .put("replyAt", replyAt)
        .put("cooldown", cooldown)
        .put("perMinute", perMinute)
        .put("context", context)
        .put("maxTokens", maxTokens)
        .put("temperature", temperature);
  }

  public static Config from(JSONObject j) {
    Config c = new Config();
    c.ws = j.optString("ws", c.ws);
    c.token = j.optString("token", "");
    c.base = j.optString("base", c.base);
    c.key = j.optString("key", "");
    c.model = j.optString("model", "");
    c.prompt = j.optString("prompt", c.prompt);
    c.groups = j.optString("groups", "");
    c.keywords = j.optString("keywords", "");
    c.scope = j.optString("scope", c.scope);
    c.privateReply = j.optBoolean("privateReply");
    c.mention = j.optBoolean("mention", true);
    c.memory = j.optBoolean("memory", true);
    c.replyAt = j.optBoolean("replyAt");
    c.cooldown = clamp(j.optInt("cooldown", 5), 1, 300);
    c.perMinute = clamp(j.optInt("perMinute", 10), 1, 60);
    c.context = clamp(j.optInt("context", 12), 0, 20);
    c.maxTokens = clamp(j.optInt("maxTokens", 512), 64, 2048);
    c.temperature = Math.max(0, Math.min(2, j.optDouble("temperature", .8)));
    return c;
  }

  static int clamp(int n, int min, int max) {
    return Math.max(min, Math.min(max, n));
  }

  public void validate(boolean connect) {
    if (!Set.of("白名单", "黑名单", "所有群").contains(scope)) throw new IllegalArgumentException("群聊范围无效");
    if (!groups.trim().isEmpty())
      for (String id : groups.trim().split("[，,\\s]+"))
        if (!id.matches("[0-9]{4,20}")) throw new IllegalArgumentException("群号只能填写数字，用逗号或换行分隔");
    checkUrl(base, Set.of("http", "https"), "模型地址");
    if (!ws.isBlank() || connect) checkUrl(ws, Set.of("ws", "wss"), "OneBot 地址");
    if (connect && model.isBlank()) throw new IllegalArgumentException("请先填写模型名称");
    if (prompt.length() > 8000) throw new IllegalArgumentException("人设最多 8000 字");
  }

  static void checkUrl(String value, Set<String> schemes, String label) {
    try {
      URI u = URI.create(value);
      if (!schemes.contains(u.getScheme())
          || u.getHost() == null
          || u.getUserInfo() != null
          || u.getFragment() != null
          || u.getRawQuery() != null) throw new Exception();
    } catch (Exception e) {
      throw new IllegalArgumentException(label + "格式不正确，不支持内嵌密码或查询参数");
    }
  }

  public boolean groupAllowed(String group) {
    Set<String> ids = new HashSet<>(Arrays.asList(groups.trim().split("[，,\\s]+")));
    return scope.equals("所有群")
        || (scope.equals("白名单") ? ids.contains(group) : !ids.contains(group));
  }
}
