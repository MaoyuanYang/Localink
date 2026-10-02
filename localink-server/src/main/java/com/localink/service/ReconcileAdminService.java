package com.localink.service;

import com.localink.api.vo.PageVO;
import com.localink.api.vo.ReconcileLogVO;
import com.localink.api.vo.RollbackFailureVO;

/**
 * 对账运营看板（W3）：两表只读查询。GET 匿名可达（拦截器只拦非 GET），实现首行显式判登录。
 */
public interface ReconcileAdminService {

    /**
     * 对账流水分页：reconciliationStatus 筛选（null=全部；"未收敛"口径为 !=4），按雪花 id 倒序近似时间序
     * （表无 create_time 索引）。
     */
    PageVO<ReconcileLogVO> pageLogs(long page, long size, Integer reconciliationStatus);

    /**
     * 回滚失败表现存行分页（retryAttempts 倒序近似最新），成功重试的行已被 Job 删除。
     */
    PageVO<RollbackFailureVO> pageFailures(long page, long size);
}
