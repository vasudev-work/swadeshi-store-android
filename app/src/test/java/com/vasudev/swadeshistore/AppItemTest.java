package com.vasudev.swadeshistore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// pure-logic tests for AppItem (no Android runtime needed)
public class AppItemTest {

    private AppItem app(String name, String dev, String cat, String desc, String... tags) {
        AppItem a = new AppItem();
        a.name = name;
        a.developer = dev;
        a.category = cat;
        a.description = desc;
        for (String t : tags) a.tags.add(t);
        return a;
    }

    private final AppItem phonepe = app("PhonePe", "PhonePe Pvt Ltd",
            "Fintech, Payments & Banking", "UPI payments and bill pay",
            "Finance, Payments & Investments");

    @Test
    public void matchesByNameIgnoringCaseAndSpaces() {
        assertTrue(phonepe.matchesQuery("phonepe"));
        assertTrue(phonepe.matchesQuery("PHONEPE"));
        assertTrue(phonepe.matchesQuery("  phonepe  "));
    }

    @Test
    public void matchesByDeveloperCategoryAndDescription() {
        assertTrue(phonepe.matchesQuery("pvt"));
        assertTrue(phonepe.matchesQuery("fintech"));
        assertTrue(phonepe.matchesQuery("bill"));
    }

    @Test
    public void emptyOrNullQueryMatchesEverything() {
        assertTrue(phonepe.matchesQuery(null));
        assertTrue(phonepe.matchesQuery(""));
        assertTrue(phonepe.matchesQuery("   "));
    }

    @Test
    public void unrelatedQueryDoesNotMatch() {
        assertFalse(phonepe.matchesQuery("swiggy"));
    }

    @Test
    public void inCategoryMatchesCategoryAndTags() {
        assertTrue(phonepe.inCategory("Fintech, Payments & Banking"));
        assertTrue(phonepe.inCategory("Finance, Payments & Investments"));
    }

    @Test
    public void inCategoryAllOrNullMatchesEverything() {
        assertTrue(phonepe.inCategory("All"));
        assertTrue(phonepe.inCategory(null));
    }

    @Test
    public void inCategoryRejectsOtherCategories() {
        assertFalse(phonepe.inCategory("Gaming & Esports"));
    }
}
