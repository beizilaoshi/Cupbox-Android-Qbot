package cn.cupbox.qbot;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;

public class UpdateCheckerTest {
  private JSONObject release(String tag) throws Exception {
    return new JSONObject()
        .put("tag_name", tag)
        .put("name", "Cupbox " + tag)
        .put("html_url", UpdateChecker.PROJECT + "/releases/tag/" + tag)
        .put("body", "修复问题与界面改进")
        .put(
            "assets",
            new JSONArray()
                .put(new JSONObject().put("name", "Cupbox-release.apk").put("state", "uploaded")));
  }

  private UpdateChecker checker(MockWebServer server) {
    return new UpdateChecker(
        new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).followRedirects(false).build(),
        server.url("/"),
        server.url("/"));
  }

  @Test
  public void semanticVersionsAreNumericAndAcceptVPrefix() throws Exception {
    assertTrue(UpdateChecker.compare("v1.10.0", "1.9.9") > 0);
    assertEquals(0, UpdateChecker.compare("V1.0.0+build.4", "1.0.0"));
    assertTrue(UpdateChecker.compare("0.3.0", "1.0.0") < 0);
    for (String invalid :
        new String[] {"1.0", "1.0.0-beta.1", "1.00.0", "1.999999999999999999.0", "release-2.0.0"})
      assertThrows(IOException.class, () -> UpdateChecker.compare(invalid, "1.0.0"));
  }

  @Test
  public void newerCurrentAndLocalAheadRemainDistinct() throws Exception {
    var newer = UpdateChecker.parse(release("v1.1.0").toString(), "1.0.0");
    assertEquals(UpdateChecker.State.NEWER, newer.state());
    assertTrue(newer.hasApk());
    assertTrue(newer.notes().contains("修复"));
    assertEquals(
        UpdateChecker.State.CURRENT,
        UpdateChecker.parse(release("v1.0.0").toString(), "1.0.0").state());
    assertEquals(
        UpdateChecker.State.LOCAL_NEWER,
        UpdateChecker.parse(release("v0.3.0").toString(), "1.0.0").state());
    assertFalse(
        UpdateChecker.parse(release("v1.1.0").put("assets", new JSONArray()).toString(), "1.0.0")
            .hasApk());
  }

  @Test
  public void rejectsPreviewsAndLinksOutsideTheSpecifiedRepository() throws Exception {
    assertThrows(
        IOException.class,
        () -> UpdateChecker.parse(release("v2.0.0").put("prerelease", true).toString(), "1.0.0"));
    assertThrows(
        IOException.class,
        () -> UpdateChecker.parse(release("v2.0.0").put("draft", true).toString(), "1.0.0"));
    for (String link :
        new String[] {
          "https://example.com/download",
          "https://github.com/other/repo/releases/tag/v2.0.0",
          "http://github.com/beizilaoshi/Cupbox-Android-Qbot/releases/tag/v2.0.0"
        })
      assertThrows(
          IOException.class,
          () -> UpdateChecker.parse(release("v2.0.0").put("html_url", link).toString(), "1.0.0"));
  }

  @Test
  public void noReleaseRequiresAnAccessibleRepositoryAndNeverMeansCurrent() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(404));
      server.enqueue(new MockResponse().setBody("{}"));
      server.start();
      assertEquals(UpdateChecker.State.NO_RELEASE, checker(server).check("1.0.0").state());
      RecordedRequest first = server.takeRequest();
      assertEquals("/repos/beizilaoshi/Cupbox-Android-Qbot/releases/latest", first.getPath());
      assertEquals("Cupbox-Android/1.0.0", first.getHeader("User-Agent"));
      assertNull(first.getHeader("Authorization"));
      assertEquals(0, first.getBodySize());
      assertEquals("/repos/beizilaoshi/Cupbox-Android-Qbot", server.takeRequest().getPath());
    }
  }

  @Test
  public void missingRepositoryRateLimitsAndBadResponsesStayErrors() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setResponseCode(404));
      server.enqueue(new MockResponse().setResponseCode(404));
      assertThrows(IOException.class, () -> checker(server).check("1.0.0"));
      for (int code : new int[] {403, 429, 500, 302}) {
        server.enqueue(new MockResponse().setResponseCode(code));
        if (code == 403 || code == 429) server.enqueue(new MockResponse().setResponseCode(code));
        assertThrows(IOException.class, () -> checker(server).check("1.0.0"));
      }
      server.enqueue(new MockResponse().setBody("not-json"));
      assertThrows(IOException.class, () -> checker(server).check("1.0.0"));
      server.enqueue(new MockResponse().setBody("x".repeat(512 * 1024 + 1)));
      assertThrows(IOException.class, () -> checker(server).check("1.0.0"));
    }
  }

  @Test
  public void rateLimitedApiUsesOnlyTheSameRepositoryOfficialReleasePage() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setResponseCode(403));
      server.enqueue(
          new MockResponse()
              .setResponseCode(302)
              .addHeader("Location", "/beizilaoshi/Cupbox-Android-Qbot/releases"));
      server.enqueue(new MockResponse().setBody("<h2>There aren’t any releases here</h2>"));
      assertEquals(UpdateChecker.State.NO_RELEASE, checker(server).check("1.0.0").state());
      server.enqueue(new MockResponse().setResponseCode(429));
      server.enqueue(
          new MockResponse()
              .setResponseCode(302)
              .addHeader("Location", "/beizilaoshi/Cupbox-Android-Qbot/releases/tag/v1.0.2"));
      var result = checker(server).check("1.0.0");
      assertEquals(UpdateChecker.State.NEWER, result.state());
      assertFalse(result.detailsAvailable());
      assertEquals(UpdateChecker.PROJECT + "/releases/tag/v1.0.2", result.url());
      server.enqueue(new MockResponse().setResponseCode(403));
      server.enqueue(
          new MockResponse()
              .setResponseCode(302)
              .addHeader("Location", "https://example.com/releases/tag/v2.0.0"));
      assertThrows(IOException.class, () -> checker(server).check("1.0.0"));
      server.enqueue(new MockResponse().setResponseCode(403));
      server.enqueue(new MockResponse().setBody("<html>Unexpected page</html>"));
      assertThrows(IOException.class, () -> checker(server).check("1.0.0"));
    }
  }

  @Test
  public void successReadsNotesAndCancellationStopsAnInFlightRequest() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setBody(release("v1.0.1").toString()));
      assertEquals(UpdateChecker.State.NEWER, checker(server).check("1.0.0").state());
      server.takeRequest();
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      UpdateChecker pending = checker(server);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<?> task =
            executor.submit(
                () -> {
                  try {
                    pending.check("1.0.0");
                    throw new AssertionError("cancelled check succeeded");
                  } catch (IOException expected) {
                  }
                });
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
        pending.cancel();
        task.get(3, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
      UpdateChecker cancelled = checker(server);
      cancelled.cancel();
      assertThrows(IOException.class, () -> cancelled.check("1.0.0"));
    }
  }
}
