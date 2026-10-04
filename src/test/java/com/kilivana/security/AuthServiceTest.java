package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.auth.service.AuthService;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.users.domain.RefreshToken;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.RefreshTokenRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private EntityManager entityManager;

    private AuthService authService;
    private TokenService tokenService;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        jwtProperties = new JwtProperties("issuer", "audience", 15, 14, "a-very-long-high-entropy-secret-value-123456");
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                authenticationManager,
                jwtService,
                jwtProperties,
                tokenService,
                entityManager);
    }

    @Test
    void publicSignupRejectsPrivilegedRolesBeforeCreatingAnAccount() {
        for (UserRole role : new UserRole[]{UserRole.DRIVER, UserRole.ADMIN}) {
            SignupRequest request = request(role);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(BusinessConflictException.class)
                    .hasMessageContaining("limited");
        }

        verifyNoInteractions(userRepository, passwordEncoder, entityManager);
    }

    @Test
    void publicSignupRequiresEmployeeCodeForInspector() {
        SignupRequest request = request(UserRole.INSPECTOR);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("employeeCode");

        verifyNoInteractions(userRepository, passwordEncoder, entityManager);
    }

    @Test
    void publicSignupStoresOnlyAHashedRefreshToken() {
        UUID userId = UUID.randomUUID();
        User user = new User("buyer@example.com", "encoded", "Buyer", "+254700000000", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");

        AuthResponse response = authService.signup(request(UserRole.BUYER));

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshToken stored = tokenCaptor.getValue();
        assertThat(stored.getTokenValue()).isNotEqualTo(response.refreshToken());
        assertThat(stored.getTokenValue()).hasSize(64);
        assertThat(stored.getFamilyId()).isNotNull();
    }

    @Test
    void adminProvisioningIsRefusedAfterAnAdminExists() {
        when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(true);

        assertThatThrownBy(() -> authService.registerAndAuthenticate(
                "admin@example.com",
                "secretPass1",
                "Admin",
                "+254700000001",
                UserRole.ADMIN))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("already");

        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void inspectorSignupCreatesPendingProfileAndTokens() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("access-token");
        AuthResponse response = authService.signup(new SignupRequest(
                "inspector@example.com",
                "secretPass1",
                "Inspector",
                "+254700000001",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.INSPECTOR,
                null,
                null,
                null,
                "  any code  ",
                null,
                null,
                null,
                null));
        ArgumentCaptor<com.kilivana.users.domain.InspectorProfile> profile =
                ArgumentCaptor.forClass(com.kilivana.users.domain.InspectorProfile.class);
        verify(entityManager).persist(profile.capture());
        assertThat(profile.getValue().getEmployeeCode()).isEqualTo("any code");
        assertThat(profile.getValue().getUser().getVerificationStatus())
                .isEqualTo(com.kilivana.users.domain.VerificationStatus.PENDING);
        assertThat(profile.getValue().getUser().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.verificationStatus()).isEqualTo(com.kilivana.users.domain.VerificationStatus.PENDING);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void invalidInspectorCodesAreRejectedBeforePersistence() {
        for (String code : new String[]{null, "", "   ", "x".repeat(101)}) {
            assertThatThrownBy(() -> authService.signup(new SignupRequest(
                "inspector@example.com",
                "secretPass1",
                "Inspector",
                "+254700000001",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.INSPECTOR,
                null,
                null,
                null,
                code,
                null,
                null,
                null,
                null)))
                    .isInstanceOf(BusinessConflictException.class);
        }
        verifyNoInteractions(userRepository, passwordEncoder, entityManager, refreshTokenRepository);
    }

    private SignupRequest request(UserRole role) {
        return new SignupRequest(
                "buyer@example.com",
                "secretPass1",
                "Buyer",
                "+254700000000",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                role,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
