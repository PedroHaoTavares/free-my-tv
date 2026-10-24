package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Continue watching" from your own Jellyfin server, in place of TMDB's picks (config.properties "jellyfin_url",
 * "jellyfin_user", "jellyfin_password"). Signs in with the user and password (no API key needed), keeps the token,
 * and lists what that user has half watched. A pick opens the item's page in the Jellyfin app, on "Resume".
 *
 * The Jellyfin app on the TV must be signed in to the same server and user: it opens the item by its id.
 */
final class Jellyfin {
    private Jellyfin() {}

    static final String PKG = "org.jellyfin.androidtv";
    /** Recs.Pick.app for Jellyfin items. */
    static final String APP = "jellyfin";
    /** Recs.Pick.link prefix: the item's id follows. */
    static final String LINK = "jellyfin:";
    private static final String URL_BASE = Config.JELLYFIN_URL.trim().replaceAll("/+$", "");

    static boolean on() {
        return !URL_BASE.isEmpty() && !Config.JELLYFIN_USER.trim().isEmpty();
    }

    /** For Recs' cache: a new server or user fetches again. */
    static long stamp() {
        return ((URL_BASE.hashCode() * 31L + Config.JELLYFIN_USER.trim().hashCode()) * 31L + Config.JELLYFIN_PASSWORD.hashCode()) * 31L
                + Config.JELLYFIN_TABS.hashCode();
    }

    /**
     * The home screen tabs (config.properties "jellyfin_tabs": library names, comma separated, e.g. Filmes,Séries).
     * Empty: the usual three tabs, all of "Continue watching".
     */
    static final String[] TABS = tabNames();

