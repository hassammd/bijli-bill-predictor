package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v3.1.0 — NEAR-ACCURATE estimate from LAST MONTH'S BILL (user-approved).
 *
 * The user's principle, enforced here: NO fixed/hardcoded charges. Every
 * charge is read LIVE from the last bill, because different consumers get
 * different taxes. A line that was on the last bill is mirrored into the
 * estimate; a line that wasn't there is NEVER invented.
 *
 * How a bill line is mirrored (classified from its label, case-insensitive):
 *   PER-UNIT    FPA / fuel-price / per-unit surcharges:
 *               rate = billAmt / billUnits; est = rate x estUnits
 *   PERCENTAGE  GST / sales tax / income tax / extra / further tax:
 *               effRate = billAmt / billPreTaxBase
 *               (billPreTaxBase = electricityCharges + fixedLines + fpa);
 *               est = effRate x estPreTaxBase
 *   FIXED       meter rent, fixed charges, TV fee, anything unrecognized:
 *               amount as-is from the bill
 *   SUBSIDY     (negative): ratio vs electricityCharges, applied
 *               proportionally, shown as a negative line — BUT only when
 *               the bill's energy basis matches the estimator's slab basis.
 *               If the bill's electricityCharges are far ABOVE what the
 *               slab table gives for the bill's units/category (e.g. bill
 *               at NEPRA rates with a subsidy bridging down to protected
 *               rates), the estimator's slab rates ALREADY embody the
 *               subsidy and mirroring it would double-count it. Guarded.
 *               v3.1.3: when the bill was netted to its NET electricity
 *               basis first, SUBSIDY lines are skipped entirely — the
 *               subsidy is already inside the net basis.
 *
 * NEVER mirrored (one-time items): arrears, late-payment surcharge,
 * installments, adjustments, credits, previous balance, and all total/
 * summary rows. The caller shows a muted note when any were present.
 *
 * v3.1.2 — ENERGY BASIS CALIBRATION: the slab table in Tariff.java
 * goes stale when NEPRA revises rates, but the mirror used to keep
 * the stale energy basis while mirroring tax ratios faithfully —
 * estimates came out far too low (e.g. 302 units estimated at
 * ~Rs 10,927 against a real Rs 15,676 bill). The mirror now scales
 * the incoming slab energy by k = bill.electricityCharges /
 * Tariff.energyCharges(billUnits), the bill's real Rs/unit basis
 * (clamped to [0.25, 4.0] as a parse-garbage guard). The no-bill
 * legacy path is untouched — it stays an honest ANDAZA fallback.
 *
 * v3.1.3 — NET ENERGY BASIS: v3.1.2 calibrated against the GROSS
 * "Total Electricity Charges" line, but Protected bills print a
 * "Subsidies" line plus a "Net Electricity Charges" line — the
 * consumer really pays the NET basis. The mirror now nets first:
 * elecNet = netCharges when 0 < net < elec, else elec - subsidy
 * when 0 < subsidy < elec, else elec (garbage values ignored).
 * Calibration uses k = elecNet / Tariff.energyCharges(billUnits)
 * (same [0.25, 4.0] clamp), the tax base starts from elecNet, and
 * when a subsidy was netted all SUBSIDY-kind candidates are skipped
 * entirely — the subsidy is already baked into the net basis, so
 * mirroring it again would double-count it. Without a netted
 * subsidy the old subsidyBasisOk guard still applies (evaluated on
 * elecNet). Example: 113-unit Protected bill, Total Electricity
 * Charges 4280 - Subsidies 2551 = Net 1729 -> future 113u ~= 2064
 * (v3.1.2 wrongly gave ~4615).
 *
 * v3.2.0 — DUAL CALIBRATION: the single k above is kProt, valid
 * only while the estimate stays at/below 200 units on a Protected
 * bill. Above the 200-unit cliff the consumer pays full NEPRA
 * rates with no subsidy, so the mirror keeps a second factor
 * kNp = gross electricityCharges / NP-table(billUnits) and uses
 * it for every estimate above 200 (Protected) — e.g. 250u on the
 * 113u Protected bill: NP-table(250) x 1.6337 instead of x 1.4136.
 * MirrorResult.crossedCliff flags the cliff for the UI banner.
 *
 * Pure Java, no Android dependencies — headless-unit-testable.
 * MeterCheckCalc.compute() is left untouched: when the mirror has no
 * usable base (no bill, units <= 0, electricityCharges <= 0, or zero
 * charge lines parsed) it reports usable=false and the caller keeps the
 * old ratio / 18%-fallback path.
 */
