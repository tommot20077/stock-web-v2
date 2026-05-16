package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.api.StrategyValidationDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class StrategyValidator {
    private static final Pattern STRATEGY_FUNCTION = Pattern.compile("\\bfunction\\s+strategy\\s*\\(");

    public StrategyValidationDto validate(String code) {
        String source = code == null ? "" : code.trim();
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
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
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

        return parentheses == 0 && braces == 0;
    }
}
