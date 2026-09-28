package com.localink.search;

import com.localink.entity.Post;
import lombok.Data;

import java.util.List;

/**
 * 帖子 ES 文档（M6-D，索引 `post`，_id=postId）。文本字段入索引前已 HTML 转义
 * （高亮 XSS 后端口径）；nickName 冗余自 DB（搜索结果免回查，改名不实时）。
 * createTime 为 yyyy-MM-dd HH:mm:ss 字符串（与 mapping 的 date format 对齐）。
 */
@Data
public class PostDocument {

    private Long postId;

    private String title;

    private String content;

    private String nickName;

    private Long shopId;

    private Long userId;

    private Integer liked;

    private String createTime;

    private List<String> images;

    public static PostDocument of(Post post, String nickName, String escapedTitle,
                                  String escapedContent, List<String> images) {
        PostDocument doc = new PostDocument();
        doc.setPostId(post.getId());
        doc.setTitle(escapedTitle);
        doc.setContent(escapedContent);
        doc.setNickName(nickName);
        doc.setShopId(post.getShopId());
        doc.setUserId(post.getUserId());
        doc.setLiked(post.getLiked());
        doc.setCreateTime(post.getCreateTime() == null ? null
                : post.getCreateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        doc.setImages(images);
        return doc;
    }
}
