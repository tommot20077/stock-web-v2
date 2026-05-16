package dowob.xyz.stockwebv2.start.support;

import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/test-only/error")
@Profile("test")
class TestOnlyController {

    @GetMapping("/business")
    void business() {
        throw new DuplicateResourceException("test-resource");
    }

    @GetMapping("/status")
    void status() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "test status not found");
    }
}
