#!/usr/bin/env bash
# 黑盒补验（part2）：修正 part1 中的脚本问题后重验——UTF-8 文件体、必填字段、时序
set -u
BASE=http://localhost:8086
M="docker exec localink-mysql mysql -uroot -plocalink123 -N"
PASS=0; FAIL=0
check() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf 'PASS  %-50s (%s)\n' "$1" "$3"; else FAIL=$((FAIL+1)); printf 'FAIL  %-50s expected=%s actual=%s\n' "$1" "$2" "$3"; fi; }
check_true() { if [ "$2" -eq 0 ]; then PASS=$((PASS+1)); echo "PASS  $1"; else FAIL=$((FAIL+1)); echo "FAIL  $1"; fi; }
code_of() { grep -o '"code":-\?[0-9]*' <<<"$1" | head -1 | cut -d: -f2; }
sms_code() { docker exec localink-redis redis-cli --raw GET "lk:sms:code:$1" | tr -d '\r\n'; }
req() { local m=$1 u=$2 t=${3:-} b=${4:-}
  if [ -n "$t" ] && [ -n "$b" ]; then curl -s -m 10 -X "$m" -H "Authorization: $t" -H 'Content-Type: application/json' --data-binary @"$b" "$u"
  elif [ -n "$t" ]; then curl -s -m 10 -X "$m" -H "Authorization: $t" "$u"
  elif [ -n "$b" ]; then curl -s -m 10 -X "$m" -H 'Content-Type: application/json' --data-binary @"$b" "$u"
  else curl -s -m 10 -X "$m" "$u"; fi; }
login_user() { local r tok code B=/tmp/verify-login.json
  curl -s -m 10 -X POST -H "X-Forwarded-For: 10.$RANDOM.$RANDOM.$RANDOM" -H 'Content-Type: application/json' -d '{"phone":"'$1'"}' "$BASE/api/sms/code" >/dev/null
  sleep 1; code=$(sms_code "$1")
  printf '{"phone":"%s","code":"%s"}' "$1" "$code" > "$B"
  r=$(curl -s -m 10 -X POST -H 'Content-Type: application/json' --data-binary @"$B" "$BASE/api/user/login")
  tok=$(grep -o '"data":"[^"]*"' <<<"$r" | head -1 | cut -d'"' -f4); echo "$tok"; }
J=/tmp/verify-p2.json   # UTF-8 请求体文件（printf 写入，避开终端 GBK）

echo "================ P2-S1 补验：商户更新+缓存失效 ================"
T1=$(login_user 13977770001)
printf '{"id":1,"name":"[verify]renamed-p2","typeId":1,"address":"x","avgPrice":100,"longitude":116.397,"latitude":39.909}' > "$J"
R=$(req PUT "$BASE/api/shop" "$T1" "$J"); check "P2-1a 更新商户名(含经纬度)" 0 "$(code_of "$R")"
sleep 1
R=$(curl -s -m 10 "$BASE/api/shop/1")
echo "$R" | grep -q 'renamed-p2' && check_true "P2-1b 改名后读到新值(缓存失效生效)" 0 || check_true "P2-1b 改名后读到新值" 1
OLD=$($M -e "SELECT name FROM localink.lk_shop WHERE id=1" 2>/dev/null)
printf '{"id":1,"name":"'"$OLD"'","typeId":1,"address":"x","avgPrice":100,"longitude":116.397,"latitude":39.909}' > "$J"
req PUT "$BASE/api/shop" "$T1" "$J" >/dev/null; echo "      (已还原: $OLD)"

echo "================ P2-S2 秒杀两步流全链路 ================"
BT=$(date "+%Y-%m-%d %H:%M:%S" -d "-2 min"); ET=$(date "+%Y-%m-%d %H:%M:%S" -d "+120 min")
printf '{"shopId":1,"title":"[verify]p2-voucher","payValue":100,"actualValue":200,"stock":5,"minLevel":0,"beginTime":"%s","endTime":"%s"}' "$BT" "$ET" > "$J"
R=$(req POST "$BASE/api/seckill-voucher" "$T1" "$J")
if [ "$(code_of "$R")" != "0" ]; then echo "      创建失败原文: $(head -c 300 <<<"$R")"; fi
check "P2-2a 创建秒杀券(stock=5)" 0 "$(code_of "$R")"
VID=$($M -e "SELECT id FROM localink.lk_seckill_voucher WHERE title='[verify]p2-voucher' ORDER BY id DESC LIMIT 1" 2>/dev/null)
echo "      voucherId=$VID"
R=$(req POST "$BASE/api/seckill-voucher/$VID/token" "$T1"); check "P2-2b 申请令牌" 0 "$(code_of "$R")"
TOK=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4)
R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=$TOK" "$T1"); check "P2-2c 令牌下单(异步受理)" 0 "$(code_of "$R")"
OID=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4); echo "      orderId=$OID"
sleep 5
N=0; for db in localink localink_1; do for t in 0 1; do
  v=$($M -e "SELECT COUNT(*) FROM $db.lk_voucher_order_$t WHERE voucher_id=$VID" 2>/dev/null); N=$((N+${v:-0}))
