package com.localink.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发帖入参（M6-A）：images 为上传接口产出的 URL 逗号串，最多 9 张。
 */
@Data
public class PostCreateDTO {

    private Long shopId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题最长 255 字")
    private String title;

    @Size(max = 2048, message = "图片 URL 串超长（最多 9 张）")
    private String images;

    @NotBlank(message = "正文不能为空")
    @Size(max = 2048, message = "正文最长 2048 字")
    private String content;
}
