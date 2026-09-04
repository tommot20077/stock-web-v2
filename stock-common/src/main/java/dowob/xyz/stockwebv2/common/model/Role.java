package dowob.xyz.stockwebv2.common.model;

import java.util.EnumSet;
import java.util.Set;

public enum Role {
    USER,
    ADMIN;

    public Set<Permission> permissions() {
        return switch (this) {
            case USER -> EnumSet.of(
                Permission.WATCHLIST_MANAGE,
                Permission.TRADE_EXECUTE,
                Permission.PORTFOLIO_VIEW,
                Permission.PROFILE_EDIT
            );
            case ADMIN -> EnumSet.allOf(Permission.class);
        };
    }
}
