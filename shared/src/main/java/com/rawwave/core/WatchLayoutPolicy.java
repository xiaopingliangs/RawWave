package com.rawwave.core;

/**
 * Round-watch layout policy shared by the measurement page and the scrollable pages.
 *
 * <p>The panel is a circle, not a rectangle. The old policy split the full height into bands and
 * kept a rectangular width, so content near the top/bottom edge was cut by the bezel even when
 * the percentages were "correct". This policy keeps the same percentage hierarchy but also
 * checks every band against the actual circle chord:</p>
 *
 * <ul>
 *   <li>3% top gap, 16% title/status, 50% instruction/progress, 2% gap, 14% bottom action,
 *       15% bottom safety area.</li>
 *   <li>Title and buttons are placed where the circle is still wide enough; the footer sits at
 *       71-85% of the height instead of the last row of pixels.</li>
 *   <li>Content width is 68% of the panel (the old 16% side inset) and is additionally clamped
 *       by the chord at the top and bottom of the content band.</li>
 *   <li>Scrollable pages reserve a 16% top/bottom safe inset so their first and last rows are
 *       never drawn under the round bezel.</li>
 * </ul>
 *
 * <p>Font sizes are derived from the actual box height and the system font scale, then the
 * Android views use auto-size inside those boxes as a second safety net.</p>
 *
 * <p>Pure policy only; the host owns pixels, insets and view measurement.</p>
 */
public final class WatchLayoutPolicy {
    /** Space above the title. */
    public static final double TOP_GAP_FRACTION = 0.03;
    /** Title/status zone. */
    public static final double HEADER_FRACTION = 0.16;
    /** Instruction or progress zone. */
    public static final double CONTENT_FRACTION = 0.50;
    /** Gap between content and the bottom action. */
    public static final double BETWEEN_GAP_FRACTION = 0.02;
    /** Bottom action zone. Kept above the narrow bottom of the circle. */
    public static final double FOOTER_FRACTION = 0.14;
    /** Empty bottom safety area; on a circle this is where the bezel closes in. */
    public static final double BOTTOM_GAP_FRACTION = 0.15;

    /** Normal content width, matching the historical 16% side inset. */
    public static final double CONTENT_WIDTH_FRACTION = 0.68;
    /** One bottom action button is 32% of the width (two fit inside the footer chord). */
    public static final double BUTTON_WIDTH_FRACTION = 0.32;
    /** Gap between the two bottom buttons. */
    public static final double BUTTON_GAP_FRACTION = 0.0125;
    /** Top/bottom inset used by scrollable pages so text never enters the bezel. */
    public static final double ROUND_SAFE_FRACTION = 0.16;

    /** A button below this is too hard to hit even on a watch. */
    public static final int MIN_BUTTON_HEIGHT_DP = 30;
    /** Horizontal breathing room between a band and the circle edge. */
    public static final int SIDE_SAFETY_DP = 4;

    public static final float MIN_TITLE_SP = 12f;
    public static final float MAX_TITLE_SP = 20f;
    public static final float MIN_INSTRUCTION_SP = 15f;
    public static final float MAX_INSTRUCTION_SP = 28f;
    public static final float MIN_PERCENT_SP = 24f;
    public static final float MAX_PERCENT_SP = 46f;
    public static final float MIN_DETAIL_SP = 12f;
    public static final float MAX_DETAIL_SP = 16f;
    public static final float MIN_BUTTON_SP = 11f;
    public static final float MAX_BUTTON_SP = 15f;

    private WatchLayoutPolicy() {}

    public static final class Metrics {
        public final int widthPx;
        public final int heightPx;
        public final int headerPx;
        public final int headerWidthPx;
        public final int contentPx;
        public final int contentWidthPx;
        public final int footerPx;
        public final int footerRowWidthPx;
        public final int topGapPx;
        public final int betweenGapPx;
        public final int bottomGapPx;
        public final int roundSafeInsetPx;
        public final int buttonWidthPx;
        public final int buttonHeightPx;
        public final int buttonGapPx;
        public final float titleSp;
        public final float instructionMinSp;
        public final float instructionMaxSp;
        public final float percentSp;
        public final float detailSp;
        public final float buttonSp;

        private Metrics(int widthPx, int heightPx, int headerPx, int headerWidthPx,
                        int contentPx, int contentWidthPx, int footerPx, int footerRowWidthPx,
                        int topGapPx, int betweenGapPx, int bottomGapPx, int roundSafeInsetPx,
                        int buttonWidthPx, int buttonHeightPx, int buttonGapPx,
                        float titleSp, float instructionMinSp, float instructionMaxSp,
                        float percentSp, float detailSp, float buttonSp) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.headerPx = headerPx;
            this.headerWidthPx = headerWidthPx;
            this.contentPx = contentPx;
            this.contentWidthPx = contentWidthPx;
            this.footerPx = footerPx;
            this.footerRowWidthPx = footerRowWidthPx;
            this.topGapPx = topGapPx;
            this.betweenGapPx = betweenGapPx;
            this.bottomGapPx = bottomGapPx;
            this.roundSafeInsetPx = roundSafeInsetPx;
            this.buttonWidthPx = buttonWidthPx;
            this.buttonHeightPx = buttonHeightPx;
            this.buttonGapPx = buttonGapPx;
            this.titleSp = titleSp;
            this.instructionMinSp = instructionMinSp;
            this.instructionMaxSp = instructionMaxSp;
            this.percentSp = percentSp;
            this.detailSp = detailSp;
            this.buttonSp = buttonSp;
        }

