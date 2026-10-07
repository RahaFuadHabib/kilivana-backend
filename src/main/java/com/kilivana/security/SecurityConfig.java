package com.kilivana.security;

import com.kilivana.common.config.RequestContextFilter;
import com.kilivana.security.config.ProxyProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, ProxyProperties.class})
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApplicationUserDetailsService userDetailsService;
    private final RequestContextFilter requestContextFilter;
    private final RateLimitFilter rateLimitFilter;
    private final AdminBootstrapKeyFilter adminBootstrapKeyFilter;
    private final ApiSecurityErrorHandler securityErrorHandler;
    private final boolean openApiEnabled;
    private final boolean openApiPublic;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApplicationUserDetailsService userDetailsService,
            RequestContextFilter requestContextFilter,
            RateLimitFilter rateLimitFilter,
            AdminBootstrapKeyFilter adminBootstrapKeyFilter,
            ApiSecurityErrorHandler securityErrorHandler,
            @Value("${springdoc.api-docs.enabled:false}") boolean openApiEnabled,
            @Value("${kilivana.security.docs.public:false}") boolean openApiPublic) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
        this.requestContextFilter = requestContextFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.adminBootstrapKeyFilter = adminBootstrapKeyFilter;
        this.securityErrorHandler = securityErrorHandler;
        this.openApiEnabled = openApiEnabled;
        this.openApiPublic = openApiPublic;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                    HttpMethod.POST,
                                    "/api/v1/auth/signup",
                                    "/api/v1/auth/register",
                                    "/api/v1/auth/login",
                                    "/api/v1/auth/refresh",
                                    "/api/v1/auth/logout",
                                    "/api/v1/auth/forgot-password",
                                    "/api/v1/auth/reset-password")
                            .permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/admins/signup").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/contact-messages", "/api/v1/webhooks/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();

                    auth.requestMatchers(HttpMethod.GET, "/api/v1/products/mine", "/api/v1/products/saved").authenticated();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/badges/mine").authenticated();
                    auth.requestMatchers(
                                    HttpMethod.GET,
                                    "/api/v1/products",
                                    "/api/v1/products/*",
                                    "/api/v1/products/*/images",
                                    "/api/v1/products/*/images/*/content",
                                    "/api/v1/sellers/*/products",
                                    "/api/v1/sellers/*/profile",
                                    "/api/v1/categories",
                                    "/api/v1/badges",
                                    "/api/v1/badges/user/*")
                            .permitAll();

                    if (openApiEnabled) {
                        if (openApiPublic) {
                            auth.requestMatchers("/", "/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                        } else {
                            auth.requestMatchers("/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").hasRole("ADMIN");
                        }
                    }

                    auth.requestMatchers("/actuator/info", "/actuator/metrics/**", "/actuator/prometheus").hasRole("ADMIN");
                    auth.requestMatchers("/actuator/**").hasRole("ADMIN");
                    auth.anyRequest().authenticated();
                })
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(requestContextFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, RequestContextFilter.class)
                .addFilterAfter(adminBootstrapKeyFilter, RateLimitFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, AdminBootstrapKeyFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
