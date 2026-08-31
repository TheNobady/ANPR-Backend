package com.anpr.backend.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlateSkeletonTest {

    @Test
    void foldsEveryConfusableClassToItsRepresentative() {
        assertEquals("018526", PlateSkeleton.of("OIBSZG"));
        assertEquals("018526", PlateSkeleton.of("018526"));
    }

    @Test
    void leavesUnconfusableCharactersAlone() {
        // No O I B S Z G here, so the plate is its own skeleton.
        assertEquals("CH01AD1234", PlateSkeleton.of("CH01AD1234"));
        assertEquals("PH41AD7749", PlateSkeleton.of("PH41AD7749"));
    }

    @Test
    void foldsConfusableLettersEvenInsideAnOrdinaryPlate() {
        // B is confusable with 8, so a plate containing B is not its own skeleton.
        assertEquals("CH01A81234", PlateSkeleton.of("CH01AB1234"));
    }

    @Test
    void twoReadsOfOnePlateThatDifferOnlyByAConfusionShareASkeleton() {
        // The exact case the simulator generates: 0 read as O, 5 read as S.
        assertEquals(PlateSkeleton.of("CH01AB1234"), PlateSkeleton.of("CHO1AB1234"));
        assertEquals(PlateSkeleton.of("PB65AB5678"), PlateSkeleton.of("PB6SAB5678"));
        assertEquals(PlateSkeleton.of("HR26DK8337"), PlateSkeleton.of("HRZ6DKB337"));
    }

    @Test
    void plateoDifferingOutsideAConfusableClassKeepDistinctSkeletons() {
        assertFalse(PlateSkeleton.of("CH01AB1234").equals(PlateSkeleton.of("CH01AB1235")));
        assertFalse(PlateSkeleton.of("CH01AB1234").equals(PlateSkeleton.of("CH01AC1234")));
    }

    @Test
    void missingPlateStaysMissing() {
        assertNull(PlateSkeleton.of(null));
        assertNull(PlateSkeleton.of(""));
    }

    @Test
    void skeletonIsIdempotent() {
        String once = PlateSkeleton.of("CHO1ABI234");
        assertEquals(once, PlateSkeleton.of(once));
    }

    @Test
    void confusableRecognisesPairsInBothDirectionsButNotIdentity() {
        assertTrue(PlateSkeleton.confusable('0', 'O'));
        assertTrue(PlateSkeleton.confusable('O', '0'));
        assertTrue(PlateSkeleton.confusable('B', '8'));
        assertFalse(PlateSkeleton.confusable('0', '0'));
        assertFalse(PlateSkeleton.confusable('0', '8'));
        assertFalse(PlateSkeleton.confusable('A', 'B'));
    }

    @Test
    void matchesThePostgresTranslateUsedToBackfillTheColumn() {
        // V7 backfills with translate(plate_canonical, 'OIBSZG', '018526');
        // if these two ever disagree, existing rows stop matching new ones.
        String from = "OIBSZG";
        String to = "018526";
        for (int i = 0; i < from.length(); i++) {
            assertEquals(to.charAt(i), PlateSkeleton.fold(from.charAt(i)));
        }
    }
}
