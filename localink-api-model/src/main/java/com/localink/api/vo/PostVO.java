package com.localink.api.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子视图（M6-A）：作者昵称批量回填；images 拆为数组便于前端渲染。
 */
@Data
public class PostVO {

    private Long id;

    private Long userId;

    private String nickName;

    private Long shopId;

    private String title;

    private List<String> images;

    private String content;

    private Integer liked;

    private Integer comments;

    private Integer viewed;

    private LocalDateTime createTime;
}