    private static String[] tabNames() {
        List<String> out = new ArrayList<>();
        for (String s : Config.JELLYFIN_TABS.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out.toArray(new String[0]);
    }

    static boolean libraryTabs() {
        return on() && TABS.length > 0;
    }

    /** Recs.Pick.kind of the items of library tab i. */
    static String tabKind(int i) {
        return "jf" + i;
    }

    /** Recs.Pick.kind of the first tab, "Home": what you were watching last, in any library. */
    static final String HOME_KIND = "jfhome";
    /** Recs.Pick.kind of the second tab, "New": what came in last to the server, any library. */
    static final String NEW_KIND = "jfnew";

    /** The tabs' kinds in order: Home, New, then one per library. */
    static String[] tabKinds() {
        String[] kinds = new String[TABS.length + 2];
        kinds[0] = HOME_KIND;
        kinds[1] = NEW_KIND;
        for (int i = 0; i < TABS.length; i++) kinds[i + 2] = tabKind(i);
        return kinds;
    }

    /** The tabs' names in order: Home, New, then the libraries as named in config.properties. */
    static String[] tabTitles() {
        String[] titles = new String[TABS.length + 2];
        titles[0] = Ui.tr("tab|Home");
        titles[1] = Ui.tr("tab|New");
        System.arraycopy(TABS, 0, titles, 2, TABS.length);
        return titles;
    }

    /**
     * Without tabs: what the user has half watched, newest first, one per show. With tabs, for each library: what's
     * half watched, then the next episodes, then what's new, so no tab is ever empty. Up to `max` per tab.
     */
    static List<Recs.Pick> fetch(Context c, int max) throws Exception {
        try {
            return fetch(c, max, false);
        } catch (Unauthorized e) {
            return fetch(c, max, true);    // the saved token expired or was revoked: sign in again once
        }
    }

    private static List<Recs.Pick> fetch(Context c, int max, boolean fresh) throws Exception {
        String[] login = login(c, fresh);
        String token = login[0], user = login[1];
        List<Recs.Pick> out = new ArrayList<>();
        if (!libraryTabs()) {
            add(out, items("/Users/" + user + "/Items/Resume?MediaTypes=Video&Limit=30" + FIELDS, token), null, null, max, new HashSet<>());
            return out;
        }
        // Home: the latest you were watching, any library (newest first), then the next episodes.
        Set<String> seenHome = new HashSet<>();
        List<Recs.Pick> home = new ArrayList<>();
        add(home, items("/Users/" + user + "/Items/Resume?MediaTypes=Video&Limit=30" + FIELDS, token), HOME_KIND, null, max, seenHome);
        add(home, items("/Shows/NextUp?UserId=" + user + "&Limit=20" + FIELDS, token), HOME_KIND, Ui.tr("Next episode"), max, seenHome);
        out.addAll(home);
        // New: the latest added to the server, any library (shows come as the show, not each episode).
        List<Recs.Pick> latest = new ArrayList<>();
        add(latest, new JSONArray(request("/Users/" + user + "/Items/Latest?Limit=20" + FIELDS, null, token)), NEW_KIND, null, max, new HashSet<>());
        out.addAll(latest);
        JSONArray views = new JSONObject(request("/Users/" + user + "/Views", null, token)).getJSONArray("Items");
        for (int t = 0; t < TABS.length; t++) {
            String library = null;
            for (int i = 0; i < views.length() && library == null; i++) {
                if (plain(views.getJSONObject(i).optString("Name")).equals(plain(TABS[t]))) library = views.getJSONObject(i).optString("Id");
            }
            if (library == null) {
                AppLog.w("jellyfin: no library named " + TABS[t]);
                continue;
            }
            String kind = tabKind(t);
            Set<String> seen = new HashSet<>();
            List<Recs.Pick> tab = new ArrayList<>();
            add(tab, items("/Users/" + user + "/Items/Resume?MediaTypes=Video&Limit=30&ParentId=" + library + FIELDS, token), kind, null, max, seen);
            add(tab, items("/Shows/NextUp?UserId=" + user + "&Limit=20&ParentId=" + library + FIELDS, token), kind, Ui.tr("Next episode"), max, seen);
            add(tab, new JSONArray(request("/Users/" + user + "/Items/Latest?Limit=20&ParentId=" + library + FIELDS, null, token)),
                    kind, Ui.tr("New"), max, seen);
            out.addAll(tab);
        }
        return out;
    }

    private static final String FIELDS = "&Fields=Overview&EnableImageTypes=Backdrop,Thumb,Primary";

    private static JSONArray items(String path, String token) throws Exception {
        return new JSONObject(request(path, null, token)).getJSONArray("Items");
    }

    /** Adds the items as picks (one per show), labeled with `tag` ("New"…) when given, up to `max` in `to`. */
    private static void add(List<Recs.Pick> to, JSONArray items, String kind, String tag, int max, Set<String> seen) {
        for (int i = 0; i < items.length() && to.size() < max; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String show = o.optString("SeriesName", o.optString("Name", ""));
            if (!seen.add(show)) continue;   // two episodes of one show: the first (newest) only
            Recs.Pick p = pick(o);
            if (p == null) continue;
            if (kind != null) p.kind = kind;
            if (tag != null) p.meta = p.meta.isEmpty() ? tag : tag + " · " + p.meta;
            to.add(p);
        }
    }

    /** "Séries" and "series" are the same library name. */
    private static String plain(String s) {
        return java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static Recs.Pick pick(JSONObject o) {
        String id = o.optString("Id", "");
        if (id.isEmpty()) return null;
        boolean episode = "Episode".equals(o.optString("Type"));
        Recs.Pick p = new Recs.Pick();
        p.id = id.hashCode();
        p.kind = episode ? "tv" : "movie";
        p.title = episode ? o.optString("SeriesName", o.optString("Name")) : o.optString("Name");
        p.app = APP;
        p.pkg = PKG;
        p.link = LINK + id;
        p.detailed = true;
        // The show's backdrop for an episode (episodes rarely have their own), else the item's; a thumb as a last resort.
        String imageItem = null, imageType = "Backdrop";
        JSONArray own = o.optJSONArray("BackdropImageTags");
        JSONArray parent = o.optJSONArray("ParentBackdropImageTags");
        if (own != null && own.length() > 0) imageItem = id;
        else if (parent != null && parent.length() > 0) imageItem = o.optString("ParentBackdropItemId", null);
        if (imageItem == null && o.optJSONObject("ImageTags") != null && o.optJSONObject("ImageTags").has("Thumb")) {
            imageItem = id;
            imageType = "Thumb";
        }
        p.backdrop = imageItem == null ? null : "jf-" + imageItem + "-" + imageType;
        // The tall cards: the show's poster for an episode, else the item's own.
        JSONObject tags = o.optJSONObject("ImageTags");
        if (episode && o.has("SeriesPrimaryImageTag") && !o.optString("SeriesId").isEmpty()) p.poster = "jf-" + o.optString("SeriesId") + "-Primary";
        else if (!episode && tags != null && tags.has("Primary")) p.poster = "jf-" + id + "-Primary";
        // "S7:E1 · The episode's name · 38 min left"
        StringBuilder meta = new StringBuilder();
        if (episode) {
            if (o.has("ParentIndexNumber") && o.has("IndexNumber")) {
                meta.append(Ui.trf("S{season}:E{episode}", "season", String.valueOf(o.optInt("ParentIndexNumber")),
                        "episode", String.valueOf(o.optInt("IndexNumber"))));
            }
            String name = o.optString("Name", "");
            if (!name.isEmpty()) meta.append(meta.length() == 0 ? "" : " · ").append(name);
        } else if (o.has("ProductionYear")) {
            meta.append(o.optInt("ProductionYear"));
        }
        long runtime = o.optLong("RunTimeTicks", 0);
        JSONObject user = o.optJSONObject("UserData");
        long position = user == null ? 0 : user.optLong("PlaybackPositionTicks", 0);
        long left = (runtime - position) / 600_000_000L;   // ticks are 100 ns
        if (runtime > 0 && left > 0) meta.append(meta.length() == 0 ? "" : " · ").append(Ui.trf("{n} min left", "n", String.valueOf(left)));
        p.meta = meta.toString();
        return p;
    }

    /** The image to download for an image key from pick() (backdrop or poster), sized for the UI. */
    static String imageUrl(String image) {
        String[] parts = image.split("-");   // "jf", item id, type
        String size = parts[2].equals("Primary") ? "?maxHeight=" + 2 * Backdrop.TALL_H : "?maxWidth=" + Backdrop.W;
        return URL_BASE + "/Items/" + parts[1] + "/Images/" + parts[2] + size + "&quality=90";
    }

    /** Opens the item's page in the Jellyfin app, or null if the link isn't a Jellyfin one. */
    static Intent open(String link) {
        if (link == null || !link.startsWith(LINK)) return null;
        // StartupActivity reads the "ItemId" extra and goes to the item's details (jellyfin-androidtv, StartupActivity.kt)
        return new Intent().setClassName(PKG, PKG + ".ui.startup.StartupActivity")
                .putExtra("ItemId", link.substring(LINK.length()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // MARK: - Sign-in (the token is kept; a 401 signs in again)

    private static final class Unauthorized extends IOException {
        Unauthorized() { super("Jellyfin refused the sign-in"); }
    }

    /** {token, user id}, from the cache or a new sign-in. */
    private static String[] login(Context c, boolean fresh) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("jellyfin", Context.MODE_PRIVATE);
        String token = sp.getString("token", null), user = sp.getString("user", null);
        if (!fresh && token != null && user != null && sp.getLong("stamp", 0) == stamp()) return new String[] {token, user};
        JSONObject body = new JSONObject().put("Username", Config.JELLYFIN_USER.trim()).put("Pw", Config.JELLYFIN_PASSWORD);
        JSONObject r = new JSONObject(request("/Users/AuthenticateByName", body.toString(), null));
        token = r.getString("AccessToken");
        user = r.getJSONObject("User").getString("Id");
        sp.edit().putString("token", token).putString("user", user).putLong("stamp", stamp()).apply();
        return new String[] {token, user};
    }

    /** Jellyfin's Authorization header: who is asking, and the token once signed in. */
    private static String auth(String token) {
        return "MediaBrowser Client=\"Free My TV\", Device=\"TV\", DeviceId=\"freemytv-" + Long.toHexString(stamp())
                + "\", Version=\"1\"" + (token == null ? "" : ", Token=\"" + token + "\"");
    }

    private static String request(String path, String body, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_BASE + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", auth(token));
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code == 401) throw new Unauthorized();
            if (code != 200) throw new IOException("Jellyfin returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** Image download for Recs (the server may want the token for images too). */
    static HttpURLConnection image(Context c, String backdrop) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl(backdrop)).openConnection();
        SharedPreferences sp = c.getSharedPreferences("jellyfin", Context.MODE_PRIVATE);
        conn.setRequestProperty("Authorization", auth(sp.getString("token", null)));
        return conn;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
