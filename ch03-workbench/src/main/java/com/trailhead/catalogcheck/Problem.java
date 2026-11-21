package com.trailhead.catalogcheck;

/** One thing the catalog says is wrong with a route file. */
public record Problem(String file, String kind, String where, String detail) {

    @Override
    public String toString() {
        return "%s: %s %s: %s".formatted(file, kind, where, detail);
    }
}
