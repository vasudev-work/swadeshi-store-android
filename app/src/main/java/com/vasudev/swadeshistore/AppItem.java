package com.vasudev.swadeshistore;

import java.util.ArrayList;
import java.util.List;

// one Indian app entry (matches a record in assets/indian_apps.json)
public class AppItem {
    public String id = "";
    public String name = "";
    public String logo = "";       // optional remote icon URL (blank = use monogram)
    public String pkg = "";
    public String developer = "";
    public String category = "";
    public String subcategory = "";
    public String description = "";
    public String downloads = "";
    public String color = "#1565C0";
    public List<String> tags = new ArrayList<>();
    public double rating = 0;
    public String playUrl = "";
    public String indusUrl = "";
    public String govtUrl = "";
    public String apkUrl = "";
    public String githubUrl = "";
    public String websiteUrl = "";

    public boolean matchesQuery(String q) {
        if (q == null) return true;
        q = q.trim().toLowerCase();
        if (q.isEmpty()) return true;
        return name.toLowerCase().contains(q)
                || developer.toLowerCase().contains(q)
                || category.toLowerCase().contains(q)
                || description.toLowerCase().contains(q);
    }

    public boolean inCategory(String cat) {
        if (cat == null || cat.equals("All")) return true;
        if (category.equalsIgnoreCase(cat)) return true;
        for (String tg : tags) if (tg.equalsIgnoreCase(cat)) return true;
        return false;
    }
}
