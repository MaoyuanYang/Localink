package com.localink.api.vo;

import lombok.Data;

/**
 * 签到状态视图（M6-F）：continuousDays 含跨月连续；monthDays=本月累计签到天数。
 */
@Data
public class SignVO {

    private Boolean signedToday;

    private Integer continuousDays;

    private Integer monthDays;
}
