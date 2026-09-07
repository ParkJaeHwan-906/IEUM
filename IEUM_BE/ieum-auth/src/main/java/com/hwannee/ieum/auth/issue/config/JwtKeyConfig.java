package com.hwannee.ieum.auth.issue.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    public RSAKey signingKey(AuthProperties properties) throws Exception {
        KeyPair keyPair = StringUtils.hasText(properties.privateKey())
                ? loadFromPkcs8(properties.privateKey()) : generateEphemeral();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyIDFromThumbprint()
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
    }

    static KeyPair loadFromPkcs8(String base64Der) throws Exception {
        byte[] der = Base64.getMimeDecoder().decode(base64Der);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent())
        );
        return new KeyPair(pub, priv);
    }

    private static KeyPair generateEphemeral() throws Exception {
        log.warn("ieum.auth.private-key 가 비어 있어 임시 서명 키를 생성합니다. "
                + "재시작하면 발급된 토큰이 전부 무효화되며, 인증 서버를 둘 이상 띄우면 검증이 실패합니다. "
                + "scripts/gen-jwt-key.sh 로 키를 만들어 JWT_PRIVATE_KEY 에 넣으세요.");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
