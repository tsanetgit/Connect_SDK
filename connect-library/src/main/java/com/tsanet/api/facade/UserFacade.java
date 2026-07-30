package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.UserContextDto;
import java.util.List;

public interface UserFacade {
    UserContextDto getCurrentUser();

    List<UserContextDto> listStoredUsers();
}
