package dowob.xyz.stockwebv2.start.config;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.Permission;
import dowob.xyz.stockwebv2.infrastructure.security.JwtService;
import dowob.xyz.stockwebv2.infrastructure.security.JwtService.JwtClaims;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.user.service.BrowserAuthCookieService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtAuthenticationFilter,
        BrowserCsrfFilter browserCsrfFilter,
        ApiSecurityErrorWriter apiSecurityErrorWriter,
        CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/assets").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/csrf").permitAll()
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/token",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                // WebSocket handshake 路徑：HTTP Filter 層放行，由 MarketHandshakeInterceptor 以 ticket 驗證
                .requestMatchers("/ws/v1/market/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(apiSecurityErrorWriter.authenticationEntryPoint())
                .accessDeniedHandler(apiSecurityErrorWriter.accessDeniedHandler())
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(browserCsrfFilter, JwtAuthenticationFilter.class)
            .build();
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(
        JwtService jwtService,
        StringRedisTemplate redisTemplate,
        ApiSecurityErrorWriter apiSecurityErrorWriter,
        BrowserAuthCookieService cookieService
    ) {
        return new JwtAuthenticationFilter(jwtService, redisTemplate, apiSecurityErrorWriter, cookieService);
    }

    @Bean
    BrowserCsrfFilter browserCsrfFilter(BrowserAuthCookieService cookieService, ApiSecurityErrorWriter apiSecurityErrorWriter) {
        return new BrowserCsrfFilter(cookieService, apiSecurityErrorWriter);
    }

    @Bean
    ApiSecurityErrorWriter apiSecurityErrorWriter(ObjectMapper objectMapper) {
        return new ApiSecurityErrorWriter(objectMapper);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
        @Value("${stock.cors.allowed-origins:}") String allowedOrigins
    ) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of(TraceIdFilter.TRACE_HEADER));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    static class JwtAuthenticationFilter extends OncePerRequestFilter {
        static final String AUTH_TRANSPORT_ATTRIBUTE = "stock.auth.transport";
        private static final String AUTH_TRANSPORT_BEARER = "bearer";
        private static final String AUTH_TRANSPORT_COOKIE = "cookie";
        private static final String BEARER_PREFIX = "Bearer ";
        private static final String ACTIVE_STATUS = "ACTIVE";

        private final JwtService jwtService;
        private final StringRedisTemplate redisTemplate;
        private final ApiSecurityErrorWriter errorWriter;
        private final BrowserAuthCookieService cookieService;

        JwtAuthenticationFilter(
            JwtService jwtService,
            StringRedisTemplate redisTemplate,
            ApiSecurityErrorWriter errorWriter,
            BrowserAuthCookieService cookieService
        ) {
            this.jwtService = jwtService;
            this.redisTemplate = redisTemplate;
            this.errorWriter = errorWriter;
            this.cookieService = cookieService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
            String authorization = request.getHeader("Authorization");
            String token;
            String transport;
            if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                token = authorization.substring(BEARER_PREFIX.length());
                transport = AUTH_TRANSPORT_BEARER;
            } else if (!isRefreshEndpoint(request)) {
                token = cookieService.readAccessCookie(request);
                transport = AUTH_TRANSPORT_COOKIE;
            } else {
                token = null;
                transport = null;
            }

            if (token == null || token.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }

            try {
                JwtClaims claims = jwtService.parse(token);
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
                    authoritiesFor(claims)
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute(AUTH_TRANSPORT_ATTRIBUTE, transport);
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

        private List<SimpleGrantedAuthority> authoritiesFor(JwtClaims claims) {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + claims.role().name()));
            for (Permission permission : claims.role().permissions()) {
                authorities.add(new SimpleGrantedAuthority(permission.name()));
            }
            return authorities;
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

        private boolean isRefreshEndpoint(HttpServletRequest request) {
            return "/api/v1/auth/refresh".equals(request.getRequestURI());
        }

        private record AuthState(String tokenVersion, String status) {
        }
    }

    static class BrowserCsrfFilter extends OncePerRequestFilter {
        private final BrowserAuthCookieService cookieService;
        private final ApiSecurityErrorWriter errorWriter;

        BrowserCsrfFilter(BrowserAuthCookieService cookieService, ApiSecurityErrorWriter errorWriter) {
            this.cookieService = cookieService;
            this.errorWriter = errorWriter;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
            if (isSafeMethod(request)) {
                filterChain.doFilter(request, response);
                return;
            }

            boolean cookieAuthenticated = JwtAuthenticationFilter.AUTH_TRANSPORT_COOKIE.equals(
                request.getAttribute(JwtAuthenticationFilter.AUTH_TRANSPORT_ATTRIBUTE)
            );
            boolean browserSessionAction = isBrowserSessionAction(request) && cookieService.readRefreshCookie(request) != null;
            if (!cookieAuthenticated && !browserSessionAction) {
                filterChain.doFilter(request, response);
                return;
            }

            String cookieToken = cookieService.readCsrfCookie(request);
            String headerToken = request.getHeader(BrowserAuthCookieService.XSRF_HEADER);
            if (cookieToken == null || headerToken == null || !cookieToken.equals(headerToken)) {
                SecurityContextHolder.clearContext();
                errorWriter.write(response, ErrorCode.AUTH_CSRF_TOKEN_INVALID);
                return;
            }

            filterChain.doFilter(request, response);
        }

        private boolean isSafeMethod(HttpServletRequest request) {
            return HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || HttpMethod.TRACE.matches(request.getMethod());
        }

        private boolean isBrowserSessionAction(HttpServletRequest request) {
            String uri = request.getRequestURI();
            return "/api/v1/auth/refresh".equals(uri) || "/api/v1/auth/logout".equals(uri);
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
