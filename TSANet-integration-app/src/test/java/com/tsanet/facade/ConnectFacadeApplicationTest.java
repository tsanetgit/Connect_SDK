package com.tsanet.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.TsaNetApiSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ConnectFacadeApplicationTest {
    @Autowired
    private TsaNetApiSession tsaNetApiSession;

    @Test
    void itStartsWithTsaNetApiSessionBean() {
        assertThat(tsaNetApiSession).isNotNull();
        assertThat(tsaNetApiSession.auth()).isNotNull();
        assertThat(tsaNetApiSession.auth().isAuthorized()).isFalse();
    }
}
