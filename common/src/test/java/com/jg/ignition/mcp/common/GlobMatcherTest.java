package com.jg.ignition.mcp.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

    @Test
    void matchesWildcardSegments() {
        assertTrue(GlobMatcher.matches("[default]MCP/*", "[default]MCP/Line1/Speed"));
        assertTrue(GlobMatcher.matches("foo?.bar", "foo1.bar"));
        assertFalse(GlobMatcher.matches("foo?.bar", "foo12.bar"));
    }

    @Test
    void treatsRegexCharactersAsLiterals() {
        assertTrue(GlobMatcher.matches("a.b(c)", "a.b(c)"));
        assertFalse(GlobMatcher.matches("a.b(c)", "axb(c)"));
    }
}
