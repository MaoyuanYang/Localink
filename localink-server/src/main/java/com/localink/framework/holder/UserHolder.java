package com.localink.framework.holder;

import com.localink.api.dto.UserDTO;

public final class UserHolder {

    private static final ThreadLocal<UserDTO> HOLDER = new ThreadLocal<>();

    private UserHolder() {
    }

    public static void set(UserDTO user) {
        HOLDER.set(user);
    }

    public static UserDTO get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