public final class BillMirrorCalc {

    private BillMirrorCalc() {}

    /** How one bill line is carried into the estimate. */
    public enum LineKind {
        PER_UNIT, PERCENTAGE, FIXED, SUBSIDY
    }

    /** One mirrored charge line. */
    public static final class MirrorRow {
        /** Display label — the bill's own label where known. */
        public final String label;
        public final LineKind kind;
        /** Rs printed on the last bill (always >= 0). */
        public final double billAmount;
        /** Rs in the estimate (negative for SUBSIDY). */
        public final double estAmount;
        /**
         * PER_UNIT: Rs/unit rate; PERCENTAGE/SUBSIDY: percent;
         * FIXED: 0.
         */
        public final double aux;

        public MirrorRow(String label, LineKind kind, double billAmount,
                double estAmount, double aux) {
            this.label = label;
            this.kind = kind;
            this.billAmount = billAmount;
            this.estAmount = estAmount;
            this.aux = aux;
        }
    }

    /** Outcome of mirroring one bill onto an estimate. */
    public static final class MirrorResult {
        /** false => caller must use the legacy estimate path. */
        public boolean usable;
        /** Mirrored charge rows, bill order (known fields first). */
        public final List<MirrorRow> rows = new ArrayList<MirrorRow>();
        /**
         * Slab energy estimate for estUnits, CALIBRATED (v3.1.2) to
         * the bill's real Rs/unit basis: stale-table value x k where
         * k = bill's NET electricity basis / table(billUnits)
         * (v3.1.3: net of the Protected subsidy), applied only when
         * 0.25 <= k <= 4.0.
         */
        public double energyEstimate;
        /** Sum of FIXED-kind bill amounts (part of the tax base). */
        public double fixedTotal;
        /** energyEstimate + all mirrored rows (subsidy negative). */
        public double estimatedTotal;
        /**
         * One-time lines seen on the bill and deliberately NOT mirrored
         * (arrears, late surcharge, installment, ...) — for the UI note.
         */
        public int excludedCount;
        /**
         * v3.1.3: true when the bill's energy basis was netted down
         * from gross electricity charges via the printed net-charges
         * line or the subsidy line. The UI shows a small "after
         * subsidy (net)" note under the Energy row in this case.
         */
        public boolean subsidyNetted;
        /**
         * v3.2.0: true when a Protected estimate crossed the 200-unit
         * cliff — the consumer loses the subsidy AND the protected
         * slabs for this estimate. The UI shows a red warning banner
         * in this case.
         */
        public boolean crossedCliff;
    }

    /**
     * Tariff category from the bill's category text — same mapping as
     * MeterCheckCalc.compute() so the subsidy basis guard compares
     * like with like.
     *
     * v3.1.3: "Non-Protected" (any case/spelling) is NON_PROTECTED.
     * The old code checked contains("protected") first, which wrongly
     * matched "Non-Protected" as PROTECTED.
     */
    public static int tariffCat(String category) {
        if (category == null) return Tariff.NON_PROTECTED;
        String c = category.toLowerCase(Locale.US);
        if (c.contains("lifeline")) return Tariff.LIFELINE;
        // NB: non-protected spellings BEFORE "protected" — the
        // substring "protected" appears inside "non-protected".
        if (c.contains("non-protected") || c.contains("non protected")
                || c.contains("nonprotected")) {
            return Tariff.NON_PROTECTED;
        }
        if (c.contains("protected")) return Tariff.PROTECTED;
        return Tariff.NON_PROTECTED;
    }

