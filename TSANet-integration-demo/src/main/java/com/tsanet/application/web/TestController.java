package com.tsanet.application.web;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    private final TsaNetApiSession tsaNetApiSession;

    public TestController(TsaNetApiSession tsaNetApiSession) {
        this.tsaNetApiSession = tsaNetApiSession;
    }

    @GetMapping("/test")
    public List<CollaborationRequestStatusDto> test() {
        tsaNetApiSession.auth().loginWithConfiguredCredentials();
        return tsaNetApiSession.collaborationRequests().listRequests();
    }
}
