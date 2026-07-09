package com.tsanet.facade.session;

import java.util.Optional;

public interface AccountSessionView {
    Optional<String> activeAccountLabel();

    Optional<String> activeSqlitePath();
}
