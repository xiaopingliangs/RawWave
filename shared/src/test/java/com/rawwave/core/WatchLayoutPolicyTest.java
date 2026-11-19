package com.rawwave.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pure layout math for round watches. No device or emulator required: the same fractions,
 * circle-chord checks and font-size rules are checked for the 44mm 480x480 panel, the 40mm
 * 438x438 panel, a smaller legacy round panel and a large font scale.
 */
public class WatchLayoutPolicyTest {
    private static final float DENSITY = 2f;

    @Test public void watch9_44mmKeepsThePercentageZones() {
        WatchLayoutPolicy.Metrics m = WatchLayoutPolicy.measure(480, 480, DENSITY, 1f);
        assertEquals(77, m.headerPx, 2);
        assertEquals(240, m.contentPx, 2);
        assertEquals(67, m.footerPx, 1);
        assertTrue("zones must not exceed the panel", m.usedHeightPx() <= 480);
        assertEquals(480, m.usedHeightPx(), 1);
        assertTrue(m.titleSp >= 14f && m.titleSp <= 20f);
        assertTrue(m.instructionMaxSp >= 19f && m.instructionMaxSp <= 28f);
        assertTrue(m.percentSp >= 28f && m.percentSp <= 40f);
        assertTrue(m.detailSp >= 12f && m.detailSp <= 16f);
        assertTrue(m.buttonSp >= 11f && m.buttonSp <= 15f);
        assertTrue("a four-line page must fit", m.fourLineHeightPx(DENSITY, 1f) <= m.contentPx);
        assertEquals(77, m.roundSafeInsetPx, 1);
        assertTrue(m.buttonHeightPx >= Math.round(WatchLayoutPolicy.MIN_BUTTON_HEIGHT_DP * DENSITY));
        assertTrue(m.buttonWidthPx <= 160);
        assertTrue("five Chinese characters must fit one button",
            m.buttonSp * 5f <= m.buttonWidthPx / DENSITY + 1f);
    }

    @Test public void watch9_40mmShrinksTextAndKeepsTheSameHierarchy() {
        WatchLayoutPolicy.Metrics m = WatchLayoutPolicy.measure(438, 438, DENSITY, 1f);
        assertTrue(m.usedHeightPx() <= 438);
        assertEquals(438, m.usedHeightPx(), 1);
        assertTrue(m.titleSp < 16f);
        assertTrue(m.instructionMaxSp < 24f);
        assertTrue(m.percentSp < 32f);
        assertTrue(m.fourLineHeightPx(DENSITY, 1f) <= m.contentPx);
        assertTrue(m.buttonWidthPx <= 146);
        assertTrue("five Chinese characters must fit one button",
            m.buttonSp * 5f <= m.buttonWidthPx / DENSITY + 1f);
    }

    @Test public void everyBandFitsTheCircleChord() {
        WatchLayoutPolicy.Metrics[] panels = {
            WatchLayoutPolicy.measure(480, 480, DENSITY, 1f),
            WatchLayoutPolicy.measure(438, 438, DENSITY, 1f),
            WatchLayoutPolicy.measure(320, 320, DENSITY, 1f),
        };
        for (WatchLayoutPolicy.Metrics m : panels) {
            String label = m.widthPx + "x" + m.heightPx;
            assertTrue(label + ": title must fit its chord",
                m.headerWidthPx + 8 <= m.chordWidthPx(0.11));
            assertTrue(label + ": content must fit the top of its band",
                m.contentWidthPx + 8 <= m.chordWidthPx(0.19));
            assertTrue(label + ": content must fit the bottom of its band",
                m.contentWidthPx + 8 <= m.chordWidthPx(0.69));
            assertTrue(label + ": both buttons must fit the footer chord",
                m.footerButtonsWidthPx() + 8 <= m.chordWidthPx(0.85));
            assertTrue(label + ": scroll-page content must fit the top safe inset",
                m.contentWidthPx + 8 <= m.chordWidthPx(0.16));
            assertTrue(label + ": scroll-page content must fit the bottom safe inset",
                m.contentWidthPx + 8 <= m.chordWidthPx(0.84));
            assertTrue(label + ": footer must end above the narrow bottom",
                m.usedHeightPx() - m.bottomGapPx <= m.heightPx);
        }
    }

    @Test public void largeFontScaleKeepsPhysicalTextInsideTheSameBoxes() {
        WatchLayoutPolicy.Metrics normal = WatchLayoutPolicy.measure(480, 480, DENSITY, 1f);
        WatchLayoutPolicy.Metrics large = WatchLayoutPolicy.measure(480, 480, DENSITY, 1.3f);
        assertTrue(large.titleSp < normal.titleSp);
        assertTrue(large.instructionMaxSp < normal.instructionMaxSp);
        assertTrue(large.percentSp < normal.percentSp);
        assertTrue(large.detailSp < normal.detailSp);
        assertTrue(large.fourLineHeightPx(DENSITY, 1.3f) <= large.contentPx);
        assertTrue(large.usedHeightPx() <= 480);
    }

    @Test public void smallRoundPanelStillHasAUsableButtonAndContent() {
        WatchLayoutPolicy.Metrics m = WatchLayoutPolicy.measure(320, 320, DENSITY, 1f);
        assertTrue(m.contentPx > 0);
        assertTrue(m.usedHeightPx() <= 320);
        assertEquals(320, m.usedHeightPx(), 1);
        assertTrue(m.buttonHeightPx >= Math.round(WatchLayoutPolicy.MIN_BUTTON_HEIGHT_DP * DENSITY));
        assertTrue(m.instructionMinSp >= WatchLayoutPolicy.MIN_INSTRUCTION_SP);
        assertTrue(m.instructionMaxSp >= m.instructionMinSp);
        assertTrue(m.footerButtonsWidthPx() + 8 <= m.chordWidthPx(0.85));
    }

    @Test public void invalidMetricsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> WatchLayoutPolicy.measure(0, 480, DENSITY, 1f));
        assertThrows(IllegalArgumentException.class,
            () -> WatchLayoutPolicy.measure(480, 0, DENSITY, 1f));
        assertThrows(IllegalArgumentException.class,
            () -> WatchLayoutPolicy.measure(480, 480, 0f, 1f));
        assertThrows(IllegalArgumentException.class,
            () -> WatchLayoutPolicy.measure(480, 480, DENSITY, 0f));
    }
}
