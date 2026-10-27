package dev.rilvune.git.domain;

import java.time.Instant;
import java.time.ZoneOffset;

/** Zero-based original and final line positions at an immutable committed revision. */
public record GitBlameLine(int line, String commitId, String author, Instant time, ZoneOffset zone,
                           String subject, String originalPath, int originalLine) { }
