package com.anpr.backend.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfusionEditDistanceTest {

    private static final double EPS = 1e-9;

    @Test
    void identicalPlatesCostNothing() {
        assertEquals(0.0, ConfusionEditDistance.between("CH01AB1234", "CH01AB1234"), EPS);
    }

    @Test
    void oneConfusableSubstitutionCostsHalf() {
        assertEquals(0.5, ConfusionEditDistance.between("CH01AB1234", "CHO1AB1234"), EPS);
        assertEquals(0.5, ConfusionEditDistance.between("PB65AB1234", "PB6SAB1234"), EPS);
    }

    @Test
    void oneOrdinarySubstitutionCostsOne() {
        assertEquals(1.0, ConfusionEditDistance.between("CH01AB1234", "CH01AB1235"), EPS);
        assertEquals(1.0, ConfusionEditDistance.between("CH01AB1234", "CH01AC1234"), EPS);
    }

    @Test
    void twoConfusableSubstitutionsCostOne_soTheyStillClearTheVetoThreshold() {
        // Two confusions is a plausible OCR failure and must not be vetoed at 2.0.
        double d = ConfusionEditDistance.between("HR26DK8337", "HRZ6DKB337");
        assertEquals(1.0, d, EPS);
        assertTrue(d <= 2.0);
    }

    @Test
    void insertionsAndDeletionsCostOneEach() {
        assertEquals(1.0, ConfusionEditDistance.between("CH01AB1234", "CH01AB123"), EPS);
        assertEquals(1.0, ConfusionEditDistance.between("CH01AB1234", "CH01AB12345"), EPS);
        assertEquals(2.0, ConfusionEditDistance.between("CH01AB1234", "CH01AB12"), EPS);
    }

    @Test
    void differentPlatesLandWellBeyondTheVetoThreshold() {
        assertTrue(ConfusionEditDistance.between("CH01AB1234", "PB99XY7777") > 2.0);
    }

    @Test
    void emptyStringCostsOnePerCharacter() {
        assertEquals(10.0, ConfusionEditDistance.between("CH01AB1234", ""), EPS);
        assertEquals(10.0, ConfusionEditDistance.between("", "CH01AB1234"), EPS);
    }

    @Test
    void nullIsInfinitelyFar_neverASilentZero() {
        assertEquals(Double.POSITIVE_INFINITY, ConfusionEditDistance.between(null, "CH01AB1234"));
        assertEquals(Double.POSITIVE_INFINITY, ConfusionEditDistance.between("CH01AB1234", null));
    }

    @Test
    void aMismatchOnALowConfidenceCharacterCostsLessThanOnAConfidentOne() {
        // Disagreeing with a character the reader was unsure of is weak evidence of a
        // different vehicle; disagreeing with one it was certain of is strong evidence.
        float[] unsure = {0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.5f, 0.99f, 0.99f, 0.99f};
        float[] certain = {0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.99f};

        double lenient = ConfusionEditDistance.between("CH01AB1234", "CH01AB9234", unsure);
        double strict = ConfusionEditDistance.between("CH01AB1234", "CH01AB9234", certain);
        assertTrue(lenient < strict,
                "a mismatch the reader was unsure about should cost less: " + lenient + " vs " + strict);
    }

    @Test
    void confidenceWeightIsFloored_soAZeroConfidenceCharacterIsNotFreeToDiffer() {
        float[] hopeless = new float[10];   // all 0.0
        double d = ConfusionEditDistance.between("CH01AB1234", "CH01AB9234", hopeless);
        assertTrue(d > 0.0, "a mismatch must always cost something, got " + d);
    }

    @Test
    void weightingNeverAppliesToMatchingCharacters() {
        float[] low = new float[10];
        assertEquals(0.0, ConfusionEditDistance.between("CH01AB1234", "CH01AB1234", low), EPS);
    }

    @Test
    void isSymmetricWhenUnweighted() {
        assertEquals(ConfusionEditDistance.between("CH01AB1234", "CHO1AB1235"),
                ConfusionEditDistance.between("CHO1AB1235", "CH01AB1234"), EPS);
    }
}
