package com.hatch.bijlibill;

/**
 * v3.2.0 — 200-Unit Guard projection math. Pure Java, no Android
 * dependencies — headless-unit-testable.
 *
 * The guard keeps a Protected consumer under the 200-unit cliff:
 * from an anchor reading/date and the latest reading it projects
 * the month-end units and reports the daily budget that keeps the
 * consumer safe. The activity layer (GuardActivity) owns the anchor,
 * the entries and the notifications; this class is only the math.
 */
public final class GuardCalc {

    private GuardCalc() {}

    /** The Protected cliff: crossing it loses subsidy + cheap slabs. */
    public static final double UNIT_LIMIT = 200.0;
    /** Early-warning threshold on units already used. */
    public static final double WARN_AT = 190.0;
    /** Billing-cycle length when the bill gives no better anchor. */
    public static final int DEFAULT_CYCLE_DAYS = 30;

    public enum Status { SAFE, WARNING, DANGER }

    /** One projection snapshot. */
    public static final class Projection {
        /** Units used since the anchor reading (>= 0). */
        public double usedUnits;
        /** Units/day so far (0 when no days elapsed yet). */
        public double dailyAvg;
        /** Projected month-end units at the current pace. */
        public double projectedUnits;
        /**
         * Max units/day for the REST of the cycle that keeps the
         * total at/below 200 (0 when nothing is left to spend, or
         * when the cycle is over).
         */
        public double dailyBudget;
        public int daysElapsed;
        public int remainingDays;
        public Status status;
        /**
         * v3.6.0 — true when this is an ANDAZA derived from the last
         * fetched bill's pace (no real readings entered yet). The UI
         * must label estimates distinctly from precise projections.
         */
        public boolean estimate;
    }

    /**
     * Projects the billing cycle.
     *
     * @param usedUnits   units used since the anchor (>= 0; negative
     *                    input — e.g. a reading below the anchor — is
     *                    clamped to 0)
     * @param daysElapsed days since the anchor date (>= 0)
     * @param cycleDays   billing-cycle length in days (> 0;
     *                    non-positive falls back to 30)
     */
    public static Projection project(double usedUnits, int daysElapsed,
            int cycleDays) {
        Projection p = new Projection();
        p.estimate = false;
        if (!(usedUnits > 0)) usedUnits = 0;
        if (daysElapsed < 0) daysElapsed = 0;
        if (cycleDays <= 0) cycleDays = DEFAULT_CYCLE_DAYS;
        p.usedUnits = usedUnits;
        p.daysElapsed = daysElapsed;
        p.dailyAvg = daysElapsed > 0 ? usedUnits / daysElapsed : 0;
        p.remainingDays = Math.max(0, cycleDays - daysElapsed);
        p.projectedUnits = usedUnits + p.dailyAvg * p.remainingDays;
        p.dailyBudget = p.remainingDays > 0
                ? Math.max(0, (UNIT_LIMIT - usedUnits) / p.remainingDays)
                : 0;
        if (p.projectedUnits > UNIT_LIMIT) {
            p.status = Status.DANGER;
        } else if (usedUnits >= WARN_AT) {
            p.status = Status.WARNING;
        } else {
            p.status = Status.SAFE;
        }
        return p;
    }

    /**
     * v3.6.0 — auto-projection from the last fetched bill: the Guard
     * works with ZERO user input. dailyAvg = billUnits /
     * billingDays; the month-end projection assumes the same pace.
     * The reading-based {@link #project} always wins when the user
     * HAS entered readings — callers must prefer it.
     *
     * @param billUnits   units on the last fetched bill (> 0)
     * @param billingDays cycle length in days (> 0; falls back to 30)
     * @param daysElapsed days since the bill's reading date (>= 0)
     */
    public static Projection autoProjectFromBill(double billUnits,
            int billingDays, int daysElapsed) {
        Projection p = new Projection();
        p.estimate = true;
        if (!(billUnits > 0)) billUnits = 0;
        if (billingDays <= 0) billingDays = DEFAULT_CYCLE_DAYS;
        if (daysElapsed < 0) daysElapsed = 0;
        p.dailyAvg = billUnits / billingDays;
        p.usedUnits = p.dailyAvg * daysElapsed;
        p.daysElapsed = daysElapsed;
        p.remainingDays = Math.max(0, billingDays - daysElapsed);
        p.projectedUnits = p.dailyAvg * billingDays;
        p.dailyBudget = p.remainingDays > 0
                ? Math.max(0, (UNIT_LIMIT - p.usedUnits)
                        / p.remainingDays)
                : 0;
        if (p.projectedUnits > UNIT_LIMIT) {
            p.status = Status.DANGER;
        } else if (p.usedUnits >= WARN_AT) {
            p.status = Status.WARNING;
        } else {
            p.status = Status.SAFE;
        }
        return p;
    }
}
