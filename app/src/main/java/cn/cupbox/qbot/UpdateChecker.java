package cn.cupbox.qbot;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import okhttp3.*;
import org.json.*;

/** Manual, read-only stable-release checks. Independent of the bot's API client and secrets. */
final class UpdateChecker {
  enum State {
    NEWER,
    CURRENT,
    LOCAL_NEWER,
    NO_RELEASE
  }

  record Result(
      State state,
      String version,
      String title,
      String notes,
      String url,
      boolean hasApk,
      boolean detailsAvailable) {}

  static final String PROJECT = "https://github.com/beizilaoshi/Cupbox-Android-Qbot";
  private static final Pattern VERSION =
      Pattern.compile(
          "^[vV]?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:\\+[0-9A-Za-z.-]+)?$");
  private final OkHttpClient client;
  private final HttpUrl api;
  private final HttpUrl web;
  private long deadline;
  private volatile Call active;
  private volatile boolean cancelled;

  UpdateChecker() {
    this(
        new OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        HttpUrl.get("https://api.github.com/"));
  }

  UpdateChecker(OkHttpClient client, HttpUrl api) {
    this(client, api, HttpUrl.get("https://github.com/"));
  }

  UpdateChecker(OkHttpClient client, HttpUrl api, HttpUrl web) {
    this.client = client;
    this.api = api;
    this.web = web;
  }

  static int compare(String a, String b) throws IOException {
    Matcher left = VERSION.matcher(a.trim()), right = VERSION.matcher(b.trim());
    if (!left.matches() || !right.matches())
      throw new IOException("无法识别发布版本号，请前往仓库确认（应为 v1.2.3 格式）。");
    for (int i = 1; i <= 3; i++) {
      int order =
          Integer.compare(Integer.parseInt(left.group(i)), Integer.parseInt(right.group(i)));
      if (order != 0) return order;
    }
    return 0;
  }

  Result check(String current) throws IOException {
    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
    try (Response response =
        request("repos/beizilaoshi/Cupbox-Android-Qbot/releases/latest", current)) {
      if (response.code() == 403 || response.code() == 429) return checkWeb(current);
      if (response.code() == 404) {
        // GitHub also returns 404 for a missing/private repository, so verify access first.
        try (Response repository = request("repos/beizilaoshi/Cupbox-Android-Qbot", current)) {
          if (repository.code() == 403 || repository.code() == 429) return checkWeb(current);
          requireSuccess(repository);
          return new Result(
              State.NO_RELEASE,
              "",
              "尚未发布正式版本",
              "仓库可以访问，但还没有公开的正式 Release。当前安装版本为 " + current + "；以后维护者发布正式版本后，即可在这里检查。",
              PROJECT + "/releases",
              false,
              false);
        }
      }
      requireSuccess(response);
      return parse(readBounded(response), current);
    }
  }

  private Response request(String path, String current) throws IOException {
    if (cancelled) throw new InterruptedIOException("检查已取消");
    Request request =
        new Request.Builder()
            .url(api.resolve(path))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Cupbox-Android/" + current)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build();
    return execute(request);
  }

  private Response execute(Request request) throws IOException {
    if (cancelled) throw new InterruptedIOException("检查已取消");
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new java.net.SocketTimeoutException("检查超时");
    active = client.newCall(request);
    long perCall =
        TimeUnit.MILLISECONDS.toNanos(
            client.callTimeoutMillis() > 0 ? client.callTimeoutMillis() : 20000);
    active.timeout().timeout(Math.min(perCall, remaining), TimeUnit.NANOSECONDS);
    if (cancelled) active.cancel();
    return active.execute();
  }

