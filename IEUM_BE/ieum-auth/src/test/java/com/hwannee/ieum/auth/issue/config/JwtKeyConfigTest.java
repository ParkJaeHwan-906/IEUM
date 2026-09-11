package com.hwannee.ieum.auth.issue.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtKeyConfigTest {

    private final JwtKeyConfig config = new JwtKeyConfig();

    @Test
    void loadsPkcs8PrivateKeyAndRestoresMatchingPublicKey() throws Exception {
        KeyPair pair = generate();

        RSAKey key = config.signingKey(props(base64(pair), List.of()));

        RSAPublicKey expected = (RSAPublicKey) pair.getPublic();
        assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(expected.getModulus());
        assertThat(key.toRSAPublicKey().getPublicExponent()).isEqualTo(expected.getPublicExponent());
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    }

    @Test
    void blankPrivateKeyFallsBackToEphemeralKey() throws Exception {
        RSAKey key = config.signingKey(props("", List.of()));

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getKeyID()).isNotBlank();
    }

    @Test
    void jwksExposesOnlyPublicPartsOfCurrentAndPreviousKeys() throws Exception {
        AuthProperties props = props(base64(generate()), List.of(base64(generate())));
        RSAKey signingKey = config.signingKey(props);

        JWKSet jwkSet = config.publicJwkSet(signingKey, props);

        assertThat(jwkSet.getKeys()).hasSize(2);
        assertThat(jwkSet.getKeys()).noneMatch(JWK::isPrivate);
        assertThat(jwkSet.getKeyByKeyId(signingKey.getKeyID())).isNotNull();
    }

    @Test
    void previousKeyEqualToCurrentKeyIsSkipped() throws Exception {
        String same = base64(generate());
        AuthProperties props = props(same, List.of(same));
        RSAKey signingKey = config.signingKey(props);

        JWKSet jwkSet = config.publicJwkSet(signingKey, props);

        assertThat(jwkSet.getKeys()).hasSize(1);
    }

    private static AuthProperties props(String privateKey, List<String> previousKeys) {
        return new AuthProperties("http://localhost:8081", privateKey, previousKeys,
                Duration.ofMinutes(15), Duration.ofDays(14));
    }

    private static KeyPair generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String base64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}