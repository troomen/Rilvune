package dev.editor.platform.text.ui;

import java.awt.Color;

/** A presentation-only marker. It never changes the text model or its offsets. */
public record TextLineMarker(int startOffset, int endOffset, Color color) { }
