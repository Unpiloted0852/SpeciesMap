package io.github.unpiloted0852.speciesmap;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-update from the project's GitHub Releases (free, keyless API).
 *
 * {@link #check} reads the latest published release and compares its tag ("v1.1") with the
 * installed versionName. {@link #install} streams that release's .apk asset straight into a
 * PackageInstaller session, so nothing is written to shared storage.
 *
 * Android still owns the final step: the first update shows the system "update this app?"
 * dialog (and, once, the "allow installs from this app" setting). On Android 12+ later updates
 * may install without that dialog. The update replaces the running app, which closes it, and
 * Android does not let an app reopen itself, so the user reopens it by hand. An update only
 * installs over a build signed with the same key as the release APK, so a debug build cannot
 * update itself this way.
 *
 * Callbacks run on the main thread.
 */
final class AppUpdater {

    static final class Release {
        final String versionName;
        final String apkUrl;

        Release(String versionName, String apkUrl) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }

    interface CheckCallback {
        /** {@code newer} is null when up to date, or when the check {@code failed}. */
        void onChecked(Release newer, boolean failed);
    }

    interface InstallCallback {
        void onProgress(int percent);

        /** {@code problem} is null on success, "cancelled" if the user backed out, else a short reason. */
        void onFinished(String problem);
    }

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/Unpiloted0852/SpeciesMap/releases/latest";
    private static final String USER_AGENT = "SpeciesMap (https://github.com/Unpiloted0852/SpeciesMap)";

    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    AppUpdater(Activity activity) {
        this.activity = activity;
    }

    void close() {
        io.shutdownNow();
    }

    String installedVersion() {
        try {
            String v = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    void check(CheckCallback callback) {
        io.execute(() -> {
            Release newer = null;
            boolean failed = false;
            try {
                Release latest = fetchLatest();
                if (latest != null && isNewer(latest.versionName, installedVersion())) newer = latest;
            } catch (Exception e) {
                failed = true;
            }
            Release result = newer;
            boolean didFail = failed;
            main.post(() -> callback.onChecked(result, didFail));
        });
    }

    void install(Release release, InstallCallback callback) {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        String action = activity.getPackageName() + ".UPDATE_STATUS";
        AtomicBoolean finished = new AtomicBoolean();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                switch (status) {
                    case PackageInstaller.STATUS_PENDING_USER_ACTION:
                        Intent confirm = confirmIntent(intent);
                        if (confirm != null) activity.startActivity(confirm);
                        return;
                    case PackageInstaller.STATUS_SUCCESS:
                        finish(this, finished, callback, null);
                        return;
                    case PackageInstaller.STATUS_FAILURE_ABORTED:
                        finish(this, finished, callback, "cancelled");
                        return;
                    default:
                        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        finish(this, finished, callback, message != null ? message : "error " + status);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver, new IntentFilter(action));
        }

        io.execute(() -> {
            int sessionId = -1;
            boolean committed = false;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(activity.getPackageName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    download(release.apkUrl, session, callback);
                    // Mutable so the installer can attach the status extras; package-scoped
                    // so it only ever reaches the receiver registered above.
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent status = PendingIntent.getBroadcast(
                            activity, sessionId, new Intent(action).setPackage(activity.getPackageName()), flags);
                    session.commit(status.getIntentSender());
                    committed = true;
                }
            } catch (Exception e) {
                String problem = e.getMessage() != null ? e.getMessage() : "download failed";
                main.post(() -> finish(receiver, finished, callback, problem));
            } finally {
                if (!committed && sessionId != -1) {
                    try {
                        installer.abandonSession(sessionId);
                    } catch (RuntimeException ignored) {
                        // session already gone
                    }
                }
            }
        });
    }

    private void finish(BroadcastReceiver receiver, AtomicBoolean finished, InstallCallback callback, String problem) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            activity.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // activity already gone
        }
        callback.onFinished(problem);
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmIntent(Intent statusIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return statusIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return statusIntent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private void download(String url, PackageInstaller.Session session, InstallCallback callback) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("download failed (HTTP " + conn.getResponseCode() + ")");
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = session.openWrite("update.apk", 0, total)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        main.post(() -> callback.onProgress(percent));
                    }
                }
                session.fsync(out);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** The latest release that has an APK attached, or null if there is none yet. */
    private Release fetchLatest() throws Exception {
        HttpURLConnection conn = open(LATEST_RELEASE_URL);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code == 404) return null; // nothing published yet
            if (code != 200) throw new IOException("HTTP " + code);
            JSONObject o;
            try (InputStream in = conn.getInputStream()) {
                o = new JSONObject(readAll(in));
            }
            String version = o.optString("tag_name").trim();
            if (version.startsWith("v")) version = version.substring(1);
            JSONArray assets = o.optJSONArray("assets");
            if (version.isEmpty() || assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null || !a.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                String url = a.optString("browser_download_url");
                if (!url.isEmpty()) return new Release(version, url);
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Numeric, dot-separated comparison: "3.7" > "3.6", "3.10" > "3.9", "4" > "3.99". */
    static boolean isNewer(String candidate, String installed) {
        String[] a = candidate.split("\\.");
        String[] b = installed.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? toInt(a[i]) : 0;
            int y = i < b.length ? toInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
