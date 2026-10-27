package cn.cupbox.qbot;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

public final class Store extends SQLiteOpenHelper {
  private final Context app;

  public Store(Context c) {
    super(c, "cupbox.db", null, 1);
    app = c.getApplicationContext();
    // 主线程读、工作线程写：开启 WAL，避免读写互相阻塞。
    setWriteAheadLoggingEnabled(true);
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT,session TEXT NOT NULL,role TEXT"
            + " NOT NULL,body TEXT NOT NULL)");
    db.execSQL("CREATE INDEX history_session ON history(session,id)");
  }

  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

  private SecretKey secret() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("cupbox.config")) {
      KeyGenerator gen =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      gen.init(
          new KeyGenParameterSpec.Builder(
                  "cupbox.config", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      gen.generateKey();
    }
    return (SecretKey) ks.getKey("cupbox.config", null);
  }

  public synchronized Config load() throws Exception {
    String value = app.getSharedPreferences("vault", 0).getString("config", null);
    if (value == null) return new Config();
    String[] parts = value.split(":");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        secret(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return Config.from(
        new JSONObject(
            new String(
                cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                java.nio.charset.StandardCharsets.UTF_8)));
  }

  public synchronized void save(Config c) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, secret());
    String value =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(
                cipher.doFinal(
                    c.json().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    if (!app.getSharedPreferences("vault", 0).edit().putString("config", value).commit())
      throw new IllegalStateException("设置写入失败");
  }

  public synchronized JSONArray history(String session, int limit) throws JSONException {
    JSONArray reverse = new JSONArray(), result = new JSONArray();
    limit = Math.max(0, Math.min(20, limit));
    if (limit == 0) return result;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT role,body FROM history WHERE session=? ORDER BY id DESC LIMIT ?",
                new String[] {session, String.valueOf(limit)})) {
      while (c.moveToNext())
        reverse.put(new JSONObject().put("role", c.getString(0)).put("content", c.getString(1)));
    }
    for (int i = reverse.length() - 1; i >= 0; i--) result.put(reverse.get(i));
    return result;
  }

  public synchronized void remember(String session, String input, String output) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.execSQL(
          "INSERT INTO history(session,role,body) VALUES(?,?,?)",
          new Object[] {session, "user", input});
      db.execSQL(
          "INSERT INTO history(session,role,body) VALUES(?,?,?)",
          new Object[] {session, "assistant", output});
      db.execSQL(
          "DELETE FROM history WHERE session=? AND id NOT IN (SELECT id FROM history WHERE"
              + " session=? ORDER BY id DESC LIMIT 20)",
          new Object[] {session, session});
      db.execSQL(
          "DELETE FROM history WHERE session NOT IN (SELECT session FROM history GROUP BY session"
              + " ORDER BY MAX(id) DESC LIMIT 32)");
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public synchronized String summary() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT COUNT(DISTINCT session),COUNT(*) FROM history", null)) {
      c.moveToFirst();
      return c.getInt(0) + " 个会话 · " + c.getInt(1) + " 条记忆";
    }
  }

  public synchronized void clear() {
    getWritableDatabase().delete("history", null, null);
  }
}
