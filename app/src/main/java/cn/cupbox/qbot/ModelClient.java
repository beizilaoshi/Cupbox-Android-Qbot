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
    String value =
        root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content", "")
            .trim();
    value = value.replaceAll("(?s)<think>.*?</think>", "").trim();
    value = Persona.clean(value);
    if (value.isEmpty() || value.startsWith("<think>")) throw new IOException("模型没有返回可发送的正文");
    return value.length() > 1800 ? value.substring(0, 1800) + "…" : value;
  }
}
