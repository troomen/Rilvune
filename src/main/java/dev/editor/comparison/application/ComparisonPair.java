package dev.editor.comparison.application;

import dev.editor.document.application.DocumentHandle;

public record ComparisonPair(DocumentHandle left, DocumentHandle right) { }
