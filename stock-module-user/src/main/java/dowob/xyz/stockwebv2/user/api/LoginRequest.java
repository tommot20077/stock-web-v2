package dowob.xyz.stockwebv2.user.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@Email @NotBlank String email, @NotBlank @Size(max = 128) String password) {
}
