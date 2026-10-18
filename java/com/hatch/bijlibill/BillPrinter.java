package com.hatch.bijlibill;

/**
 * v2.0.5 — PRINT / SAVE PDF for the bill screen. The activity hands
 * over the WebView's print adapter; Android's PrintManager then offers
 * printers and "Save as PDF".
 *
 * Pure Java (no Android APIs): the activity adapts the real
 * PrintManager to {@link PrintService}; headless tests assert the job
 * name and the null guards.
 */
public final class BillPrinter {

    private BillPrinter() {}

    /** Print job name shown in the system print dialog. */
    public static final String JOB_NAME = "Bijli Bill";

    /** Minimal print-service surface (test seam). */
    public interface PrintService {
        void print(String jobName, Object printAdapter);
    }

    /**
     * Starts the print job. Never touches Android classes directly —
     * the activity's PrintService adapter does that.
     *
     * @throws IllegalArgumentException when service or adapter is null
     */
    public static void print(PrintService service, Object printAdapter) {
        if (service == null) {
            throw new IllegalArgumentException("print service missing");
        }
        if (printAdapter == null) {
            throw new IllegalArgumentException("print adapter missing");
        }
        service.print(JOB_NAME, printAdapter);
    }
}
