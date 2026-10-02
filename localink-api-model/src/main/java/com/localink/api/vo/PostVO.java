package com.localink.api.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子视图（M6-A）：作者昵称批量回填；images 拆为数组便于前端渲染。
 * meLiked（M6-B）：当前用户是否已赞——仅详情回填，未登录为 null，列表不查。
 */
@Data
public class PostVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long shopId;

    private String title;

    private List<String> images;

    private String content;

    private Integer liked;

    /**
     * 当前用户是否已赞；仅详情回填（列表页逐帖判重是 N 次查询，演进声明见任务卡 M6-B）。
     */
    private Boolean meLiked;

    private Integer comments;

    private Integer viewed;

    /**
     * 审核状态：1 在架 / 2 驳回（W3 admin 查询填充；状态机先发后审无 0 待审态）。
     */
    private Integer auditStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
