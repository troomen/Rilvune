package dev.rilvune.platform.text.ui.minimap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinimapGeometryTest {
    @Test void shortDocumentsKeepTheirNaturalMiniatureHeight() {
        var geometry = MinimapGeometry.of(64, 600, 180, .2);
        assertEquals(36, geometry.height()); assertEquals(180, geometry.documentY(36));
        assertEquals(geometry.height(), geometry.viewport(0, 600).height);
        assertEquals(0, geometry.viewY(36, 0, 600));
    }
    @Test void largeDocumentsUseBoundedGeometryAndNavigationClampsAtBothEnds() {
        var geometry = MinimapGeometry.of(500, 8000, 10_000_000, .2);
        assertEquals(64, geometry.width()); assertEquals(2048, geometry.height());
        assertEquals(0, geometry.viewY(-100, 300, 600));
        assertEquals(9_999_400, geometry.viewY(3000, 300, 600));
        var visible = geometry.viewport(9_999_400, 600);
        assertEquals(4, visible.height); assertEquals(2048, visible.y + visible.height);
    }
    @Test void draggingViewportPreservesTheGrabPosition() {
        var geometry = MinimapGeometry.of(64, 600, 60_000, .2);
        double grab = geometry.documentY(205) - 20_000;
        assertEquals(20_000, geometry.viewY(205, grab, 600));
        assertEquals(30_000, geometry.viewY(305, grab, 600));
    }
}
