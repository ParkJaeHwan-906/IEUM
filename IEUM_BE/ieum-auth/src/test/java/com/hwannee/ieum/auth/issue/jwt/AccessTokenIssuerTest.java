package com.hwannee.ieum.auth.issue.jwt;

import com.hwannee.ieum.auth.issue.config.AuthProperties;
import com.hwannee.ieum.auth.issue.config.JwtKeyConfig;
import com.hwannee.ieum.users.domain.UserType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AccessTokenIssuerTest {

    private static final AuthProperties PROPS = new AuthProperties(
            "http://localhost:8081", "", List.of(), Duration.ofMinutes(15), Duration.ofDays(14));
    private final JwtKeyConfig keyConfig = new JwtKeyConfig();

    @Test
    void issuedTokenIsVerifiableWithPublicKeyAndCarriesClaims() throws Exception {
        RSAKey key = keyConfig.signingKey(PROPS);
        AccessTokenIssuer issuer = new AccessTokenIssuer(keyConfig.jwtEncoder(key), key, PROPS);

        String token = issuer.issue("uid-1", UserType.CONSUMER, "닉네임");
        Jwt jwt = decoderFor(key).decode(token);

        assertThat(jwt.getSubject()).isEqualTo("uid-1");
        assertThat(jwt.getIssuer().toString()).isEqualTo(PROPS.issuer());
        assertThat(jwt.getClaimAsString(AccessTokenIssuer.CLAIM_ROLE)).isEqualTo("CONSUMER");
        assertThat(jwt.getClaimAsString(AccessTokenIssuer.CLAIM_NICKNAME)).isEqualTo("닉네임");
        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID());
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(PROPS.accessTokenTtl());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        RSAKey key = keyConfig.signingKey(PROPS);
        RSAKey otherKey = keyConfig.signingKey(PROPS);
        AccessTokenIssuer issuer = new AccessTokenIssuer(keyConfig.jwtEncoder(key), key, PROPS);

        String token = issuer.issue("uid-1", UserType.CONSUMER, "닉네임");

        assertThatThrownBy(() -> decoderFor(otherKey).decode(token))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        AuthProperties otherIssuer = new AuthProperties(
                "http://other-issuer", "", List.of(), PROPS.accessTokenTtl(), PROPS.refreshTokenTtl());
        RSAKey key = keyConfig.signingKey(PROPS);
        AccessTokenIssuer issuer = new AccessTokenIssuer(keyConfig.jwtEncoder(key), key, otherIssuer);

        String token = issuer.issue("uid-1", UserType.CONSUMER, "닉네임");

        assertThatThrownBy(() -> decoderFor(key).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    private static NimbusJwtDecoder decoderFor(RSAKey key) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(PROPS.issuer()));
        return decoder;
    }
}
