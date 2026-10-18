package com.hatch.bijlibill;

/**
 * v2.3.0 DOWNLOAD (no system print dialog) — pure helpers, no Android
 * dependencies. Headless-unit-testable (see TestBillFetch.runV230Tests).
 *
 * The Android side (BillActivity.downloadBill) renders the WebView
 * page-by-page into a PrintedPdfDocument with an A4 aspect ratio and
 * saves it under Downloads/BijliBill via MediaStore.
 */
public final class BillPdf {

    private BillPdf() {}

    /**
     * "bijli-bill-02118240618604-20260920.pdf". Non-digits are stripped
     * from the reference (the typed value may carry a U/R suffix);
     * empty -> "bill". yyyymmdd must be 8 digits, else "00000000".
     */
    public static String fileName(String refDigits, String yyyymmdd) {
        String d = refDigits == null ? ""
                : refDigits.replaceAll("[^0-9]", "");
        if (d.isEmpty()) d = "bill";
        String stamp = (yyyymmdd != null && yyyymmdd.matches("\\d{8}"))
                ? yyyymmdd : "00000000";
        return "bijli-bill-" + d + "-" + stamp + ".pdf";
    }

    /**
     * How many pageHeightPx pages contentHeightPx of content needs.
     * Always at least 1.
     */
    public static int pageCount(int contentHeightPx, int pageHeightPx) {
        if (pageHeightPx <= 0) return 1;
        int c = (contentHeightPx + pageHeightPx - 1) / pageHeightPx;
        return Math.max(1, c);
    }
}
