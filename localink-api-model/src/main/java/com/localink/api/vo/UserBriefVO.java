package com.localink.api.vo;

import lombok.Data;

/**
 * 用户简要视图（M6-B）：共同关注等列表场景的昵称/头像回填载体。
 */
@Data
public class UserBriefVO {

    private Long userId;

    private String nickName;

    private String icon;
}
