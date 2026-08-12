package com.tsanet.demo.web;

import com.tsanet.api.connectapi.dto.UserContextDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final SessionGuard guard;

    public MeController(SessionGuard guard) {
        this.guard = guard;
    }

    @GetMapping("/api/me")
    public UserContextDto me() {
        return guard.session().users().getCurrentUser();
    }
}
