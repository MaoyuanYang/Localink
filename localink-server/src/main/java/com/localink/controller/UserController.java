package com.localink.controller;

import com.localink.api.dto.UserDTO;
import com.localink.api.dto.UserLoginDTO;
import com.localink.api.vo.SignVO;
import com.localink.common.result.Result;
import com.localink.framework.holder.UserHolder;
import com.localink.service.SignService;
import com.localink.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import com.localink.framework.auth.TokenRefreshInterceptor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SignService signService;

    @PostMapping("/login")
    public Result<String> login(@Validated @RequestBody UserLoginDTO dto) {
        return Result.ok(userService.login(dto.getPhone(), dto.getCode()));
    }

    @DeleteMapping("/logout")
    public Result<Void> logout(
            @org.springframework.web.bind.annotation.RequestHeader(TokenRefreshInterceptor.AUTH_HEADER) String token) {
        userService.logout(token);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserDTO> me() {
        return Result.ok(UserHolder.get());
    }

    @PostMapping("/sign")
    public Result<SignVO> checkIn() {
        return Result.ok(signService.checkIn());
    }

    @GetMapping("/sign")
    public Result<SignVO> signStatus() {
        return Result.ok(signService.status());
    }
}
