package com.localink.cache;

import java.util.List;

/**
 * Redis GEO 结构操作（M6-F 扩组）。GEO 底层 = ZSet + geohash 52 位整数 score：
 * 经纬度编码进 score，检索按编码区间圈候选再算精确距离，不是两两算距离。
 */
public interface RedisGeoOps {

    /**
     * 写入/更新成员坐标（GEOADD，member 已存在则覆盖——坐标变更即更新）。
     */
    void add(KeyBuild key, double longitude, double latitude, String member);

    /**
     * 移除成员。
     */
    void remove(KeyBuild key, String... members);

    /**
     * 附近检索（GEOSEARCH）：以 (longitude, latitude) 为圆心 radiusMeters 半径内，
     * 按距离升序取最多 count 个，携带距离（米）。
     */
    List<GeoEntry> search(KeyBuild key, double longitude, double latitude,
                          double radiusMeters, int count);

    /**
     * GEO 检索结果：member + 距离（米），已按距离升序。
     */
    record GeoEntry(String member, double distanceMeters) {
    }
}
