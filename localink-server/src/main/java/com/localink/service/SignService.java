package com.localink.service;

import com.localink.api.vo.SignVO;

/**
 * 用户签到（M6-F）：BitMap 按月存储（今天在最低位），签到幂等。
 */
public interface SignService {

    /**
     * 签到（重复签到幂等，返回最新状态）。
     */
    SignVO checkIn();

    /**
     * 签到状态：今日是否已签/连续天数（跨月）/本月累计。
     */
    SignVO status();
}