        /** The four zones plus the three gaps; never greater than the available height. */
        public int usedHeightPx() {
            return headerPx + contentPx + footerPx + topGapPx + betweenGapPx + bottomGapPx;
        }

        /** Width of the circle chord at a vertical fraction (0 = top, 1 = bottom). */
        public int chordWidthPx(double fraction) {
            return chordAt(widthPx, heightPx, fraction);
        }

        /** Both bottom buttons plus their gap. */
        public int footerButtonsWidthPx() {
            return footerRowWidthPx;
        }

        /** A four-line instruction page at the maximum font must fit the content box. */
        public float fourLineHeightPx(float density, float fontScale) {
            return instructionMaxSp * 1.2f * 4f * density * fontScale;
        }
    }

    public static Metrics measure(int widthPx, int heightPx, float density, float fontScale) {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f || fontScale <= 0f)
            throw new IllegalArgumentException("INVALID_WATCH_METRICS");

        int header = (int) Math.round(heightPx * HEADER_FRACTION);
        int content = (int) Math.round(heightPx * CONTENT_FRACTION);
        int footer = Math.max((int) Math.round(heightPx * FOOTER_FRACTION),
            Math.round(MIN_BUTTON_HEIGHT_DP * density));
        int topGap = (int) Math.round(heightPx * TOP_GAP_FRACTION);
        int betweenGap = (int) Math.round(heightPx * BETWEEN_GAP_FRACTION);
        int bottomGap = (int) Math.round(heightPx * BOTTOM_GAP_FRACTION);
        int overflow = header + content + footer + topGap + betweenGap + bottomGap - heightPx;
        if (overflow > 0) content = Math.max(0, content - overflow);

        int sideSafety = Math.max(1, Math.round(SIDE_SAFETY_DP * density));
        int contentWidth = Math.max(1, (int) Math.round(widthPx * CONTENT_WIDTH_FRACTION));

        // The title is vertically centred in its band; use the chord at that centre, not at the
        // very top of the panel where the circle is only a few pixels wide.
        int headerCentreY = topGap + header / 2;
        int headerWidth = Math.min(contentWidth,
            chordAt(widthPx, heightPx, headerCentreY) - sideSafety * 2);
        headerWidth = Math.max(1, headerWidth);

        // Content can start and end at different heights; the narrower chord wins.
        int contentTopY = topGap + header;
        int contentBottomY = contentTopY + content;
        int contentChord = Math.min(
            chordAt(widthPx, heightPx, contentTopY),
            chordAt(widthPx, heightPx, contentBottomY));
        contentWidth = Math.min(contentWidth,
            Math.max(1, contentChord - sideSafety * 2));

        int buttonWidth = Math.max(1, (int) Math.round(widthPx * BUTTON_WIDTH_FRACTION));
        int buttonGap = Math.max(1, (int) Math.round(widthPx * BUTTON_GAP_FRACTION));
        int footerTopY = contentBottomY + betweenGap;
        int footerBottomY = footerTopY + footer;
        int footerChord = chordAt(widthPx, heightPx, footerBottomY);
        int maxFooterRow = Math.max(1, footerChord - sideSafety * 2);
        int footerRow = buttonWidth * 2 + buttonGap;
        if (footerRow > maxFooterRow) {
            buttonWidth = Math.max(1, (maxFooterRow - buttonGap) / 2);
            footerRow = buttonWidth * 2 + buttonGap;
        }

        float contentDp = content / density;
        float headerDp = header / density;
        float footerDp = footer / density;
        float title = clamp(headerDp / fontScale * 0.38f, MIN_TITLE_SP, MAX_TITLE_SP);
        float idealInstruction = clamp(contentDp / fontScale * 0.16f,
            MIN_INSTRUCTION_SP, MAX_INSTRUCTION_SP);
        float instructionMin = clamp(idealInstruction * 0.82f, MIN_INSTRUCTION_SP, idealInstruction);
        float instructionMax = clamp(idealInstruction * 1.12f, idealInstruction, MAX_INSTRUCTION_SP);
        float percent = clamp(contentDp / fontScale * 0.26f, MIN_PERCENT_SP, MAX_PERCENT_SP);
        float detail = clamp(contentDp / fontScale * 0.11f, MIN_DETAIL_SP, MAX_DETAIL_SP);
        float button = clamp(footerDp / fontScale * 0.38f, MIN_BUTTON_SP, MAX_BUTTON_SP);

        return new Metrics(widthPx, heightPx, header, headerWidth,
            content, contentWidth, footer, footerRow,
            topGap, betweenGap, bottomGap,
            (int) Math.round(heightPx * ROUND_SAFE_FRACTION),
            buttonWidth, footer, buttonGap,
            title, instructionMin, instructionMax, percent, detail, button);
    }

    /** Width of the circle chord at a fraction of the panel height. */
    public static int chordAt(int widthPx, int heightPx, double fraction) {
        if (widthPx <= 0 || heightPx <= 0 || fraction < 0d || fraction > 1d)
            throw new IllegalArgumentException("INVALID_CHORD_METRICS");
        double radius = Math.min(widthPx, heightPx) / 2d;
        double dy = Math.abs(fraction * heightPx - heightPx / 2d);
        if (dy >= radius) return 0;
        return (int) Math.round(2d * Math.sqrt(radius * radius - dy * dy));
    }

    private static int chordAt(int widthPx, int heightPx, int yPx) {
        return chordAt(widthPx, heightPx, yPx / (double) heightPx);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
