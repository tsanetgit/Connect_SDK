package com.tsanet.application.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tsanet.api.TsaNetApiSession;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TsaNetApiSessionBeanCloseTest {
    private static final TsaNetApiSession SESSION = mock(TsaNetApiSession.class);

    @Configuration
    static class SessionBean {
        @Bean
        TsaNetApiSession session() {
            return SESSION;
        }
    }

    @Test
    void aSessionBeanIsClosedWhenItsContextShutsDown() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SessionBean.class);
        verify(SESSION, never()).close();

        context.close();

        verify(SESSION).close();
    }
}
