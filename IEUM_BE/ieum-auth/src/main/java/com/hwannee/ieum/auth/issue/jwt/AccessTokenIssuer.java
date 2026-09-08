package com.hwannee.ieum.auth.issue.jwt;

import com.hwannee.ieum.auth.issue.config.AuthProperties;
import com.hwannee.ieum.users.domain.UserType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import java.util.UUID;

@Component
public class AccessTokenIssuer {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_NICKNAME = "nickname";

    private final JwtEncoder encoder;
    private final String keyId;
    private final AuthProperties props;

    public AccessTokenIssuer(JwtEncoder encoder, RSAKey signingKey, AuthProperties props) {
        this.encoder = encoder;
        this.keyId = signingKey.getKeyID();
        this.props = props;
    }

    public String issue(String uid, UserType role, String nickname) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer()).subject(uid).id(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plus(props.accessTokenTtl()))
                .claim(CLAIM_ROLE, role.name()).claim(CLAIM_NICKNAME, nickname)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