done; done
[ "${N:-0}" -ge 1 ] && check_true "P2-2d 订单经 Kafka 异步落库(双库物理表, 命中 $N 条)" 0 || check_true "P2-2d 订单异步落库" 1
# 双库路由断言：user1 的奇偶决定落哪个库
U1=$($M -e "SELECT id FROM localink.lk_user WHERE phone='13977770001'" 2>/dev/null)
EXPDB=$([ $((U1%2)) -eq 1 ] && echo localink_1 || echo localink)
LOC=$($M -e "SELECT COUNT(*) FROM $EXPDB.lk_voucher_order_0 WHERE voucher_id=$VID" 2>/dev/null)
LOC2=$($M -e "SELECT COUNT(*) FROM $EXPDB.lk_voucher_order_1 WHERE voucher_id=$VID" 2>/dev/null)
[ $((${LOC:-0}+${LOC2:-0})) -ge 1 ] && check_true "P2-2e 订单落在 user_id%2 对应库($EXPDB, uid=$U1)" 0 || check_true "P2-2e 分库路由(uid=$U1 期望 $EXPDB)" 1
R=$(req POST "$BASE/api/seckill-voucher/$VID/token" "$T1")
TOK2=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4)
if [ -n "$TOK2" ]; then
  R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=$TOK2" "$T1")
  check "P2-2f 同一用户重复下单被拒(10005)" 10005 "$(code_of "$R")"
else
  R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=x" "$T1"); echo "      (第二次令牌被限流, 伪令牌探测 code=$(code_of "$R"))"
fi
R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=fake-token-xyz" "$T1")
check "P2-2g 无效令牌被拒(10007)" 10007 "$(code_of "$R")"
echo "      DB stock=$($M -e "SELECT stock FROM localink.lk_seckill_voucher WHERE id=$VID" 2>/dev/null) / Redis stock=$(docker exec localink-redis redis-cli --raw GET "lk:seckill:stock:$VID" 2>/dev/null)"
echo "VID=$VID OID=$OID" >> scripts/verify/last-run.env

echo "================ P2-S3 敏感词（UTF-8 文件体） ================"
T2=$(login_user 13977770002)
printf '{"shopId":1,"title":"[verify] p2 gambling post","content":"\u8d4c\u535a is forbidden"}' > "$J"
R=$(req POST "$BASE/api/post" "$T2" "$J"); check "P2-3a 显性敏感词同步拒发(30001)" 30001 "$(code_of "$R")"
printf '{"shopId":1,"title":"[verify] p2 risk post","content":"\u5237\u5355\u517c\u804c easy money"}' > "$J"
R=$(req POST "$BASE/api/post" "$T2" "$J"); check "P2-3b 隐性风险词先放行(异步复审)" 0 "$(code_of "$R")"
RPID=$($M -e "SELECT id FROM localink.lk_post WHERE title='[verify] p2 risk post' ORDER BY id DESC LIMIT 1" 2>/dev/null)

echo "================ P2-S4 时序修正：先关注后发帖 → Feed 推送 ================"
T3=$(login_user 13977770003)
U2=$($M -e "SELECT id FROM localink.lk_user WHERE phone='13977770002'" 2>/dev/null)
U3=$($M -e "SELECT id FROM localink.lk_user WHERE phone='13977770003'" 2>/dev/null)
req POST "$BASE/api/follow/$U2" "$T1" >/dev/null   # user1 关注 user2（幂等，已关注也无妨）
printf '{"shopId":1,"title":"[verify] p2 feedpush post","content":"feedpush token p2feed998"}' > "$J"
R=$(req POST "$BASE/api/post" "$T2" "$J"); check "P2-4a user2 再发帖(关注之后)" 0 "$(code_of "$R")"
sleep 3
R=$(req GET "$BASE/api/feed" "$T1")
echo "$R" | grep -q 'p2feed998' && check_true "P2-4b user1 Feed 收到新帖(推模式收件箱)" 0 || check_true "P2-4b Feed 推送" 1
# 共同关注：user1 与 user2 都关注 user3
req POST "$BASE/api/follow/$U3" "$T1" >/dev/null; req POST "$BASE/api/follow/$U3" "$T2" >/dev/null
R=$(req GET "$BASE/api/follow/common/$U2" "$T1")
echo "$R" | grep -q "\"id\":\"$U3\"" && check_true "P2-4c 共同关注交集含 user3(id 字符串)" 0 || { echo "      响应: $(head -c 300 <<<"$R")"; check_true "P2-4c 共同关注交集" 1; }

echo "================ P2-S5 隐性词异步复审结果复查 ================"
if [ -n "$RPID" ]; then
  for i in $(seq 1 10); do
    ST=$($M -e "SELECT audit_status FROM localink.lk_post WHERE id=$RPID" 2>/dev/null)
    [ "$ST" = "2" ] && break; sleep 2
  done
  [ "$ST" = "2" ] && check_true "P2-5a 隐性词帖被异步复审驳回(audit_status=2)" 0 || check_true "P2-5a 异步复审驳回(当前 status=$ST)" 1
  R=$(req GET "$BASE/api/post/$RPID")
  C=$(code_of "$R"); [ "$C" = "40004" ] || [ "$C" = "0" ]; echo "      驳回后详情可见性 code=$C"
fi

echo "================ 汇总 ================"
echo "PASS=$PASS FAIL=$FAIL"
