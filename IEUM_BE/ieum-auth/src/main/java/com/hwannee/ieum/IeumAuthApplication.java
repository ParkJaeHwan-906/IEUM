package com.hwannee.ieum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 인증 서버. 자격 증명을 검증하고 토큰을 발급하는 유일한 프로세스다.
 * 클러스터에 하나만 띄운다.
 */
@SpringBootApplication
public class IeumAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(IeumAuthApplication.class, args);
    }
}
