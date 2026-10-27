package dev.rilvune.platform.layout.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LayoutNodeTest {
    @Test
    void rejectsFractionsThatCannotProduceTwoVisibleRegions() {
        Panel first = new Panel("project");
        Panel second = new Panel("editor");
        double[] invalidFractions = {
                Double.NEGATIVE_INFINITY, -0.1, 0.0, 1.0, 1.1, Double.POSITIVE_INFINITY, Double.NaN
        };

        for (double fraction : invalidFractions) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Split(Axis.HORIZONTAL, fraction, first, second),
                    "Fraction: " + fraction);
        }
    }

    @Test
    void rejectsAnIncompleteLayoutBeforeItReachesTheRenderer() {
        assertThrows(IllegalArgumentException.class, () -> new Panel("  "));
        assertThrows(NullPointerException.class,
                () -> new Split(Axis.VERTICAL, 0.5, new Panel("editor"), null));
        assertThrows(NullPointerException.class,
                () -> new Split(null, 0.5, new Panel("project"), new Panel("editor")));
    }
}
