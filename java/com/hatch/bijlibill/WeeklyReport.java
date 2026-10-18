package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * v3.4.0 — weekly usage report math. Pure Java, no Android
 * dependencies — headless-unit-testable. The receiver owns storage
 * and notifications; this class is only the aggregation.
 */
public final class WeeklyReport {

    private WeeklyReport() {}

    /** Report window length in days. */
    public static final int WINDOW_DAYS = 7;
    /** Cycle length used when prorating from a fetched bill. */
    public static final int PRORATE_CYCLE_DAYS = 30;

    /** One reading point: midnight-ms of the day + meter reading. */
    public static final class Point {
        public long dayMs;
        public double reading;
    }

    /**
     * Units consumed inside the [nowMs - 7d, nowMs] window: the
     * latest reading minus the latest point at/before the window
     * start. The caller should include the guard anchor as the
     * first point when one exists. Falls back to the earliest
     * point when every point is inside the window; negative results
     * (meter reset etc.) clamp to 0. Empty input -> 0.
     */
    public static double weekUnits(List<Point> points, long nowMs) {
        if (points == null || points.isEmpty()) return 0;
        List<Point> sorted = new ArrayList<Point>(points);
        Collections.sort(sorted, new Comparator<Point>() {
            @Override public int compare(Point a, Point b) {
                return a.dayMs < b.dayMs ? -1
                        : (a.dayMs > b.dayMs ? 1 : 0);
            }
        });
        long weekAgo = nowMs - (long) WINDOW_DAYS * 86400000L;
        Point latest = sorted.get(sorted.size() - 1);
        Point base = null;
        for (Point p : sorted) {
            if (p.dayMs <= weekAgo) base = p;
        }
        if (base == null) base = sorted.get(0);
        double u = latest.reading - base.reading;
        return u > 0 ? u : 0;
    }

    /**
     * Fallback when the guard has no data at all: prorate the last
     * fetched bill's units over a 30-day cycle. Non-positive input
     * -> 0 (caller treats 0 as "nothing to report").
     */
    public static double prorateWeekUnits(double billUnits) {
        if (!(billUnits > 0)) return 0;
        return billUnits / PRORATE_CYCLE_DAYS * WINDOW_DAYS;
    }
}
