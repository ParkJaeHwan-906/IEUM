package com.hwannee.ieum.auth.issue.service;

import com.hwannee.ieum.auth.issue.config.AuthProperties;
import com.hwannee.ieum.auth.issue.exception.AuthException;
import com.hwannee.ieum.auth.issue.jwt.AccessTokenIssuer;
import com.hwannee.ieum.auth.issue.token.RefreshTokenStore;
import com.hwannee.ieum.auth.issue.web.dto.TokenResponse;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UsersAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenStore refreshTokens;
    private final AuthProperties props;
    private final String dummyHash;

    public AuthService(UsersAccountRepository accounts, PasswordEncoder encoder,
                       AccessTokenIssuer accessTokens, RefreshTokenStore refreshTokens, AuthProperties props) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.props = props;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    @Transactional(readOnly = true)
    public TokenResponse login(String email, String rawPassword) {
        UsersAccount account = accounts.findByEmail(email).orElse(null);
        String hash = account != null ? account.getPassword() : dummyHash;
        boolean matches = encoder.matches(rawPassword, hash);
        if (account == null || !matches) {
            throw new AuthException.InvalidCredentials();
        }
        return issuePair(account);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        String uid = refreshTokens.consume(refreshToken)
                .orElseThrow(AuthException.InvalidRefreshToken::new);
        UsersAccount account = accounts.findByUid(uid)
                .orElseThrow(AuthException.InvalidRefreshToken::new);
        return issuePair(account);
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private TokenResponse issuePair(UsersAccount account) {
        String access = accessTokens.issue(account.getUid(), account.getUserType(), account.getNickname());
        String refresh = refreshTokens.issue(account.getUid());
        return TokenResponse.of(access, refresh, props.accessTokenTtl());
    }
}