    /**
     * Calibration factor basis/tableBase with the [0.25, 4.0]
     * parse-garbage guard. Returns -1 when the factor is unusable
     * (caller keeps the uncalibrated estimate).
     */
    private static double kOf(double basis, double tableBase) {
        if (!(tableBase > 0) || !(basis > 0)) return -1;
        double k = basis / tableBase;
        return (k >= 0.25 && k <= 4.0) ? k : -1;
    }

    /**
     * Mirrors the last bill's charges onto an estimate.
     *
     * @param bill           parsed last-month bill (may be null)
     * @param estUnits       units used since the bill (> 0)
     * @param energyEstimate slab-wise energy Rs for estUnits (>= 0)
     * @param tariffCat      Tariff.PROTECTED / NON_PROTECTED / LIFELINE
     * @return result with usable=false when there is no solid base
     */
    public static MirrorResult mirror(LescoBillFetcher.BillInfo bill,
            double estUnits, double energyEstimate, int tariffCat) {
        MirrorResult mr = new MirrorResult();
        mr.energyEstimate = energyEstimate;
        if (bill == null) return mr;
        double billUnits = MeterCheckCalc.parseReadingD(bill.units);
        double elec = MeterCheckCalc.parseAmount(bill.electricityCharges);
        if (!(billUnits > 0) || !(elec > 0) || !(estUnits > 0)
                || !(energyEstimate >= 0)) {
            return mr; // no solid base — caller keeps the legacy path
        }

        // ---- v3.1.3: NET energy basis. Protected bills print a GROSS
        // "Total Electricity Charges" line plus a "Subsidies" line and
        // a "Net Electricity Charges" line — the consumer actually pays
        // the NET basis, so calibration must use it. Prefer the printed
        // net line; else subtract the subsidy. Garbage values (net or
        // subsidy not strictly between 0 and elec) are ignored.
        double sub = MeterCheckCalc.parseAmount(bill.subsidy);
        double net = MeterCheckCalc.parseAmount(bill.netCharges);
        double elecNet = elec;
        if (net > 0 && net < elec) {
            elecNet = net;
        } else if (sub > 0 && sub < elec) {
            elecNet = elec - sub;
        }
        boolean netted = elecNet < elec;
        mr.subsidyNetted = netted;

        // ---- v3.1.2/v3.1.3: calibrate the slab table to the bill's
        // REAL energy basis (v3.1.3: the NET basis, see above). The
        // hardcoded slab table goes stale (NEPRA revises rates); the
        // bill's own charges are the truth for THIS consumer.
        //
        // v3.2.0 DUAL CALIBRATION: one k is not enough. A Protected
        // bill's net basis (e.g. kProt = 1729/1223.13 = 1.4136) must
        // NOT scale an estimate that crossed the 200-unit cliff into
        // Non-Protected slabs — above 200 the consumer pays full
        // NEPRA rates with no subsidy, whose drift is different
        // (e.g. kNp = 4280/2619.83 = 1.6337 from the bill's GROSS
        // electricity charges, which ARE at NEPRA rates). So:
        //   kProt: elecNet / table(billUnits, billCat)  (<=200 est.)
        //   kNp:   gross-or-net / table(billUnits, NON_PROTECTED)
        //        (gross for Protected bills; elecNet for the rest, so
        //        non-protected/lifeline bills behave exactly as v3.1.3)
        // A Protected estimate at/below 200 uses kProt; a Protected
        // estimate above 200 (cliff crossed) uses kNp; every other
        // bill keeps the single v3.1.3 factor. The [0.25, 4.0] clamp
        // is a parse-garbage guard — outside it we keep the
        // uncalibrated estimate rather than multiply garbage.
        boolean billProt = tariffCat == Tariff.PROTECTED;
        double tableProt = Tariff.energyCharges(billUnits, tariffCat);
        double tableNp = Tariff.energyCharges(billUnits,
                Tariff.NON_PROTECTED);
        double kProt = kOf(elecNet, tableProt);
        double kNp = kOf(billProt ? elec : elecNet, tableNp);
        double kOld = kOf(elecNet, tableProt);
        double k;
        if (billProt && billUnits <= 200 && estUnits <= 200) {
            k = kProt;
        } else if (billProt) {
            k = kNp; // cliff crossed (or bill itself >200): NEPRA basis
        } else {
            k = kOld; // v3.1.3 behavior, unchanged
        }
        double calEnergy = energyEstimate;
        if (k > 0) {
            calEnergy = energyEstimate * k;
        }
        mr.energyEstimate = calEnergy;
        // v3.2.0: UI hook — a Protected estimate that crossed the
        // 200-unit cliff loses the subsidy AND the protected slabs.
        mr.crossedCliff = billProt && estUnits > 200;

        // ---- collect candidate lines (bill order: known fields first)
        List<Cand> cands = new ArrayList<Cand>();
        addKnown(cands, "FPA", bill.fpa, LineKind.PER_UNIT);
        addKnown(cands, "GST", bill.gst, LineKind.PERCENTAGE);
        addKnown(cands, "Taxes", bill.taxes, LineKind.PERCENTAGE);
        addKnown(cands, "Subsidy", bill.subsidy, LineKind.SUBSIDY);
        if (bill.extraLines != null) {
            for (String[] pair : bill.extraLines) {
                if (pair == null || pair.length < 2) continue;
                String rawLabel = pair[0] == null ? "" : pair[0].trim();
                String norm = rawLabel.toLowerCase(Locale.US).trim();
                double amt = MeterCheckCalc.parseAmount(pair[1]);
                if (rawLabel.isEmpty() || !(amt > 0)) continue;
                if (isExcluded(norm)) {
                    mr.excludedCount++;
                    continue;
                }
                if (alreadyHave(cands, norm)) continue;
                cands.add(new Cand(rawLabel, classify(norm), amt, norm));
            }
        }
        // Known one-time fields present on the bill: counted for the UI
        // note (they are never mirrored).
        mr.excludedCount += countPresent(bill.arrears, bill.lpSurcharge,
                bill.installment, bill.adjustments, bill.weCredit,
                bill.lockOpenCredit);

        // ---- pass 1: FIXED totals + PER-UNIT estimates
        double fixedTotal = 0;
        double perUnitEstSum = 0;
        double fpaBill = 0;
        for (Cand c : cands) {
            if (c.kind == LineKind.FIXED) {
                c.est = c.billAmt;
                c.done = true;
                fixedTotal += c.billAmt;
            } else if (c.kind == LineKind.PER_UNIT) {
                c.rate = c.billAmt / billUnits; // billUnits > 0 (guarded)
                c.est = c.rate * estUnits;
                c.done = true;
                perUnitEstSum += c.est;
                if (c.normLabel.contains("fpa")
                        || c.normLabel.contains("fuel")) {
                    fpaBill += c.billAmt;
                }
            }
        }
        double billPreTax = elecNet + fixedTotal + fpaBill;
        // v3.1.2: the tax base starts from the CALIBRATED energy
        // (bill's real Rs/unit basis), not the stale slab table.
        // v3.1.3: the bill side of the base is the NET energy basis.
        double estPreTax = calEnergy + fixedTotal + perUnitEstSum;

        // ---- pass 2: PERCENTAGE + SUBSIDY
        // Subsidy basis guard: mirror only when the bill's energy basis
        // matches the estimator's slab basis (MF-aware). Otherwise the
        // slab rates already embody the subsidy — mirroring would
        // double-count it. v3.1.3: when the bill was netted, the
        // subsidy is already baked into the calibrated energy basis,
        // so SUBSIDY-kind candidates are skipped entirely (no rows).
        double mf = MeterCheckCalc.parseAmount(bill.mf);
        if (!(mf > 0)) mf = 1.0;
        double tariffBase = Tariff.energyCharges(billUnits, tariffCat) * mf;
        boolean subsidyBasisOk = tariffBase > 0
                && elecNet <= tariffBase * 1.15;
        for (Cand c : cands) {
            if (c.kind == LineKind.PERCENTAGE) {
                if (!(billPreTax > 0)) continue;
                c.rate = c.billAmt / billPreTax;
                c.est = c.rate * estPreTax;
                c.done = true;
            } else if (c.kind == LineKind.SUBSIDY) {
                if (netted) continue; // baked into the net basis
                if (!subsidyBasisOk) continue;
                c.rate = c.billAmt / elecNet; // elecNet > 0 (guarded)
                // v3.1.2: subsidy scales with the calibrated energy.
                c.est = -c.rate * calEnergy;
                c.done = true;
            }
        }

        // ---- build rows
        // v3.1.2: total starts from the calibrated energy basis.
        double total = calEnergy;
        for (Cand c : cands) {
            if (!c.done) continue;
            double aux = 0;
            if (c.kind == LineKind.PER_UNIT) aux = c.rate;
            else if (c.kind == LineKind.PERCENTAGE
                    || c.kind == LineKind.SUBSIDY) aux = c.rate * 100.0;
            mr.rows.add(new MirrorRow(c.label, c.kind, c.billAmt, c.est,
                    aux));
            total += c.est;
        }
        mr.fixedTotal = fixedTotal;
        mr.estimatedTotal = total;
        // A bill with zero parseable charge lines is treated as "no
        // usable tax detail" — the legacy 18%-fallback path is the
        // honest answer there.
        mr.usable = !mr.rows.isEmpty();
        return mr;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** One classified candidate line before estimation. */
    private static final class Cand {
        final String label;
        final String normLabel;
        final LineKind kind;
        final double billAmt;
        double rate; // per-unit Rs, or fraction for PERCENTAGE/SUBSIDY
        double est;
        boolean done;
        Cand(String label, LineKind kind, double billAmt,
                String normLabel) {
            this.label = label;
            this.kind = kind;
            this.billAmt = billAmt;
            this.normLabel = normLabel;
        }
    }

    private static void addKnown(List<Cand> cands, String label,
            String rawValue, LineKind kind) {
        double amt = MeterCheckCalc.parseAmount(rawValue);
        if (!(amt > 0)) return;
        cands.add(new Cand(label, kind, amt,
                label.toLowerCase(Locale.US)));
    }

    private static boolean alreadyHave(List<Cand> cands, String norm) {
        for (Cand c : cands) {
            if (c.normLabel.equals(norm)) return true;
        }
        return false;
    }

    private static int countPresent(String... vals) {
        int n = 0;
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) n++;
        }
        return n;
    }

    /**
     * Labels that must NEVER be mirrored: one-time items (arrears,
     * late fees, installments, adjustments, credits, previous balance)
     * and total/summary/identity rows.
     */
    private static final String[] EXCLUDE_KEYS = {
        "arrear", "late", "installment", "credit",
        "previous balance", "payable", "grand total", "net charges",
        "current bill", "total", "due date", "bill month", "bill date",
        "issue date", "reading", "units", "consumption", "reference",
        "consumer", "customer", "name", "address", "tariff", "category",
        "feeder", "division", "subdivision", "sanctioned", "san load",
        "multiplying", "connection", "month"
    };

    private static boolean isExcluded(String norm) {
        // "adjustment" is one-time — but never a fuel adjustment, which
        // is a genuine per-unit charge (FCA).
        if (norm.contains("adjustment") && !norm.contains("fuel")) {
            return true;
        }
        for (String k : EXCLUDE_KEYS) {
            if (norm.contains(k)) return true;
        }
        return false;
    }

    private static boolean containsAny(String norm, String... keys) {
        for (String k : keys) {
            if (norm.contains(k)) return true;
        }
        return false;
    }

    /**
     * Classifies an extraLines label. Order matters: subsidy first,
     * then per-unit markers, then tax markers; everything else is a
     * fixed charge (the user's rule: mirror it as-is, never invent).
     */
    private static LineKind classify(String norm) {
        if (norm.contains("subsid")) return LineKind.SUBSIDY;
        if (containsAny(norm, "fpa", "fuel price", "fuel cost", "per unit",
                "/unit", "rs/unit")) {
            return LineKind.PER_UNIT;
        }
        // "late"-style surcharges are excluded above; a surviving
        // surcharge (NJ / FC / ...) is a per-unit surcharge.
        if (norm.contains("surcharge")) return LineKind.PER_UNIT;
        if (containsAny(norm, "gst", "sales tax", "income tax",
                "extra tax", "further tax", "withholding tax",
                "withholding", "tax")) {
            return LineKind.PERCENTAGE;
        }
        return LineKind.FIXED;
    }
}