  private Result checkWeb(String current) throws IOException {
    HttpUrl url = web.resolve("beizilaoshi/Cupbox-Android-Qbot/releases/latest");
    for (int hop = 0; hop < 4; hop++) {
      try (Response response =
          execute(
              new Request.Builder()
                  .url(url)
                  .header("User-Agent", "Cupbox-Android/" + current)
                  .header("Accept", "text/html")
                  .build())) {
        if (response.code() == 301
            || response.code() == 302
            || response.code() == 303
            || response.code() == 307
            || response.code() == 308) {
          String location = response.header("Location");
          HttpUrl next = location == null ? null : url.resolve(location);
          if (next == null
              || !next.scheme().equals(web.scheme())
              || !next.host().equals(web.host())
              || next.port() != web.port()
              || !next.encodedUsername().isEmpty()
              || !next.encodedPassword().isEmpty()) throw new IOException("备用更新地址异常，请前往项目仓库确认。");
          String path = next.encodedPath(),
              prefix = "/beizilaoshi/Cupbox-Android-Qbot/releases/tag/";
          if (path.startsWith(prefix)) {
            String tag = next.pathSegments().get(next.pathSegments().size() - 1);
            int order = compare(tag, current);
            return new Result(
                order > 0 ? State.NEWER : order == 0 ? State.CURRENT : State.LOCAL_NEWER,
                tag,
                "Cupbox " + tag,
                "GitHub 接口暂时限流，已通过同一仓库的官方最新发行页确认版本。完整更新说明和安装包请在发行页查看。",
                "https://github.com" + path,
                false,
                false);
          }
          if (!path.equals("/beizilaoshi/Cupbox-Android-Qbot/releases"))
            throw new IOException("备用更新地址与指定仓库不符，请前往项目主页确认。");
          url = next;
          continue;
        }
        requireSuccess(response);
        String html = readBounded(response);
        if (html.contains("any releases here"))
          return new Result(
              State.NO_RELEASE,
              "",
              "尚未发布正式版本",
              "GitHub 接口暂时限流，已通过仓库公开发行页确认暂无发布。当前安装版本为 " + current + "；以后维护者发布正式版本后，即可在这里检查。",
              PROJECT + "/releases",
              false,
              false);
        throw new IOException("GitHub 接口受限，备用发行页暂时无法确认版本。请稍后重试，或直接打开仓库发行页。");
      }
    }
    throw new IOException("备用发行页跳转过多，请直接打开项目仓库确认。");
  }

  private static void requireSuccess(Response response) throws IOException {
    if (response.isSuccessful()) return;
    if (response.code() == 403 || response.code() == 429)
      throw new IOException("GitHub 暂时限制了请求，请稍后重试；也可直接打开仓库发行页。");
    if (response.code() == 404) throw new IOException("暂时无法访问指定仓库，请检查网络或仓库可见性。");
    throw new IOException("GitHub 检查失败（HTTP " + response.code() + "），请稍后重试。");
  }

  private static String readBounded(Response response) throws IOException {
    if (response.body() == null) throw new IOException("GitHub 没有返回版本信息。");
    try (InputStream input = response.body().byteStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = input.read(buffer)) != -1) {
        if (output.size() + n > 512 * 1024) throw new IOException("版本信息过大，请前往仓库查看。");
        output.write(buffer, 0, n);
      }
      return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    }
  }

  static Result parse(String body, String current) throws IOException {
    try {
      JSONObject release = new JSONObject(body);
      if (release.optBoolean("draft") || release.optBoolean("prerelease"))
        throw new IOException("仓库返回了预发布版本，本应用只检查正式版，请前往仓库确认。");
      String tag = release.getString("tag_name").trim();
      int order = compare(tag, current);
      String url = release.getString("html_url");
      HttpUrl parsed = HttpUrl.parse(url);
      if (parsed == null
          || !parsed.isHttps()
          || !parsed.host().equals("github.com")
          || parsed.port() != 443
          || !parsed.encodedUsername().isEmpty()
          || !parsed.encodedPassword().isEmpty()
          || !parsed.encodedPath().startsWith("/beizilaoshi/Cupbox-Android-Qbot/releases/tag/"))
        throw new IOException("发行页地址与指定仓库不符，请前往项目主页确认。");
      JSONArray assets = release.optJSONArray("assets");
      boolean apk = false;
      if (assets != null)
        for (int i = 0; i < assets.length(); i++) {
          JSONObject asset = assets.optJSONObject(i);
          if (asset != null
              && asset.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")
              && asset.optString("state").equals("uploaded")) apk = true;
        }
      String notes = release.optString("body").trim();
      if (notes.isEmpty()) notes = "维护者尚未填写此版本的更新说明。";
      if (notes.length() > 8000) notes = notes.substring(0, 8000) + "\n…完整内容请查看发行页。";
      String title = release.optString("name", tag);
      if (title.length() > 160) title = title.substring(0, 160) + "…";
      return new Result(
          order > 0 ? State.NEWER : order == 0 ? State.CURRENT : State.LOCAL_NEWER,
          tag,
          title,
          notes,
          url,
          apk,
          true);
    } catch (JSONException e) {
      throw new IOException("版本信息格式异常，请前往仓库查看。", e);
    }
  }

  void cancel() {
    cancelled = true;
    Call call = active;
    if (call != null) call.cancel();
  }
}
