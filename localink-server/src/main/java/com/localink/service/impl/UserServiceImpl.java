package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.SmsConstants;
import com.localink.constant.UserConstants;
import com.localink.entity.User;
import com.localink.mapper.UserMapper;
import com.localink.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String login(String phone, String code) {
        String cachedCode = stringRedisTemplate.opsForValue()
                .getAndDelete(SmsConstants.CODE_KEY_PREFIX + phone);
        if (cachedCode == null) {
            throw new LocalinkException(BaseCode.SMS_CODE_EXPIRED);
        }
        if (!cachedCode.equals(code)) {
            throw new LocalinkException(BaseCode.SMS_CODE_INVALID);
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = register(phone);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        saveSession(token, user);
        return token;
    }

    private User register(String phone) {
        User user = new User();
        user.setPhone(phone);
        userMapper.insert(user);
        user.setNickName("用户" + user.getId());
        user.setIcon("");
        user.setLevel(0);
        userMapper.updateById(user);
        return user;
    }

    private void saveSession(String token, User user) {
        Map<String, String> fields = new HashMap<>();
        fields.put(UserConstants.FIELD_ID, String.valueOf(user.getId()));
        fields.put(UserConstants.FIELD_PHONE, user.getPhone());
        fields.put(UserConstants.FIELD_NICK_NAME, user.getNickName());
        fields.put(UserConstants.FIELD_ICON, user.getIcon());
        fields.put(UserConstants.FIELD_LEVEL, String.valueOf(user.getLevel()));
        String key = UserConstants.TOKEN_KEY_PREFIX + token;
        stringRedisTemplate.opsForHash().putAll(key, fields);
        stringRedisTemplate.expire(key, Duration.ofSeconds(UserConstants.SESSION_TTL_SECONDS));
    }
}
