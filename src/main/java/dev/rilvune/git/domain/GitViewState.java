package dev.rilvune.git.domain;
/** Presentation only, stored outside repositories. Fractions survive HiDPI and monitor changes. */
public record GitViewState(int x, int y, int width, int height, boolean maximized, boolean docked,
        double vertical, double references, double details, double files, boolean showReferences, boolean showDetails,
        boolean unified, boolean ignoreWhitespace, boolean history, boolean currentBranch) {
    public static GitViewState defaults() { return new GitViewState(-1, -1, 1440, 900, false, false, .51, .18, .68, .42, true, true, false, false, false, false); }
}
