package dowob.xyz.stockwebv2.infrastructure.search;

import java.util.List;

public interface SearchService<T> {
    List<T> search(String query, int limit);
}
