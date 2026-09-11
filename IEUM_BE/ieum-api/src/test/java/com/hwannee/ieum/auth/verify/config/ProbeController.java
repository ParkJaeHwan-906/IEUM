package com.hwannee.ieum.auth.verify.config;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.auth.verify.principal.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {

    @GetMapping("/api/items/1")
    public String openItem() {
        return "item";
    }

    @GetMapping("/api/items/me")
    public AuthenticatedUser openMe(@CurrentUser AuthenticatedUser user) {
        return user;
    }

    @GetMapping("/probe/me")
    public AuthenticatedUser me(@CurrentUser AuthenticatedUser user) {
        return user;
    }

    @GetMapping("/probe/owner")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public String owner(@CurrentUser AuthenticatedUser user) {
        return user.uid();
    }
}
