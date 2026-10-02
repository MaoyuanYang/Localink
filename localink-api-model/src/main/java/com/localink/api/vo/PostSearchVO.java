package com.localink.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 搜索命中视图（M6-D）：title/content 为原始值，*Highlight 为 ES 高亮片段（含 &lt;em&gt;，
 * 无命中高亮时为 null，前端用原始值兜底）。
 */
@Data
public class PostSearchVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String title;

    private String titleHighlight;

    private String contentHighlight;

    private String nickName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long shopId;

    private Integer liked;

    private String createTime;
}
