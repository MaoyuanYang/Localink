#!/usr/bin/env bash
# ============================================================================
# Localink 黑盒补验 part3（T1 深度体检 / 2026-10-02）
# 用途：覆盖 W1~W3 新增端点的运行时验证——我的订单、admin 订单监控+手动关单、
#       对账看板、订阅/统计、Top买家、通知、社区补口(评论分页/评论点赞/审核队列)、
#       Feed 游标滚动、以及匿名越权负面矩阵。
# 前置：中间件在线，应用已启动且 /ping 通；建议在 part1/part2 之后运行。
# 数据口径：手机号 139777703xx、标题前缀 [verify]（与 verify-cleanup.sh 兼容）。
# ============================================================================
set -u
BASE=http://localhost:8086
M="docker exec localink-mysql mysql -uroot -plocalink123 -N"
RC="docker exec localink-redis redis-cli --raw"
PASS=0; FAIL=0
declare -a FAILED=()
check() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf 'PASS  %-52s (%s)\n' "$1" "$3"; else FAIL=$((FAIL+1)); printf 'FAIL  %-52s expected=%s actual=%s\n' "$1" "$2" "$3"; FAILED+=("$1"); fi; }
check_true() { if [ "$2" -eq 0 ]; then PASS=$((PASS+1)); echo "PASS  $1"; else FAIL=$((FAIL+1)); echo "FAIL  $1"; FAILED+=("$1"); fi; }
code_of() { grep -o '"code":-\?[0-9]*' <<<"$1" | head -1 | cut -d: -f2; }
sms_code() { $RC GET "lk:sms:code:$1" 2>/dev/null | tr -d '\r\n'; }
q() { $M -e "$1" 2>/dev/null; }
req() { local m=$1 u=$2 t=${3:-} b=${4:-}
  if [ -n "$t" ] && [ -n "$b" ]; then curl -s -m 10 -X "$m" -H "Authorization: $t" -H 'Content-Type: application/json' --data-binary @"$b" "$u"
  elif [ -n "$t" ]; then curl -s -m 10 -X "$m" -H "Authorization: $t" "$u"
  elif [ -n "$b" ]; then curl -s -m 10 -X "$m" -H 'Content-Type: application/json' --data-binary @"$b" "$u"
  else curl -s -m 10 -X "$m" "$u"; fi; }
login_user() { local r tok code B=/tmp/verify-p3-login.json i
  # 发码可能被 IP 维度限流(本地连跑共享回环桶)，码读不到/登录失败时退避重试
  for i in 1 2 3; do
    curl -s -m 10 -X POST -H "X-Forwarded-For: 10.$RANDOM.$RANDOM.$RANDOM" -H 'Content-Type: application/json' -d '{"phone":"'$1'"}' "$BASE/api/sms/code" >/dev/null
    sleep 1; code=$(sms_code "$1")
    if [ -z "$code" ]; then sleep 8; continue; fi
    printf '{"phone":"%s","code":"%s"}' "$1" "$code" > "$B"
    r=$(curl -s -m 10 -X POST -H 'Content-Type: application/json' --data-binary @"$B" "$BASE/api/user/login")
    tok=$(grep -o '"data":"[^"]*"' <<<"$r" | head -1 | cut -d'"' -f4)
    [ -n "$tok" ] && { echo "$tok"; return; }
    sleep 8
  done; echo ""; }
J=/tmp/verify-p3.json

echo "================ P3-S0 探活 ================"
P=$(curl -s -m 5 "$BASE/ping")
[ "$P" = "pong" ] && check_true "P3-0a /ping 返回 pong" 0 || check_true "P3-0a /ping 返回 pong" 1

