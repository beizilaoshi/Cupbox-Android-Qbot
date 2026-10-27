package cn.cupbox.qbot;

import java.io.*;
import okhttp3.*;
import org.json.*;

public final class ModelClient {
  public static Request request(Config c, JSONArray history, String text) throws JSONException {
    JSONArray messages =
        new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", Persona.prompt(c)));
    for (int i = 0; i < history.length(); i++) messages.put(history.get(i));
    messages.put(new JSONObject().put("role", "user").put("content", text));
    JSONObject body =
        new JSONObject()
            .put("model", c.model)
            .put("messages", messages)
            .put("temperature", c.temperature)
            .put("max_tokens", c.maxTokens)
            .put("stream", false);
    Request.Builder b =
        new Request.Builder()
            .url(c.base.replaceAll("/+$", "") + "/chat/completions")
            .post(
                RequestBody.create(
                    body.toString(), MediaType.get("application/json; charset=utf-8")));
    if (!c.key.isBlank()) b.header("Authorization", "Bearer " + c.key);
    return b.build();
  }

  public static String answer(Response r) throws Exception {
    if (!r.isSuccessful()) throw new IOException("模型 HTTP " + r.code());
    if (r.body() == null) throw new IOException("模型响应为空");
    // Read an upper-bounded response, even if a gateway omits Content-Length.
    InputStream in = r.body().byteStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[4096];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > 256 * 1024) throw new IOException("模型响应超过 256 KB");
      out.write(b, 0, n);
    }
    JSONObject root = new JSONObject(out.toString("UTF-8"));
    JSONArray choices = root.optJSONArray("choices");
    if (choices == null || choices.length() == 0) throw new IOException("模型没有返回任何回复");
    JSONObject first = choices.optJSONObject(0);
    JSONObject message = first == null ? null : first.optJSONObject("message");
    if (message == null) throw new IOException("模型响应缺少 message 字段");
    String value = message.optString("content", "").trim();
    // 推理模型可能输出未闭合的思考段，或把思考段放在正文前面。
    value = value.replaceAll("(?s)<think>.*?</think>", "");
    value = value.replaceAll("(?s)<think>.*$", "");
    value = value.replaceAll("(?s)^.*?</think>", "");
    value = Persona.clean(value);
    if (value.isEmpty()) throw new IOException("模型只返回了表情或思考内容，没有可发送的正文");
    return value.length() > 1800 ? value.substring(0, 1800) + "…" : value;
  }
}
