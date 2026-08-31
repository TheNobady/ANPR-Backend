package com.anpr.backend.sim;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Turns a true plate into what an OCR engine actually reported.
 *
 * <p>The substitutions are the confusions a real reader makes on a real plate -- glyphs
 * that share a shape, not random characters. Identity resolution has to survive these,
 * so generating uniform noise instead would make Phase 3 look better than it is.
 */
@Component
public class PlateDegrader {

    private static final Map<Character, Character> CONFUSIONS = new LinkedHashMap<>();

    static {
        addPair('0', 'O');
        addPair('1', 'I');
        addPair('8', 'B');
        addPair('5', 'S');
        addPair('2', 'Z');
        addPair('6', 'G');
    }

    private static void addPair(char a, char b) {
        CONFUSIONS.put(a, b);
        CONFUSIONS.put(b, a);
    }

    /**
     * @param text             what the OCR reported
     * @param corruptedIndex   index of the swapped character, or -1 if the read is clean
     */
    public record Read(String text, int corruptedIndex) {
        public boolean corrupted() {
            return corruptedIndex >= 0;
        }
    }

    /** Swaps one confusable character, or returns the plate unchanged if it has none. */
    public Read corrupt(String plate, RandomGenerator random) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < plate.length(); i++) {
            if (CONFUSIONS.containsKey(plate.charAt(i))) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return new Read(plate, -1);
        }
        int index = candidates.get(random.nextInt(candidates.size()));
        char[] chars = plate.toCharArray();
        chars[index] = CONFUSIONS.get(chars[index]);
        return new Read(new String(chars), index);
    }

    /**
     * The two or three raw reads a multi-frame tracker accumulates. The reported text is
     * always among them, and at least one differs -- a tracklet whose every frame agreed
     * would not be worth storing raw reads for.
     */
    public List<String> rawReads(String reportedText, String truePlate, RandomGenerator random) {
        List<String> reads = new ArrayList<>(3);
        reads.add(reportedText);

        String alternative = corrupt(reportedText, random).text();
        if (alternative.equals(reportedText)) {
            alternative = truePlate.equals(reportedText) ? corrupt(truePlate, random).text() : truePlate;
        }
        reads.add(alternative);

        if (random.nextBoolean()) {
            String third = corrupt(alternative, random).text();
            reads.add(third.equals(alternative) ? reportedText : third);
        }
        return reads;
    }
}