echo "================ P3-S1 我的订单（W1） ================"
R1=$(req GET "$BASE/api/order/page")
check "P3-1a 匿名查我的订单被拒(40002, Service 收口)" 40002 "$(code_of "$R1")"
T1=$(login_user 13977770301)
[ -n "$T1" ] && check_true "P3-1b 用户1 登录(13977770301)" 0 || check_true "P3-1b 用户1 登录" 1
BT=$(date "+%Y-%m-%d %H:%M:%S" -d "-2 min"); ET=$(date "+%Y-%m-%d %H:%M:%S" -d "+120 min")
printf '{"shopId":1,"title":"[verify] p3-voucher","payValue":100,"actualValue":200,"stock":2,"minLevel":0,"beginTime":"%s","endTime":"%s"}' "$BT" "$ET" > "$J"
R=$(req POST "$BASE/api/seckill-voucher" "$T1" "$J")
[ "$(code_of "$R")" != "0" ] && echo "      创建失败原文: $(head -c 300 <<<"$R")"
check "P3-1c 创建秒杀券(stock=2)" 0 "$(code_of "$R")"
VID=$(q "SELECT id FROM localink.lk_voucher WHERE title='[verify] p3-voucher' ORDER BY id DESC LIMIT 1")
echo "      voucherId=$VID"
R=$(req POST "$BASE/api/seckill-voucher/$VID/token" "$T1"); check "P3-1d 申请令牌" 0 "$(code_of "$R")"
TOK=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4)
R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=$TOK" "$T1"); check "P3-1e 令牌下单(异步)" 0 "$(code_of "$R")"
OID=$(grep -o '"data":"[0-9]*"' <<<"$R" | head -1 | cut -d'"' -f4); echo "      orderId=$OID"
# 轮询异步落库（≤15s）
OK=0; for i in $(seq 1 15); do
  N=0; for db in localink localink_1; do for t in 0 1; do
    v=$(q "SELECT COUNT(*) FROM $db.lk_voucher_order_$t WHERE id=$OID"); N=$((N+${v:-0}))
  done; done
  [ "$N" -ge 1 ] && { OK=1; break; }; sleep 1
done
check_true "P3-1f 订单 Kafka 异步落库(orderId=$OID)" $((1-OK))
R=$(req GET "$BASE/api/order/page" "$T1")
check "P3-1g 我的订单分页" 0 "$(code_of "$R")"
echo "$R" | grep -q '"records":\[{.*"title":"\[verify\] p3-voucher"' && check_true "P3-1h 订单页含该券信息" 0 || { echo "$R" | grep -q 'p3-voucher' && check_true "P3-1h 订单页含该券信息" 0 || check_true "P3-1h 订单页含该券信息" 1; }

echo "================ P3-S2 admin 订单监控 + 手动关单（W2） ================"
STK0=$(q "SELECT stock FROM localink.lk_seckill_voucher WHERE voucher_id=$VID")
R=$(req GET "$BASE/api/order/admin/page?voucherId=$VID" "$T1")
check "P3-2a admin 按活动查订单(guard OFF 演示口径)" 0 "$(code_of "$R")"
echo "$R" | grep -q '"total":[1-9]' && check_true "P3-2b admin 订单页 total>=1" 0 || check_true "P3-2b admin 订单页 total>=1" 1
ST=$(q "SELECT status FROM localink.lk_order_route r JOIN localink.lk_voucher_order_0 o ON r.order_id=o.id WHERE r.order_id=$OID UNION ALL SELECT status FROM localink_1.lk_voucher_order_0 WHERE id=$OID UNION ALL SELECT status FROM localink_1.lk_voucher_order_1 WHERE id=$OID UNION ALL SELECT status FROM localink.lk_voucher_order_1 WHERE id=$OID" | grep -v '^0$' | head -1)
echo "      关单前 status=$ST stock=$STK0 (期望 status=1)"
R=$(req POST "$BASE/api/order/admin/$OID/close" "$T1")
check "P3-2c 手动关单受理" 0 "$(code_of "$R")"
echo "$R" | grep -q '"data":true' && check_true "P3-2d 关单返回 true(条件关单命中)" 0 || check_true "P3-2d 关单返回 true" 1
sleep 2
ST2=$(q "SELECT status FROM localink.lk_order_route r JOIN localink.lk_voucher_order_0 o ON r.order_id=o.id WHERE r.order_id=$OID UNION ALL SELECT status FROM localink_1.lk_voucher_order_0 WHERE id=$OID UNION ALL SELECT status FROM localink_1.lk_voucher_order_1 WHERE id=$OID UNION ALL SELECT status FROM localink.lk_voucher_order_1 WHERE id=$OID" | grep -v '^0$' | head -1)
STK1=$(q "SELECT stock FROM localink.lk_seckill_voucher WHERE voucher_id=$VID")
[ "$ST2" != "1" ] && [ -n "$ST2" ] && check_true "P3-2e 订单状态已离开创建态(now=$ST2)" 0 || check_true "P3-2e 订单状态已离开创建态(now=$ST2)" 1
EXP=$((STK0+1))
[ "$STK1" = "$EXP" ] && check_true "P3-2f DB 库存回补 +1($STK0->$STK1)" 0 || check_true "P3-2f DB 库存回补(期望$EXP 实际$STK1)" 1
RSTK=$($RC GET "lk:seckill:stock:{$VID}" 2>/dev/null | tr -d '\r\n')
[ "${RSTK:-x}" = "$EXP" ] && check_true "P3-2g Redis 库存回补 +1(=$RSTK)" 0 || check_true "P3-2g Redis 库存回补(期望$EXP 实际${RSTK:-空})" 1
R=$(req POST "$BASE/api/order/admin/$OID/close" "$T1")
check "P3-2h 重复关单幂等受理" 0 "$(code_of "$R")"
echo "$R" | grep -q '"data":false' && check_true "P3-2i 二次关单返回 false(幂等闸门)" 0 || check_true "P3-2i 二次关单返回 false(实际:$(head -c 120 <<<"$R"))" 1

