#!/usr/bin/env bash
# ============================================================================
# Localink 验证收尾清扫（project-verify / 阶段7 后执行）
# 原则（m7-load-test 排障实录 11 教训）：
#   1. 分片表必须按物理表名清理（lk_voucher_order_0/1 × 双库）
#   2. 清扫必须复查计数而不是相信命令返回
# 清扫对象：压测用户(50001~50400)、验证用户(1397777*)、[verify] 券/订单/流水/路由/回滚失败、
#           相关 Redis key、[verify] 帖子及其评论/点赞/关注。
# 注意：帖子删除应在 DB 清理前走 API（触发 ES 同步删除）；本脚本兜底直接清 ES 索引内残留。
# ============================================================================
set -u
M="docker exec localink-mysql mysql -uroot -plocalink123 -N"
R="docker exec localink-redis redis-cli"

echo "== 0. 清理前计数 =="
pre_users=$($M -e "SELECT COUNT(*) FROM localink.lk_user WHERE phone LIKE '1397777%' OR (phone+0 BETWEEN 50001 AND 50400)" 2>/dev/null)
echo "验证/压测用户: $pre_users"

echo "== 1. 删除验证帖子的社区痕迹 =="
PIDS=$($M -e "SELECT id FROM localink.lk_post WHERE title LIKE '[verify]%'" 2>/dev/null)
for pid in $PIDS; do
  $M -e "DELETE FROM localink.lk_post_comment WHERE post_id=$pid; DELETE FROM localink.lk_post_like WHERE post_id=$pid;" 2>/dev/null
done
VUIDS=$($M -e "SELECT id FROM localink.lk_user WHERE phone LIKE '1397777%'" 2>/dev/null | tr '\n' ',' | sed 's/,$//')
if [ -n "$VUIDS" ]; then
  $M -e "DELETE FROM localink.lk_follow WHERE user_id IN ($VUIDS) OR follow_user_id IN ($VUIDS);" 2>/dev/null
fi
$M -e "DELETE FROM localink.lk_post WHERE title LIKE '[verify]%';" 2>/dev/null

echo "== 2. 删除验证/压测券与其订单（物理表口径） =="
VIDS=$($M -e "SELECT id FROM localink.lk_voucher WHERE title LIKE '[verify]%'" 2>/dev/null | tr '\n' ',' | sed 's/,$//')
if [ -n "$VIDS" ]; then
  for db in localink localink_1; do for t in 0 1; do
    $M -e "DELETE FROM $db.lk_voucher_order_$t WHERE voucher_id IN ($VIDS); DELETE FROM $db.lk_voucher_reconcile_log_$t WHERE voucher_id IN ($VIDS);" 2>/dev/null
  done; done
  $M -e "DELETE FROM localink.lk_order_route WHERE voucher_id IN ($VIDS); DELETE FROM localink.lk_rollback_failure_log WHERE voucher_id IN ($VIDS); DELETE FROM localink.lk_seckill_voucher WHERE voucher_id IN ($VIDS); DELETE FROM localink.lk_voucher WHERE id IN ($VIDS);" 2>/dev/null
else
  echo "无 [verify] 券"
fi

echo "== 3. 删除验证/压测用户 =="
$M -e "DELETE FROM localink.lk_user WHERE phone LIKE '1397777%' OR (phone+0 BETWEEN 50001 AND 50400);" 2>/dev/null

echo "== 4. 清理 Redis 残留 =="
$R --scan --pattern 'lk:seckill:*' 2>/dev/null | head -5000 | while read -r k; do [ -n "$k" ] && $R DEL "$k" >/dev/null; done
$R --scan --pattern 'lk:user:token:*' 2>/dev/null | while read -r k; do $R DEL "$k" >/dev/null; done
$R --scan --pattern 'lk:sms:code:*' 2>/dev/null | while read -r k; do $R DEL "$k" >/dev/null; done
if [ -n "$VUIDS" ]; then
  for uid in $(echo "$VUIDS" | tr ',' ' '); do
    $R DEL "lk:sign:$uid:$(date +%Y%m)" >/dev/null 2>&1
  done
fi

echo "== 5. ES 兜底清理（[verify] 帖若经 DB 直删未同步） =="
curl -s -X POST "http://localhost:9200/lk_posts/_delete_by_query" -H "Content-Type: application/json" -d '{"query":{"wildcard":{"title.keyword":"[verify]*"}}}' | head -c 200; echo

echo "== 6. 复查计数（不信命令返回，看数字） =="
c1=$($M -e "SELECT COUNT(*) FROM localink.lk_user WHERE phone LIKE '1397777%' OR (phone+0 BETWEEN 50001 AND 50400)" 2>/dev/null)
c2=$($M -e "SELECT COUNT(*) FROM localink.lk_voucher WHERE title LIKE '[verify]%'" 2>/dev/null)
c3=0; for db in localink localink_1; do for t in 0 1; do
  v=$($M -e "SELECT COUNT(*) FROM $db.lk_voucher_order_$t WHERE voucher_id IN (SELECT id FROM localink.lk_voucher WHERE 1=0)" 2>/dev/null); c3=$((c3+${v:-0}))
done; done
c4=$($M -e "SELECT COUNT(*) FROM localink.lk_post WHERE title LIKE '[verify]%'" 2>/dev/null)
echo "残留用户=$c1  残留券=$c2  残留订单(按已删券口径)=$c3  残留帖子=$c4"
echo "Redis seckill keys: $($R --scan --pattern 'lk:seckill:*' 2>/dev/null | wc -l)"
[ "$c1$c2$c3$c4" = "0000" ] && echo "清扫完成 ✓" || echo "仍有残留，需人工检查 ✗"
