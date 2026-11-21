package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v3.7.31 — AI Assistant brain (v1, SIMPLE). Rule-based Q&A over the
 * locally saved bill snapshots — no internet, no API key, no LLM,
 * no cost, fully on-device. All answers are in simple Roman Urdu
 * (v3.7.33: Roman Punjabi too — detected and answered in the
 * user's own language).
 *
 * v3.7.33: Roman Punjabi is detected (isPunjabi) and answered in
 * the user's own language — same numbers, Punjabi wording. TTS
 * stays ur-PK (Punjabi TTS data is not on every phone; the ur-PK
 * voice reads Roman Punjabi acceptably).
 *
 * Pure Java, no Android dependencies — headless-unit-testable.
 * The Activity owns speech (SpeechRecognizer / TextToSpeech) and
 * the snapshot loading; this class only decides WHAT to say.
 *
 * NOTE on protected status: the bill category string is checked
 * for "non-protected" FIRST — "Non-Protected" contains the word
 * "protected" as a substring, and the old contains("protected")
 * check elsewhere in the app gets this wrong (open display bug,
 * 2026-09-20). This class does it correctly.
 */
public final class AssistantBrain {

    private AssistantBrain() {}

    /** Quick questions shown as tappable chips on the chat screen. */
    public static List<String> suggestions(boolean hasBill) {
        List<String> out = new ArrayList<String>();
        if (hasBill) {
            out.add("Mera bill kyun zyada aaya?");
            out.add("Mera bill kab due hai?");
            out.add("200 unit guard kya kehta hai?");
        } else {
            out.add("Mera bill kyun zyada aaya?");
            out.add("Mera bill kab due hai?");
            out.add("Main kya pooch sakta hun?");
        }
        return out;
    }

    /** Opening message when the chat screen opens. */
    public static String greeting(List<BillSnapshots.Snapshot> snaps,
            String meterLabel) {
        if (snaps == null || snaps.isEmpty()) {
            return "Assalamualaikum! Main aapka Bijli Bill Assistant hun. "
                    + "Abhi tak koi bill save nahi hua — pehle Home se "
                    + "apna bill fetch karain, phir mujh se poochein. "
                    + "Main aapko bata sakta hun ke bill kyun zyada aaya, "
                    + "bill kab due hai, aur 200 unit guard kya kehta hai.";
        }
        BillSnapshots.Snapshot cur = snaps.get(0);
        String label = meterLabel == null ? "" : meterLabel;
        StringBuilder sb = new StringBuilder();
        sb.append("Assalamualaikum! Main aapka Bijli Bill Assistant hun. ");
        if (!label.isEmpty()) {
            sb.append("Main ").append(label).append(" ke bill se jawab dunga. ");
        }
        sb.append("Aap mujh se pooch sakte hain: bill kyun zyada aaya, "
                + "bill kab due hai, ya 200 unit guard kya kehta hai. ");
        if (cur.units >= 0) {
            sb.append("Is mahine ke bill mein ")
              .append(cur.units).append(" unit hain");
            if (cur.amount >= 0) {
                sb.append(", Rs ").append(fmtRs(cur.amount));
            }
            sb.append(".");
        }
        return sb.toString();
    }

    /**
     * Answers a free-text question from the snapshots (newest first).
     * Never returns null; unknown questions get the help text.
     *
     * v3.7.33: Roman Punjabi is detected too (isPunjabi) and answered
     * in the user's own language — same numbers, Punjabi wording.
     * TTS stays ur-PK (Punjabi TTS data is not on every phone; the
     * ur-PK voice reads Roman Punjabi acceptably).
     */
    public static String answer(String question,
            List<BillSnapshots.Snapshot> snaps, String meterLabel) {
        String q = question == null ? "" : question.trim().toLowerCase(
                Locale.US);
        if (q.isEmpty()) return help(snaps, false);
        boolean pa = isPunjabi(q);
        if (snaps == null || snaps.isEmpty()) {
            // NOTE: no bare "hi" keyword — it matches inside "mahine"
        // ("ma-hi-ne") and would hijack real questions. Greeting
        // works via "salam"/"hello" instead.
        if (containsAny(q, "salam", "hello", "help", "madad",
                    "kya kar", "kya pooch", "tum kaun", "kaun ho",
                    "ki haal", "kiddan", "sat sri akal")) {
                return pa ? greetingPa() : greeting(snaps, meterLabel);
            }
            if (pa) {
                return "Aje tak koi bill save nahi hoya, is layi main "
                        + "jawab nahi de sakda. Pehlan Home to apna bill "
                        + "fetch karo, phir mainu pucho — main das dunga "
                        + "bill kyun wadh gaya, aakhri tareekh kado hai, "
                        + "te 200 unit guard ki kehnda hai.";
            }
            return "Abhi tak koi bill save nahi hua, is liye main jawab "
                    + "nahi de sakta. Pehle Home se apna bill fetch karain, "
                    + "phir mujh se poochein — main aapko bata dunga ke bill "
                    + "kyun zyada aaya, bill kab due hai, aur 200 unit guard "
                    + "kya kehta hai.";
        }
        BillSnapshots.Snapshot cur = snaps.get(0);
        BillSnapshots.Snapshot prev =
                snaps.size() > 1 ? snaps.get(1) : null;

        if (containsAny(q, "salam", "hello", "help", "madad",
                "kya kar", "kya pooch", "tum kaun", "kaun ho",
                "ki haal", "kiddan", "sat sri akal")) {
            return help(snaps, pa);
        }
        if (containsAny(q, "due", "aakhri", "akhri", "last date",
                "kab tak", "kab dena", "jama", "pay karna",
                "kado", "aakhri tareekh")) {
            return dueAnswer(cur, pa);
        }
        if (containsAny(q, "pichl", "pehle", "guzra", "last month",
                "last bill", "pichle mahine", "kinna si")) {
            return prevAnswer(prev, pa);
        }
        if (containsAny(q, "guard", "200", "protected", "limit",
                "bacha", "bachao", "kehnda", "kehndi")) {
            return guardAnswer(cur, pa);
        }
        if (containsAny(q, "zyada", "ziyada", "barh", "mehnga",
                "mehanga", "kyun", "kyon", "why",
                "wadh", "kiyon", "waja")) {
            return whyHighAnswer(cur, prev, pa);
        }
        if (containsAny(q, "fpa", "fuel")) {
            return fpaAnswer(cur, pa);
        }
        if (containsAny(q, "unit", "units", "kinne")) {
            return unitsAnswer(cur, prev, pa);
        }
        if (containsAny(q, "kitna", "kitne", "amount", "raqam",
                "payable", "bill", "kinna")) {
            return currentBillAnswer(cur, pa);
        }
        return help(snaps, pa);
    }

    /**
     * v3.7.33: true when the question is Roman Punjabi. Matches
     * whole words only (padded search) so a marker never fires
     * inside an Urdu word — e.g. "ki" inside "kitna". Public so
     * the headless tests (default package) can call it directly.
     */
    public static boolean isPunjabi(String q) {
        if (q == null) return false;
        String p = " " + q.trim().toLowerCase(Locale.US)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ") + " ";
        return containsAny(p, " ki haal ", " kiddan ", " tusi ",
                " tuhada ", " tuhadi ", " kinna ", " kinne ",
                " kinni ", " wadh ", " kiyon ", " kado ",
                " ki gal ", " dasso ", " daso ", " kehnda ",
                " kehndi ", " sanu ", " mainu ", " sat sri akal ");
    }

    /** Short Punjabi greeting (no bill saved yet). */
    static String greetingPa() {
        return "Sat Sri Akal! Main tuhada Bijli Bill Assistant haan. "
                + "Aje tak koi bill save nahi hoya — pehlan Home to "
                + "apna bill fetch karo, phir mainu pucho. Main das "
                + "sakda haan bill kyun wadh gaya, aakhri tareekh kado "
                + "hai, te 200 unit guard ki kehnda hai.";
    }

    // ------------------------------------------------------------------
    // Answers
    // ------------------------------------------------------------------

    private static String help(List<BillSnapshots.Snapshot> snaps,
            boolean pa) {
        StringBuilder sb = new StringBuilder();
        if (pa) {
            sb.append("Main sirf tuhade save kitte bills to jawab de "
                    + "sakda haan. ");
            if (snaps == null || snaps.isEmpty()) {
                sb.append("Aje koi bill save nahi hai — pehlan Home to "
                        + "apna bill fetch karo.");
                return sb.toString();
            }
            sb.append("Tusi eh puch sakde ho:\n"
                    + "- Mera bill kyun wadh gaya?\n"
                    + "- Mere bill di aakhri tareekh kado hai?\n"
                    + "- Pichla bill kinna si?\n"
                    + "- 200 unit guard ki kehnda hai?\n"
                    + "- Is mahine kinne unit lage?");
            return sb.toString();
        }
        sb.append("Main sirf aapke save kiye hue bills se jawab de sakta hun. ");
        if (snaps == null || snaps.isEmpty()) {
            sb.append("Abhi koi bill save nahi hai — pehle Home se apna "
                    + "bill fetch karain.");
            return sb.toString();
        }
        sb.append("Aap ye pooch sakte hain:\n"
                + "- Mera bill kyun zyada aaya?\n"
                + "- Mera bill kab due hai?\n"
                + "- Pichle mahine ka bill kitna tha?\n"
                + "- 200 unit guard kya kehta hai?\n"
                + "- Is mahine kitne unit use hue?");
        return sb.toString();
    }

    private static String currentBillAnswer(BillSnapshots.Snapshot cur,
            boolean pa) {
        StringBuilder sb = new StringBuilder();
        if (pa) {
            sb.append("Tuhada bill");
            if (!isEmpty(cur.billMonth)) {
                sb.append(" (").append(cur.billMonth).append(")");
            }
            sb.append(": ");
            boolean said = false;
            if (cur.units >= 0) {
                sb.append(cur.units).append(" unit");
                said = true;
            }
            if (cur.amount >= 0) {
                if (said) sb.append(", ");
                sb.append("Rs ").append(fmtRs(cur.amount));
                said = true;
            }
            if (!said) {
                sb.append("da mukammal record save nahi hoya");
            }
            sb.append(".");
            if (!isEmpty(cur.dueDate)) {
                sb.append(" Aakhri tareekh ").append(cur.dueDate)
                  .append(" hai.");
            }
            return sb.toString();
        }
        sb.append("Aapka bill");
        if (!isEmpty(cur.billMonth)) {
            sb.append(" (").append(cur.billMonth).append(")");
        }
        sb.append(": ");
        boolean said = false;
        if (cur.units >= 0) {
            sb.append(cur.units).append(" unit");
            said = true;
        }
        if (cur.amount >= 0) {
            if (said) sb.append(", ");
            sb.append("Rs ").append(fmtRs(cur.amount));
            said = true;
        }
        if (!said) {
            sb.append("ka mukammal record save nahi hua");
        }
        sb.append(".");
        if (!isEmpty(cur.dueDate)) {
            sb.append(" Due date ").append(cur.dueDate).append(" hai.");
        }
        return sb.toString();
    }

    private static String whyHighAnswer(BillSnapshots.Snapshot cur,
            BillSnapshots.Snapshot prev, boolean pa) {
        StringBuilder sb = new StringBuilder();
        if (pa) {
            if (prev != null && prev.units >= 0 && cur.units >= 0) {
                int delta = cur.units - prev.units;
                sb.append("Is mahine ").append(cur.units)
                  .append(" unit lage, pichle mahine ").append(prev.units)
                  .append(" si — ");
                if (delta > 0) {
                    int pct = prev.units > 0
                            ? Math.round(delta * 100.0f / prev.units) : 0;
                    sb.append(delta).append(" unit zyada");
                    if (pct > 0) sb.append(" (").append(pct).append("%)");
                    sb.append(".");
                } else if (delta < 0) {
                    sb.append(-delta).append(" unit GHAT gaye — bill "
                            + "wadhan di waja unit nahi, thalle vekho.");
                } else {
                    sb.append("unit barabar ne — bill wadhan di waja "
                            + "unit nahi, thalle vekho.");
                }
                sb.append(" ");
            } else if (cur.units >= 0) {
                sb.append("Is mahine ").append(cur.units)
                  .append(" unit lage. Pichle mahine da bill save nahi "
                          + "hai, is layi farq nahi das sakda — agle "
                          + "mahine to farq vi das dunga. ");
            }
            double fpa = BillSnapshots.parseMoney(cur.fpa);
            if (fpa > 0) {
                sb.append("Is bill vich FPA Rs ").append(fmtRs(fpa))
                  .append(" hai. ");
                if (prev != null) {
                    double pf = BillSnapshots.parseMoney(prev.fpa);
                    if (pf >= 0 && fpa > pf) {
                        sb.append("Pichle mahine FPA Rs ").append(fmtRs(pf))
                          .append(" si, is layi bill wadh gaya. ");
                    }
                }
            }
            String prot = protectedWord(cur.category);
            if (cur.units > 200) {
                if (prev != null && prev.units >= 0 && prev.units <= 200) {
                    sb.append("Tusi 200 unit to upar chale gaye — "
                            + "Protected status khatam ho gaya, is layi "
                            + "mehenge slab lage.");
                } else if ("Protected".equals(prot)) {
                    sb.append("Bill te category Protected likhi hai, "
                            + "lekin unit 200 to upar ne — agle mahine "
                            + "Protected status khatam ho sakda hai.");
                } else {
                    sb.append("Unit 200 to upar ne, is layi mehenge "
                            + "slab lage.");
                }
            } else if (cur.units >= 0 && "Protected".equals(prot)) {
                sb.append("Tusi aje vi Protected ho (200 unit to thalle), "
                        + "is layi saste slab lage.");
            }
            if (prev != null && prev.amount >= 0 && cur.amount >= 0) {
                double d = cur.amount - prev.amount;
                if (d > 0) {
                    sb.append(" Bill Rs ").append(fmtRs(cur.amount))
                      .append(" hai, pichle mahine Rs ")
                      .append(fmtRs(prev.amount)).append(" si.");
                } else if (d < 0) {
                    sb.append(" Bill asal vich Rs ").append(fmtRs(cur.amount))
                      .append(" hai — pichle mahine Rs ")
                      .append(fmtRs(prev.amount))
                      .append(" si, is layi bill ghat gaya hai.");
                }
            } else if (cur.amount >= 0) {
                sb.append(" Bill Rs ").append(fmtRs(cur.amount))
                  .append(" hai.");
            }
            return sb.toString().trim();
        }
        if (prev != null && prev.units >= 0 && cur.units >= 0) {
            int delta = cur.units - prev.units;
            sb.append("Is mahine ").append(cur.units)
              .append(" unit use hue, pichle mahine ").append(prev.units)
              .append(" the — ");
            if (delta > 0) {
                int pct = prev.units > 0
                        ? Math.round(delta * 100.0f / prev.units) : 0;
                sb.append(delta).append(" unit zyada");
                if (pct > 0) sb.append(" (").append(pct).append("%)");
                sb.append(".");
            } else if (delta < 0) {
                sb.append(-delta).append(" unit KAM hue — bill zyada "
                        + "hone ki wajah units nahi, neeche dekhein.");
            } else {
                sb.append("units barabar hain — bill zyada hone ki wajah "
                        + "units nahi, neeche dekhein.");
            }
            sb.append(" ");
        } else if (cur.units >= 0) {
            sb.append("Is mahine ").append(cur.units)
              .append(" unit use hue. Pichle mahine ka bill save nahi hai, "
                      + "is liye farq nahi bata sakta — agle mahine se farq "
                      + "bhi bata dunga. ");
        }
        // FPA
        double fpa = BillSnapshots.parseMoney(cur.fpa);
        if (fpa > 0) {
            sb.append("Is bill mein FPA Rs ").append(fmtRs(fpa))
              .append(" hai. ");
            if (prev != null) {
                double pf = BillSnapshots.parseMoney(prev.fpa);
                if (pf >= 0 && fpa > pf) {
                    sb.append("Pichle mahine FPA Rs ").append(fmtRs(pf))
                      .append(" tha, is liye bill badha. ");
                }
            }
        }
        // Protected cliff
        String prot = protectedWord(cur.category);
        if (cur.units > 200) {
            if (prev != null && prev.units >= 0 && prev.units <= 200) {
                sb.append("Aap 200 unit se upar chale gaye — Protected "
                        + "status khatam ho gaya, is liye mehngay slab lage.");
            } else if ("Protected".equals(prot)) {
                sb.append("Bill par category Protected likhi hai, lekin "
                        + "units 200 se upar hain — agle mahine Protected "
                        + "status khatam ho sakta hai.");
            } else {
                sb.append("Units 200 se upar hain, is liye mehngay slab lage.");
            }
        } else if (cur.units >= 0 && "Protected".equals(prot)) {
            sb.append("Aap abhi bhi Protected hain (200 unit se neeche), "
                    + "is liye saste slab lage.");
        }
        // Amount comparison
        if (prev != null && prev.amount >= 0 && cur.amount >= 0) {
            double d = cur.amount - prev.amount;
            if (d > 0) {
                sb.append(" Bill Rs ").append(fmtRs(cur.amount))
                  .append(" hai, pichle mahine Rs ").append(fmtRs(prev.amount))
                  .append(" tha.");
            } else if (d < 0) {
                sb.append(" Bill asal mein Rs ").append(fmtRs(cur.amount))
                  .append(" hai — pichle mahine Rs ")
                  .append(fmtRs(prev.amount))
                  .append(" tha, is liye bill kam hua hai.");
            }
        } else if (cur.amount >= 0) {
            sb.append(" Bill Rs ").append(fmtRs(cur.amount)).append(" hai.");
        }
        return sb.toString().trim();
    }

    private static String dueAnswer(BillSnapshots.Snapshot cur,
            boolean pa) {
        if (isEmpty(cur.dueDate)) {
            if (pa) {
                return "Is bill di aakhri tareekh save nahi hoyi. Bill "
                        + "dobara fetch karo te main aakhri tareekh vi "
                        + "das dunga.";
            }
            return "Is bill ki due date save nahi hui. Bill dobara fetch "
                    + "karain to main due date bhi bata dunga.";
        }
        StringBuilder sb = new StringBuilder();
        if (pa) {
            sb.append("Tuhade bill di aakhri tareekh ").append(cur.dueDate)
              .append(" hai. ");
            String iso = GuardReminder.parseBillDate(cur.dueDate);
            if (iso != null) {
                int days = GuardReminder.daysBetween(
                        GuardReminder.todayIso(), iso);
                if (days > 0) {
                    sb.append(days).append(days == 1 ? " din reh gaya hai."
                            : " din reh gaye ne.");
                } else if (days == 0) {
                    sb.append("Ajj aakhri din hai — ajj hi jama karwa deyo.");
                } else {
                    sb.append("Aakhri tareekh ").append(-days)
                      .append(-days == 1 ? " din pehlan langh gayi hai."
                              : " din pehlan langh gayi hai.")
                      .append(" Late fee to bachan layi jald jama "
                              + "karwa deyo.");
                }
                sb.append(" ");
            }
            if (cur.amountAfterDue >= 0 && cur.amount >= 0
                    && cur.amountAfterDue > cur.amount) {
                sb.append("Aakhri tareekh to baad Rs ")
                  .append(fmtRs(cur.amountAfterDue - cur.amount))
                  .append(" extra lagange, total Rs ")
                  .append(fmtRs(cur.amountAfterDue)).append(" ho jayega.");
            }
            return sb.toString().trim();
        }
        sb.append("Aapke bill ki due date ").append(cur.dueDate)
          .append(" hai. ");
        String iso = GuardReminder.parseBillDate(cur.dueDate);
        if (iso != null) {
            int days = GuardReminder.daysBetween(
                    GuardReminder.todayIso(), iso);
            if (days > 0) {
                sb.append(days).append(days == 1 ? " din reh gaya hai."
                        : " din reh gaye hain.");
            } else if (days == 0) {
                sb.append("Aaj aakhri din hai — aaj hi jama karwa dein.");
            } else {
                sb.append("Due date ").append(-days)
                  .append(-days == 1 ? " din pehle guzar gayi hai."
                          : " din pehle guzar gayi hai.")
                  .append(" Late fee se bachne ke liye jald jama karwa dein.");
            }
            sb.append(" ");
        }
        if (cur.amountAfterDue >= 0 && cur.amount >= 0
                && cur.amountAfterDue > cur.amount) {
            sb.append("Due date ke baad Rs ")
              .append(fmtRs(cur.amountAfterDue - cur.amount))
              .append(" extra lagenge, total Rs ")
              .append(fmtRs(cur.amountAfterDue)).append(" ho jayega.");
        }
        return sb.toString().trim();
    }

    private static String prevAnswer(BillSnapshots.Snapshot prev,
            boolean pa) {
        if (prev == null) {
            if (pa) {
                return "Pichle mahine da bill save nahi hai. Jadon agla "
                        + "bill fetch hovega te main pichla bill vi yaad "
                        + "rakhanga.";
            }
            return "Pichle mahine ka bill save nahi hai. Jab agla bill "
                    + "fetch hoga to main pichla bill bhi yaad rakhunga.";
        }
        StringBuilder sb = new StringBuilder();
        if (pa) {
            sb.append("Pichle mahine");
            if (!isEmpty(prev.billMonth)) {
                sb.append(" (").append(prev.billMonth).append(")");
            }
            sb.append(" da bill ");
            boolean said = false;
            if (prev.amount >= 0) {
                sb.append("Rs ").append(fmtRs(prev.amount));
                said = true;
            }
            if (prev.units >= 0) {
                if (said) sb.append(", ");
                sb.append(prev.units).append(" unit");
                said = true;
            }
            if (!said) sb.append("da mukammal record save nahi hoya");
            sb.append(" si.");
            return sb.toString();
        }
        sb.append("Pichle mahine");
        if (!isEmpty(prev.billMonth)) {
            sb.append(" (").append(prev.billMonth).append(")");
        }
        sb.append(" ka bill ");
        boolean said = false;
        if (prev.amount >= 0) {
            sb.append("Rs ").append(fmtRs(prev.amount));
            said = true;
        }
        if (prev.units >= 0) {
            if (said) sb.append(", ");
            sb.append(prev.units).append(" unit");
            said = true;
        }
        if (!said) sb.append("ka mukammal record save nahi hua");
        sb.append(" tha.");
        return sb.toString();
    }

    private static String guardAnswer(BillSnapshots.Snapshot cur,
            boolean pa) {
        if (cur.units < 0) {
            if (pa) {
                return "Guard layi is bill de unit save nahi hoye. Bill "
                        + "dobara fetch karo te main guard vi das dunga.";
            }
            return "Guard ke liye is bill ke units save nahi hue. Bill "
                    + "dobara fetch karain to main guard bhi bata dunga.";
        }
        int daysElapsed = 0;
        String rIso = GuardReminder.parseBillDate(cur.readingDate);
        if (rIso != null) {
            daysElapsed = GuardReminder.daysBetween(
                    rIso, GuardReminder.todayIso());
            if (daysElapsed < 0) daysElapsed = 0;
            if (daysElapsed > 30) daysElapsed = 30;
        }
        GuardCalc.Projection p = GuardCalc.autoProjectFromBill(
                cur.units, GuardCalc.DEFAULT_CYCLE_DAYS, daysElapsed);
        StringBuilder sb = new StringBuilder();
        if (pa) {
            sb.append("200-Unit Guard (andaza hai, asal reading naal "
                    + "badal sakda hai): ");
            sb.append("is mahine de aakhir tak andazan ")
              .append(Math.round(p.projectedUnits))
              .append(" unit ho jange. ");
            if (p.status == GuardCalc.Status.DANGER) {
                sb.append("Khatra hai — tusi 200 unit cross karan wale "
                        + "ho, Protected status khatam ho jayega. ");
            } else if (p.status == GuardCalc.Status.WARNING) {
                sb.append("Tusi 200 de bohat nere ho — ehtiyat karo. ");
            } else {
                sb.append("Tusi mehfooz ho, 200 to thalle rehan da "
                        + "andaza hai. ");
            }
            if (p.remainingDays > 0 && p.dailyBudget > 0) {
                sb.append("Baaki ").append(p.remainingDays)
                  .append(" din layi rozana ")
                  .append(fmt1(p.dailyBudget))
                  .append(" unit to zyada na varton te 200 to thalle "
                          + "raho ge.");
            } else if (p.remainingDays <= 0) {
                sb.append("Billing cycle khatam hon wala hai.");
            }
            return sb.toString().trim();
        }
        sb.append("200-Unit Guard (andaza hai, asal reading se badal "
                + "sakta hai): ");
        sb.append("is mahine ke aakhir tak andazan ")
          .append(Math.round(p.projectedUnits)).append(" unit ho jayenge. ");
        if (p.status == GuardCalc.Status.DANGER) {
            sb.append("Khatra hai — aap 200 unit cross karne wale hain, "
                    + "Protected status khatam ho jayega. ");
        } else if (p.status == GuardCalc.Status.WARNING) {
            sb.append("Aap 200 ke bohat kareeb hain — ehtiyat karain. ");
        } else {
            sb.append("Aap mehfooz hain, 200 se neeche rahne ka andaza hai. ");
        }
        if (p.remainingDays > 0 && p.dailyBudget > 0) {
            sb.append("Baaki ").append(p.remainingDays)
              .append(" din ke liye rozana ")
              .append(fmt1(p.dailyBudget))
              .append(" unit se zyada use na karain to 200 se neeche "
                      + "raho ge.");
        } else if (p.remainingDays <= 0) {
            sb.append("Billing cycle khatam hone wala hai.");
        }
        return sb.toString().trim();
    }

    private static String fpaAnswer(BillSnapshots.Snapshot cur,
            boolean pa) {
        double fpa = BillSnapshots.parseMoney(cur.fpa);
        if (fpa < 0) {
            if (pa) {
                return "Is bill vich FPA save nahi hoya. FPA fuel price "
                        + "adjustment hunda hai — bijli banan wale fuel "
                        + "di qeemat badlan te bill vich lagda hai.";
            }
            return "Is bill mein FPA save nahi hua. FPA fuel price "
                    + "adjustment hota hai — bijli banane wale fuel ki qeemat "
                    + "badalne par bill mein lagta hai.";
        }
        if (pa) {
            return "Is bill vich FPA Rs " + fmtRs(fpa) + " hai. FPA fuel "
                    + "price adjustment hunda hai — bijli banan wale "
                    + "fuel di qeemat badlan te bill vich lagda hai.";
        }
        return "Is bill mein FPA Rs " + fmtRs(fpa) + " hai. FPA fuel price "
                + "adjustment hota hai — bijli banane wale fuel ki qeemat "
                + "badalne par bill mein lagta hai.";
    }

    private static String unitsAnswer(BillSnapshots.Snapshot cur,
            BillSnapshots.Snapshot prev, boolean pa) {
        if (cur.units < 0) {
            if (pa) {
                return "Is bill de unit save nahi hoye. Bill dobara "
                        + "fetch karo te main unit vi das dunga.";
            }
            return "Is bill ke units save nahi hue. Bill dobara fetch "
                    + "karain to main units bhi bata dunga.";
        }
        StringBuilder sb = new StringBuilder();
        if (pa) {
            sb.append("Is mahine ").append(cur.units).append(" unit lage");
            if (!isEmpty(cur.prevReading) && !isEmpty(cur.currReading)) {
                sb.append(" — pichli reading ").append(cur.prevReading)
                  .append(", ajj di reading ").append(cur.currReading);
            }
            sb.append(".");
            if (prev != null && prev.units >= 0) {
                int delta = cur.units - prev.units;
                if (delta > 0) {
                    sb.append(" Pichle mahine to ").append(delta)
                      .append(" unit zyada.");
                } else if (delta < 0) {
                    sb.append(" Pichle mahine to ").append(-delta)
                      .append(" unit ghat.");
                } else {
                    sb.append(" Pichle mahine de barabar.");
                }
            }
            return sb.toString();
        }
        sb.append("Is mahine ").append(cur.units).append(" unit use hue");
        if (!isEmpty(cur.prevReading) && !isEmpty(cur.currReading)) {
            sb.append(" — pichli reading ").append(cur.prevReading)
              .append(", aaj ki reading ").append(cur.currReading);
        }
        sb.append(".");
        if (prev != null && prev.units >= 0) {
            int delta = cur.units - prev.units;
            if (delta > 0) {
                sb.append(" Pichle mahine se ").append(delta)
                  .append(" unit zyada.");
            } else if (delta < 0) {
                sb.append(" Pichle mahine se ").append(-delta)
                  .append(" unit kam.");
            } else {
                sb.append(" Pichle mahine ke barabar.");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers (package-visible for tests)
    // ------------------------------------------------------------------

    /** "Protected" / "Non-Protected" / "" — non-protected is checked
     *  FIRST because it contains "protected" as a substring. */
    static String protectedWord(String category) {
        if (category == null) return "";
        String c = category.toLowerCase(Locale.US);
        if (c.contains("non-protected") || c.contains("non protected")) {
            return "Non-Protected";
        }
        if (c.contains("protected")) return "Protected";
        return "";
    }

    static boolean containsAny(String hay, String... needles) {
        if (hay == null) return false;
        for (String n : needles) {
            if (n != null && hay.contains(n)) return true;
        }
        return false;
    }

    static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** "Rs 12,345" — manual comma grouping, locale-independent. */
    static String fmtRs(double v) {
        long r = Math.round(v);
        String s = Long.toString(Math.abs(r));
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) sb.append(',');
            sb.append(s.charAt(i));
        }
        return (r < 0 ? "-" : "") + sb.toString();
    }

    /** One decimal place, e.g. 6.5 */
    static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