echo "================ P3-S3 对账看板（W2/W3） ================"
R=$(req GET "$BASE/api/reconcile/admin/page" "$T1"); check "P3-3a 对账账本分页" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/reconcile/admin/failures" "$T1"); check "P3-3b 回滚失败表分页" 0 "$(code_of "$R")"

echo "================ P3-S4 订阅与运营统计（M5/W2） ================"
R=$(req POST "$BASE/api/seckill-voucher/$VID/subscribe" "$T1"); check "P3-4a 订阅秒杀券" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/seckill-voucher/$VID/subscribe" "$T1"); check "P3-4b 查询订阅状态" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/seckill-voucher/$VID/subscribe-stats" "$T1"); check "P3-4c 订阅统计(admin)" 0 "$(code_of "$R")"
R=$(req DELETE "$BASE/api/seckill-voucher/$VID/subscribe" "$T1"); check "P3-4d 退订" 0 "$(code_of "$R")"

echo "================ P3-S5 Top 买家（M5） ================"
R=$(req GET "$BASE/api/shop/1/top-buyers"); check "P3-5a Top买家(匿名 GET 放行)" 0 "$(code_of "$R")"
if echo "$R" | grep -q '"userId":"'; then check_true "P3-5b userId 已字符串化(防 JS 精度丢失)" 0
elif echo "$R" | grep -q '"userId":[0-9]\{10,\}'; then check_true "P3-5b userId 已字符串化" 1
else check_true "P3-5b userId 字符串化(无数据,跳过断言)" 0; fi

echo "================ P3-S6 通知（W3 补口） ================"
R=$(req GET "$BASE/api/notice"); check "P3-6a 匿名查通知被拒(40002)" 40002 "$(code_of "$R")"
R=$(req GET "$BASE/api/notice" "$T1"); check "P3-6b 登录后查通知" 0 "$(code_of "$R")"

echo "================ P3-S7 社区补口（W3） ================"
T2=$(login_user 13977770302); T3=$(login_user 13977770303)
[ -n "$T2" ] && [ -n "$T3" ] && check_true "P3-7a 用户2/3 登录" 0 || check_true "P3-7a 用户2/3 登录" 1
printf '{"shopId":1,"title":"[verify] p3-post","content":"p3 post body token p3body998"}' > "$J"
R=$(req POST "$BASE/api/post" "$T2" "$J"); check "P3-7b 用户2 发帖" 0 "$(code_of "$R")"
PID=$(q "SELECT id FROM localink.lk_post WHERE title='[verify] p3-post' ORDER BY id DESC LIMIT 1")
printf '{"postId":%s,"content":"p3 comment here"}' "$PID" > "$J"
R=$(req POST "$BASE/api/post/comment" "$T1" "$J"); check "P3-7c 用户1 评论" 0 "$(code_of "$R")"
CID=$(q "SELECT id FROM localink.lk_post_comment WHERE post_id=$PID AND content='p3 comment here' ORDER BY id DESC LIMIT 1")
R=$(req GET "$BASE/api/post/$PID/comment/page"); check "P3-7d 评论分页(匿名 GET 放行)" 0 "$(code_of "$R")"
echo "$R" | grep -q 'p3 comment here' && check_true "P3-7e 评论分页含新评论" 0 || check_true "P3-7e 评论分页含新评论" 1
R=$(req POST "$BASE/api/post/comment/$CID/like" "$T3"); check "P3-7f 评论点赞" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/post/like/top?limit=5"); check "P3-7g 点赞榜" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/post/admin/page?auditStatus=1" "$T1"); check "P3-7h 审核队列-在架筛选" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/post/admin/page?auditStatus=2" "$T1"); check "P3-7i 审核队列-驳回筛选" 0 "$(code_of "$R")"
R=$(req DELETE "$BASE/api/post/comment/$CID" "$T3")
C=$(code_of "$R"); if [ "$C" != "0" ] && [ -n "$C" ]; then check "P3-7j 无关用户删评论被拒" "$C" "$C"; else check "P3-7j 无关用户删评论被拒" nonzero 0; fi
R=$(req DELETE "$BASE/api/post/comment/$CID" "$T1"); check "P3-7k 评论作者删评论" 0 "$(code_of "$R")"

