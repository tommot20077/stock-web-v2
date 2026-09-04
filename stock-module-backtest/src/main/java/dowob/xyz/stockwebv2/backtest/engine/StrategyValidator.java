package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.api.StrategyValidationDto;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class StrategyValidator {
    private static final Pattern STRATEGY_FUNCTION = Pattern.compile("\\bfunction\\s+strategy\\s*\\(");

    public StrategyValidationDto validate(String code) {
        String source = StringUtils.trimToEmpty(code);
        if (source.isBlank()) {
            throw new IllegalArgumentException("strategyCode is required");
        }
        if (!STRATEGY_FUNCTION.matcher(source).find()) {
            throw new IllegalArgumentException("strategy function is required");
        }
        if (!hasBalancedDelimiters(source)) {
            throw new IllegalArgumentException("strategyCode has unbalanced delimiters");
        }

        return new StrategyValidationDto(true, "strategy", List.of());
    }

    private boolean hasBalancedDelimiters(String source) {
        int parentheses = 0;
        int braces = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inTemplate = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }
                continue;
            }
            if (inSingleQuote || inDoubleQuote || inTemplate) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (inSingleQuote && current == '\'') {
                    inSingleQuote = false;
                } else if (inDoubleQuote && current == '"') {
                    inDoubleQuote = false;
                } else if (inTemplate && current == '`') {
                    inTemplate = false;
                }
                continue;
            }

            if (current == '/' && next == '/') {
                inLineComment = true;
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                inBlockComment = true;
                index++;
                continue;
            }
            if (current == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (current == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (current == '`') {
                inTemplate = true;
                continue;
            }

            if (current == '(') {
                parentheses++;
            } else if (current == ')') {
                parentheses--;
            } else if (current == '{') {
                braces++;
            } else if (current == '}') {
                braces--;
            }

            if (parentheses < 0 || braces < 0) {
                return false;
            }
        }

        return parentheses == 0
            && braces == 0
            && !inSingleQuote
            && !inDoubleQuote
            && !inTemplate
            && !inBlockComment;
    }
}
