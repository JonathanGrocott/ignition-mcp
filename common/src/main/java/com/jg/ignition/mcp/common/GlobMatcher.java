package com.jg.ignition.mcp.common;

import java.util.regex.Pattern;

public final class GlobMatcher {

    private GlobMatcher() {
    }

    public static boolean matches(String glob, String value) {
        if (glob == null || value == null) {
            return false;
        }
        Pattern pattern = Pattern.compile(toRegex(glob));
        return pattern.matcher(value).matches();
    }

    public static String toRegex(String glob) {
        StringBuilder out = new StringBuilder();
        out.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            switch (ch) {
                case '*':
                    out.append(".*");
                    break;
                case '?':
                    out.append('.');
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '^':
                case '$':
                case '+':
                case '|':
                case '\\':
                    out.append('\\').append(ch);
                    break;
                default:
                    out.append(ch);
                    break;
            }
        }
        out.append('$');
        return out.toString();
    }
}