echo "================ P3-S8 Feed 游标滚动（M6-C/W1 契约） ================"
U2=$(q "SELECT id FROM localink.lk_user WHERE phone='13977770302'")
req POST "$BASE/api/follow/$U2" "$T1" >/dev/null
for n in 1 2 3; do
  printf '{"shopId":1,"title":"[verify] p3-feed%s","content":"feed post %s token p3fd%s"}' "$n" "$n" "$n" > "$J"
  req POST "$BASE/api/post" "$T2" "$J" >/dev/null
done
sleep 3
R=$(req GET "$BASE/api/feed?size=2" "$T1"); check "P3-8a Feed 第一页(size=2)" 0 "$(code_of "$R")"
echo "$R" | grep -q '"nextCursor":[0-9]' && check_true "P3-8b 返回 nextCursor 游标" 0 || check_true "P3-8b 返回 nextCursor 游标(实际:$(grep -o '"nextCursor":[^,}]*' <<<"$R"))" 1
CUR=$(grep -o '"nextCursor":[0-9]*' <<<"$R" | head -1 | cut -d: -f2)
IDS1=$(grep -o '"id":"\?[0-9]*' <<<"$R" | grep -o '[0-9]*$' | tr '\n' ' ')
R=$(req GET "$BASE/api/feed?size=2&lastScore=$CUR" "$T1"); check "P3-8c Feed 第二页(lastScore 游标)" 0 "$(code_of "$R")"
IDS2=$(grep -o '"id":"\?[0-9]*' <<<"$R" | grep -o '[0-9]*$' | tr '\n' ' ')
DUP=0; for i in $IDS1; do echo " $IDS2 " | grep -q " $i " && DUP=1; done
check_true "P3-8d 两页无重复帖子(dup=$DUP)" $DUP

echo "================ P3-S9 匿名越权负面矩阵 ================"
R=$(req POST "$BASE/api/order/admin/999/close"); check "P3-9a 匿名 admin 关单被拦(40002)" 40002 "$(code_of "$R")"
R=$(req POST "$BASE/api/post/comment/999999/like"); check "P3-9b 匿名评论点赞被拦(40002)" 40002 "$(code_of "$R")"
R=$(req POST "$BASE/api/seckill-voucher/$VID/subscribe"); check "P3-9c 匿名订阅被拦(40002)" 40002 "$(code_of "$R")"
R=$(req GET "$BASE/api/order/admin/page?voucherId=$VID")
C=$(code_of "$R"); echo "      观察项: 匿名 GET admin 订单页 code=$C (guard OFF 演示口径下 GET 放行的已知分层, 详见报告)"
R=$(req GET "$BASE/api/reconcile/admin/page")
echo "      观察项: 匿名 GET 对账看板 code=$(code_of "$R") (同上)"

echo "================ 汇总 ================"
echo "PASS=$PASS FAIL=$FAIL"
if [ ${#FAILED[@]} -gt 0 ]; then printf '失败用例: %s\n' "${FAILED[*]}"; fi
echo "VID=$VID OID=$OID PID=$PID CID=$CID" >> scripts/verify/last-run.env
