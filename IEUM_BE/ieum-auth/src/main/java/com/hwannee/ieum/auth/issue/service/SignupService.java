package com.hwannee.ieum.auth.issue.service;

import com.hwannee.ieum.auth.issue.exception.AuthException;
import com.hwannee.ieum.auth.issue.web.dto.SignupRequest;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.Users;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import com.hwannee.ieum.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SignupService {

    private final UsersRepository users;
    private final UsersAccountRepository accounts;
    private final PasswordEncoder encoder;

    @Transactional
    public String signup(SignupRequest req) {
        if(req.userType() == UserType.ADMIN) {
            throw new AuthException.UnsupportedUserType();
        }
        if(users.existsByEmail(req.email())) {
            throw new AuthException.DuplicateAccount("이메일");
        }
        if(users.existsByTel(req.tel())) {
            throw new AuthException.DuplicateAccount("전화번호");
        }
        if(accounts.existsByNickname(req.nickname())) {
            throw new AuthException.DuplicateAccount("닉네임");
        }

        Users user = users.save(new Users(req.name(), req.tel(), req.email()));
        String uid = UUID.randomUUID().toString();
        accounts.save(new UsersAccount(user, req.userType(), uid, req.nickname(), encoder.encode(req.password())));
        return uid;
    }
}
