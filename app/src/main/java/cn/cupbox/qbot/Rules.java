package cn.cupbox.qbot;

import java.util.*;
import org.json.*;

public final class Rules {
  public record Message(
      String account,
      String conversation,
      String user,
      String group,
      String text,
      boolean at,
      String id) {}

  public static Message parse(JSONObject j) {
    if (!j.optString("post_type").equals("message")) return null;
    String type = j.optString("message_type"),
        user = j.optString("user_id"),
        group = j.optString("group_id"),
        self = j.optString("self_id");
    if (!Set.of("group", "private").contains(type)
        || user.isEmpty()
        || self.isEmpty()
        || user.equals(self)
        || type.equals("group") && group.isEmpty()) return null;
    // Only real structured message segments can trigger mentions. Never interpret raw CQ text.
    JSONArray parts = j.optJSONArray("message");
    if (parts == null) return null;
    boolean at = false;
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < Math.min(parts.length(), 128); i++) {
      JSONObject p = parts.optJSONObject(i);
      if (p == null) continue;
      JSONObject d = p.optJSONObject("data");
      if (d == null) continue;
      if (p.optString("type").equals("text")) text.append(d.optString("text"));
      if (p.optString("type").equals("at") && d.optString("qq").equals(self)) at = true;
      if (text.length() > 4000) break;
    }
    String t = text.toString().trim();
    if (t.isEmpty()) return null;
    if (t.length() > 4000) t = t.substring(0, 4000);
    return new Message(
        self,
        self + ":" + (type.equals("group") ? "g:" + group : "p:" + user),
        user,
        type.equals("group") ? group : "",
        t,
        at,
        j.optString("message_id"));
  }

  /** One separator set for every list the user can type: commas, 、；and whitespace. */
  private static final java.util.regex.Pattern SEPARATOR =
      java.util.regex.Pattern.compile("[，,、;；\\s]+");

  public static String[] split(String value) {
    return SEPARATOR.split(value == null ? "" : value.trim());
  }

  public static boolean allowed(Config c, Message m) {
    if (m == null) return false;
    if (m.group().isEmpty()) return c.privateReply;
    if (!c.groupAllowed(m.group())) return false;
    if (c.mention && m.at()) return true;
    String t = m.text().toLowerCase(Locale.ROOT);
    for (String kw : split(c.keywords)) {
      String needle = kw.trim().toLowerCase(Locale.ROOT);
      if (!needle.isEmpty() && t.contains(needle)) return true;
    }
    return false;
  }
}
