package dowob.xyz.stockwebv2.start.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.security.JwtService;
import dowob.xyz.stockwebv2.infrastructure.security.JwtService.JwtClaims;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtAuthenticationFilter,
        ApiSecurityErrorWriter apiSecurityErrorWriter
    ) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/logout",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(apiSecurityErrorWriter.authenticationEntryPoint())
                .accessDeniedHandler(apiSecurityErrorWriter.accessDeniedHandler())
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(
        JwtService jwtService,
        StringRedisTemplate redisTemplate,
        ApiSecurityErrorWriter apiSecurityErrorWriter
    ) {
        return new JwtAuthenticationFilter(jwtService, redisTemplate, apiSecurityErrorWriter);
    }

    @Bean
    ApiSecurityErrorWriter apiSecurityErrorWriter(ObjectMapper objectMapper) {
        return new ApiSecurityErrorWriter(objectMapper);
    }

    static class JwtAuthenticationFilter extends OncePerRequestFilter {
        private static final String BEARER_PREFIX = "Bearer ";
        private static final String ACTIVE_STATUS = "ACTIVE";

        private final JwtService jwtService;
        private final StringRedisTemplate redisTemplate;
        private final ApiSecurityErrorWriter errorWriter;

        JwtAuthenticationFilter(JwtService jwtService, StringRedisTemplate redisTemplate, ApiSecurityErrorWriter errorWriter) {
            this.jwtService = jwtService;
            this.redisTemplate = redisTemplate;
            this.errorWriter = errorWriter;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
                filterChain.doFilter(request, response);
                return;
            }

            try {
                JwtClaims claims = jwtService.parse(authorization.substring(BEARER_PREFIX.length()));
                AuthState authState = readAuthState(claims.userId());
                if (authState == null || !Objects.equals(authState.tokenVersion(), String.valueOf(claims.tokenVersion()))) {
                    SecurityContextHolder.clearContext();
                    errorWriter.write(response, ErrorCode.AUTH_INVALID_CREDENTIALS);
                    return;
                }
                if (!ACTIVE_STATUS.equals(authState.status())) {
                    SecurityContextHolder.clearContext();
                    errorWriter.write(response, ErrorCode.AUTH_FORBIDDEN);
                    return;
                }
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    String.valueOf(claims.userId()),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (DataAccessException exception) {
                SecurityContextHolder.clearContext();
                errorWriter.write(response, ErrorCode.AUTH_REDIS_UNAVAILABLE);
                return;
            } catch (JwtException exception) {
                SecurityContextHolder.clearContext();
                errorWriter.write(response, errorCodeFor(exception));
                return;
            } catch (RuntimeException exception) {
                SecurityContextHolder.clearContext();
                errorWriter.write(response, ErrorCode.AUTH_INVALID_CREDENTIALS);
                return;
            }

            filterChain.doFilter(request, response);
        }

        private AuthState readAuthState(Long userId) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries("user:auth:" + userId);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            return new AuthState((String) entries.get("tokenVersion"), (String) entries.get("status"));
        }

        private ErrorCode errorCodeFor(JwtException exception) {
            if (exception instanceof JwtValidationException validationException && validationException.getErrors().stream()
                .anyMatch(error -> containsExpired(error.getErrorCode()) || containsExpired(error.getDescription()))) {
                return ErrorCode.AUTH_TOKEN_EXPIRED;
            }
            return ErrorCode.AUTH_INVALID_CREDENTIALS;
        }

        private boolean containsExpired(String value) {
            return value != null && value.toLowerCase().contains("expired");
        }

        private record AuthState(String tokenVersion, String status) {
        }
    }

    static class ApiSecurityErrorWriter {
        private final ObjectMapper objectMapper;

        ApiSecurityErrorWriter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        AuthenticationEntryPoint authenticationEntryPoint() {
            return (request, response, exception) -> write(response, ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        AccessDeniedHandler accessDeniedHandler() {
            return (request, response, exception) -> write(response, ErrorCode.AUTH_FORBIDDEN);
        }

        void write(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                ApiError.of(code, code.defaultMessage()),
                meta()
            ));
        }

        private ApiMeta meta() {
            String traceId = MDC.get(TraceIdFilter.TRACE_ID);
            return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
        }
    }
}
