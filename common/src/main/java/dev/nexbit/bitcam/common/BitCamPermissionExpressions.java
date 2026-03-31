package dev.nexbit.bitcam.common;

import java.util.Locale;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

public final class BitCamPermissionExpressions {
    private BitCamPermissionExpressions() {
    }

    public static boolean allows(String expression, IntPredicate levelChecker, Predicate<String> permissionChecker) {
        if (expression == null || expression.isBlank()) {
            return true;
        }

        String normalized = expression.trim();
        Integer requiredLevel = parseOperatorLevel(normalized);
        if (requiredLevel != null) {
            return levelChecker.test(requiredLevel);
        }

        return permissionChecker.test(normalized);
    }

    private static Integer parseOperatorLevel(String expression) {
        String normalized = expression.toLowerCase(Locale.ROOT);
        if ("op".equals(normalized)) {
            return 2;
        }

        String prefix;
        if (normalized.startsWith("op:")) {
            prefix = "op:";
        } else if (normalized.startsWith("level:")) {
            prefix = "level:";
        } else if (normalized.startsWith("op_level:")) {
            prefix = "op_level:";
        } else {
            return null;
        }

        String value = expression.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            return 2;
        }

        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
