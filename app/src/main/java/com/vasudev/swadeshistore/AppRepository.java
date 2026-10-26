package com.vasudev.swadeshistore;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// loads the bundled app list from assets/indian_apps.json
public class AppRepository {

    public final List<AppItem> apps = new ArrayList<>();
    public final List<String> categories = new ArrayList<>();

    public static AppRepository load(Context ctx) {
        AppRepository repo = new AppRepository();
        try (InputStream is = ctx.getAssets().open("indian_apps.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);

            JSONObject root = new JSONObject(json);
            JSONArray cats = root.optJSONArray("categories");
            if (cats != null) {
                for (int i = 0; i < cats.length(); i++) repo.categories.add(cats.getString(i));
            }
            JSONArray arr = root.optJSONArray("apps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    AppItem a = new AppItem();
                    a.id = o.optString("id");
                    a.name = o.optString("name");
                    a.logo = o.optString("logo");
                    a.pkg = o.optString("package");
                    a.developer = o.optString("developer");
                    a.category = o.optString("category");
                    a.subcategory = o.optString("subcategory");
                    a.description = o.optString("description");
                    a.downloads = o.optString("downloads");
                    a.color = o.optString("color", "#1565C0");
                    a.rating = o.optDouble("rating", 0);
                    a.playUrl = o.optString("playUrl");
                    a.indusUrl = o.optString("indusUrl");
                    a.govtUrl = o.optString("govtUrl");
                    a.apkUrl = o.optString("apkUrl");
                    a.githubUrl = o.optString("githubUrl");
                    a.websiteUrl = o.optString("websiteUrl");
                    JSONArray tg = o.optJSONArray("tags");
                    if (tg != null) for (int j = 0; j < tg.length(); j++) a.tags.add(tg.getString(j));
                    repo.apps.add(a);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return repo;
    }
}
