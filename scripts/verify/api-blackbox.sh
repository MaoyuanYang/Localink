#!/usr/bin/env bash
# ============================================================================
# Localink 验证专用黑盒脚本（project-verify / 阶段5；T2 全面修绿：断言写法、UTF-8 文件体、时序）
# 用途：对运行中的服务(8086)按文档承诺逐条演练关键用户流。
# 前置：中间件在线（mysql/redis/kafka/es），应用已启动且 /ping 通。
# 产出：PASS/FAIL 逐条输出 + 末尾汇总。本脚本不修改业务代码。
# 说明：创建的测试数据手机号前缀 1397777，帖子标题前缀 [verify]，收尾由
#       verify-cleanup.sql 处理（秒杀订单除外——留给 15m 超时关单生命周期验证）。
# ============================================================================
set -u
BASE=http://localhost:8086
MYSQL="docker exec localink-mysql mysql -uroot -plocalink123 --default-character-set=utf8mb4 -N"
PASS=0; FAIL=0
declare -a FAILED=()

check() { # $1=用例名 $2=期望值 $3=实际值
  if [ "$2" = "$3" ]; then
    PASS=$((PASS+1)); printf 'PASS  %-46s (code=%s)\n' "$1" "$3"
  else
    FAIL=$((FAIL+1)); printf 'FAIL  %-46s expected=%s actual=%s\n' "$1" "$2" "$3"; FAILED+=("$1")
  fi
}
check_true() { # $1=用例名 $2=条件表达式结果(0成功)
  if [ "$2" -eq 0 ]; then PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"
  else FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; FAILED+=("$1"); fi
}
JF=/tmp/verify-body.json
code_of() { grep -o '"code":-\?[0-9]*' <<<"$1" | head -1 | cut -d: -f2; }
sms_code() { docker exec localink-redis redis-cli --raw GET "lk:sms:code:$1" | tr -d '\r\n'; }
now_min() { date "+%Y-%m-%d %H:%M:%S" -d "$1 min"; }
q() { $MYSQL -e "$1" 2>/dev/null; }

req() { # method url [token] [bodyfile]（T2：一律 UTF-8 文件体，避开终端编码）
  local m=$1 u=$2 t=${3:-} b=${4:-}
  if [ -n "$t" ] && [ -n "$b" ]; then curl -s -m 10 -X "$m" -H "Authorization: $t" -H 'Content-Type: application/json' --data-binary @"$b" "$u"
  elif [ -n "$t" ]; then curl -s -m 10 -X "$m" -H "Authorization: $t" "$u"
  elif [ -n "$b" ]; then curl -s -m 10 -X "$m" -H 'Content-Type: application/json' --data-binary @"$b" "$u"
  else curl -s -m 10 -X "$m" "$u"; fi
}
login_user() { # $1=phone -> echoes token（随机 XFF 绕开发码接口的 IP 维度限流，与 seckill-flow.jmx 同口径）
  local r c tok
  curl -s -m 10 -X POST -H "X-Forwarded-For: 10.$RANDOM.$RANDOM.$RANDOM" -H 'Content-Type: application/json' -d '{"phone":"'$1'"}' "$BASE/api/sms/code" >/dev/null
  sleep 1
  local code=$(sms_code "$1")
  printf '{"phone":"%s","code":"%s"}' "$1" "$code" > "$JF"
  r=$(req POST "$BASE/api/user/login" "" "$JF")
  tok=$(grep -o '"data":"[^"]*"' <<<"$r" | head -1 | cut -d'"' -f4)
  echo "$tok"
}

echo "================ S0 探活 ================"
P=$(curl -s -m 5 "$BASE/ping"); echo "ping -> $P"
[ "$P" = "pong" ] && check_true "S0 /ping 返回 pong" 0 || check_true "S0 /ping 返回 pong" 1

echo "================ S1 账户链路（M1） ================"
P1=13977770001
printf '{"phone":"%s"}' "$P1" > "$JF"
R=$(req POST "$BASE/api/sms/code" "" "$JF"); check "S1a 发送验证码" 0 "$(code_of "$R")"
C=$(sms_code "$P1"); [ -n "$C" ] && check_true "S1c 验证码已写入 Redis(明文 String)" 0 || check_true "S1c 验证码已写入 Redis(明文 String)" 1
printf '{"phone":"%s","code":"%s"}' "$P1" "$C" > "$JF"
R=$(req POST "$BASE/api/user/login" "" "$JF")
check "S1d 正确验证码登录成功" 0 "$(code_of "$R")"
T1=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4)
[ -n "$T1" ] && check_true "S1e 拿到 token" 0 || check_true "S1e 拿到 token" 1
R=$(req GET "$BASE/api/user/me"); check "S1f 无 token 访问 /me 被拒(40002)" 40002 "$(code_of "$R")"
R=$(req GET "$BASE/api/user/me" "$T1"); check "S1g 带 token 访问 /me" 0 "$(code_of "$R")"
echo "$T1" | grep -q "$P1" || true

echo "================ S2 短信频控（M3 流量防线） ================"
printf '{"phone":"%s"}' "$P1" > "$JF"
R=$(req POST "$BASE/api/sms/code" "" "$JF")
C2=$(code_of "$R")
check "S2a 60s 内重发验证码被限流(40008 IP 维度)" 40008 "$C2"

echo "================ S3 商户与缓存（M2） ================"
R=$(req GET "$BASE/api/shop/1"); check "S3a 商户详情(冷/L2)" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/shop/1"); check "S3b 商户详情(热/L1 Caffeine)" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/shop/424242"); C3=$(code_of "$R")
check "S3c 不存在的商户返回 40004" 40004 "$C3"
R=$(req GET "$BASE/api/shop/nearby?longitude=116.397&latitude=39.909&radius=5000&count=5")
check "S3d GEO 附近商户(M6-F)" 0 "$(code_of "$R")"
# 缓存失效：自建店铺上改名后立查应为新值（旁路缓存先 DB 后删缓存；T2 起不再触碰种子店铺）
printf '{"name":"[verify]blackbox-shop","typeId":1,"address":"verify road","avgPrice":100,"longitude":120.163,"latitude":30.274}' > "$JF"
R=$(req POST "$BASE/api/shop" "$T1" "$JF"); check "S3e 自建商户" 0 "$(code_of "$R")"
SID=$(q "SELECT id FROM localink.lk_voucher WHERE title='__none__' LIMIT 1; SELECT id FROM localink.lk_shop WHERE name='[verify]blackbox-shop' ORDER BY id DESC LIMIT 1")
printf '{"id":%s,"name":"[verify]blackbox-shop-renamed","typeId":1,"address":"verify road","avgPrice":100,"longitude":120.163,"latitude":30.274}' "$SID" > "$JF"
R=$(req PUT "$BASE/api/shop" "$T1" "$JF"); check "S3e2 更新商户名" 0 "$(code_of "$R")"
sleep 1
R=$(req GET "$BASE/api/shop/$SID")
echo "$R" | grep -q 'blackbox-shop-renamed' && check_true "S3f 改名后读到新值(缓存已失效)" 0 || check_true "S3f 改名后读到新值(缓存已失效)" 1
req DELETE "$BASE/api/shop/$SID" "$T1" >/dev/null

echo "================ S4 秒杀两步流（M3/M4） ================"
BT=$(now_min -2); ET=$(now_min 120)
printf '{"shopId":1,"title":"[verify]blackbox-voucher","payValue":100,"actualValue":200,"stock":5,"minLevel":0,"beginTime":"%s","endTime":"%s"}' "$BT" "$ET" > "$JF"
R=$(req POST "$BASE/api/seckill-voucher" "$T1" "$JF")
check "S4a 创建秒杀券(stock=5)" 0 "$(code_of "$R")"
VID=$(q "SELECT id FROM localink.lk_voucher WHERE title='[verify]blackbox-voucher' ORDER BY id DESC LIMIT 1")
echo "      voucherId=$VID"
R=$(req POST "$BASE/api/seckill-voucher/$VID/token" "$T1"); check "S4b 申请令牌" 0 "$(code_of "$R")"
TOK=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4)
R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=$TOK" "$T1"); check "S4c 令牌下单(异步)" 0 "$(code_of "$R")"
OID=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4); echo "      orderId=$OID"
sleep 4
N=$(q "SELECT COUNT(*) FROM localink.lk_voucher_order_0 WHERE voucher_id=$VID UNION ALL SELECT COUNT(*) FROM localink.lk_voucher_order_1 WHERE voucher_id=$VID" | awk '{s+=$1} END {print s}')
[ "${N:-0}" -ge 1 ] && check_true "S4d 订单已由 Kafka 异步落库(双库物理表)" 0 || check_true "S4d 订单已由 Kafka 异步落库(双库物理表)" 1
R=$(req POST "$BASE/api/seckill-voucher/$VID/token" "$T1")
TOK2=$(grep -o '"data":"[^"]*"' <<<"$R" | head -1 | cut -d'"' -f4)
if [ -n "$TOK2" ]; then
  R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=$TOK2" "$T1")
  check "S4e 同一用户重复下单被拒(10005 一人一单)" 10005 "$(code_of "$R")"
else
  R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=whatever" "$T1")
  C=$(code_of "$R"); if [ "$C" = "10005" ]; then check "S4e 同一用户重复下单被拒(10005 一人一单)" 10005 "$C"; else check "S4e 同一用户重复下单被拒(10005 一人一单)" 10005 "$C (令牌被限流后以伪令牌探测)"; fi
fi
R=$(req POST "$BASE/api/seckill-voucher/$VID/seckill?token=fake-token-xyz" "$T1")
check "S4f 无效令牌被拒(10007)" 10007 "$(code_of "$R")"
STK=$(q "SELECT stock FROM localink.lk_seckill_voucher WHERE id=$VID")
echo "      DB stock now=$STK (创建时 5)"

echo "================ S5 社区链路（M6） ================"
T2=$(login_user 13977770002); T3=$(login_user 13977770003)
[ -n "$T2" ] && [ -n "$T3" ] && check_true "S5a 用户2/3 注册登录" 0 || check_true "S5a 用户2/3 注册登录" 1
U1=$(q "SELECT id FROM localink.lk_user WHERE phone='$P1'"); U2=$(q "SELECT id FROM localink.lk_user WHERE phone='13977770002'"); U3=$(q "SELECT id FROM localink.lk_user WHERE phone='13977770003'")
R=$(req POST "$BASE/api/follow/$U2" "$T1"); check "S5a2 用户1 关注用户2(发帖前,保证Feed推送时序)" 0 "$(code_of "$R")"
printf '{"shopId":1,"title":"[verify] post about noodle shop","content":"the noodle is great, unique token verifood998"}' > "$JF"
R=$(req POST "$BASE/api/post" "$T2" "$JF")
check "S5b 用户2 发帖" 0 "$(code_of "$R")"
PID=$(q "SELECT id FROM localink.lk_post WHERE title='[verify] post about noodle shop' ORDER BY id DESC LIMIT 1")
printf '{"shopId":1,"title":"[verify] gambling is bad","content":"\u8d4c\u535a is forbidden"}' > "$JF"
R=$(req POST "$BASE/api/post" "$T2" "$JF")
check "S5c 显性敏感词同步拒发(30001)" 30001 "$(code_of "$R")"
printf '{"shopId":1,"title":"[verify] risk parttime","content":"\u5237\u5355\u517c\u804c easy money"}' > "$JF"
R=$(req POST "$BASE/api/post" "$T2" "$JF")
check "S5d 隐性风险词先放行(异步复审)" 0 "$(code_of "$R")"
RPID=$(q "SELECT id FROM localink.lk_post WHERE title='[verify] risk parttime' ORDER BY id DESC LIMIT 1")
printf '{"postId":%s,"content":"nice one"}' "$PID" > "$JF"
R=$(req POST "$BASE/api/post/comment" "$T1" "$JF")
check "S5e 用户1 评论" 0 "$(code_of "$R")"
CID=$(q "SELECT id FROM localink.lk_post_comment WHERE post_id=$PID AND content='nice one' ORDER BY id DESC LIMIT 1")
printf '{"postId":%s,"content":"reply here","parentId":%s}' "$PID" "$CID" > "$JF"
R=$(req POST "$BASE/api/post/comment" "$T3" "$JF")
check "S5f 楼中楼回复(parentId)" 0 "$(code_of "$R")"
R=$(req POST "$BASE/api/post/$PID/like" "$T3"); check "S5g 点赞" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/post/$PID"); check "S5h 帖子详情" 0 "$(code_of "$R")"
R=$(req DELETE "$BASE/api/post/$PID/like" "$T3"); check "S5i 取消点赞" 0 "$(code_of "$R")"
R=$(req POST "$BASE/api/follow/$U2" "$T1"); check "S5j 重复关注幂等(首次已在S5a2完成)" 0 "$(code_of "$R")"
req POST "$BASE/api/follow/$U3" "$T1" >/dev/null; req POST "$BASE/api/follow/$U3" "$T2" >/dev/null
R=$(req GET "$BASE/api/follow/common/$U2" "$T1"); check "S5k 共同关注(交集含用户3)" 0 "$(code_of "$R")"
echo "$R" | grep -q "\"userId\":\"$U3\"" && check_true "S5k2 交集结果包含用户3(id字符串)" 0 || check_true "S5k2 交集结果包含用户3" 1
R=$(req GET "$BASE/api/feed" "$T1"); check "S5l Feed 流(推模式收件箱)" 0 "$(code_of "$R")"
echo "$R" | grep -q "noodle shop" && check_true "S5l2 Feed 含被关注者新帖" 0 || check_true "S5l2 Feed 含被关注者新帖" 1
# ES 搜索：等 Kafka→ES 同步（重试 30s）
FOUND=0
for i in $(seq 1 15); do
  R=$(req GET "$BASE/api/search/post?keyword=verifood998")
  if echo "$R" | grep -q '"code":0' && echo "$R" | grep -q verifood998; then FOUND=1; break; fi
  sleep 2
done
[ "$FOUND" = "1" ] && check_true "S5m ES 搜索命中(Kafka 异步同步, ≤30s)" 0 || check_true "S5m ES 搜索命中(Kafka 异步同步, ≤30s)" 1
# XSS：高亮字段应转义
printf '{"shopId":1,"title":"[verify] xsschkk<script>alert(1)</script>t","content":"xssbody<script>evil()</script>check"}' > "$JF"
req POST "$BASE/api/post" "$T2" "$JF" >/dev/null
sleep 6
R=$(req GET "$BASE/api/search/post?keyword=xsschkk")
if echo "$R" | grep -q '<script>'; then check_true "S5n 搜索高亮 XSS 转义" 1; else check_true "S5n 搜索高亮 XSS 转义(无裸<script>)" 0; fi
R=$(req GET "$BASE/api/post/hot?limit=5"); check "S5o 热榜接口(M6-E)" 0 "$(code_of "$R")"
R=$(req POST "$BASE/api/user/sign" "$T1"); check "S5p BitMap 签到(M6-F)" 0 "$(code_of "$R")"
R=$(req GET "$BASE/api/user/sign" "$T1"); check "S5q 签到状态查询" 0 "$(code_of "$R")"

echo "================ S6 越权与边界 ================"
R=$(req DELETE "$BASE/api/post/$PID" "$T3")
check "S6a 非作者删帖被拒(40003)" 40003 "$(code_of "$R")"
R=$(req DELETE "$BASE/api/post/$PID" "$T2"); check "S6b 作者删帖" 0 "$(code_of "$R")"

echo "================ S7 异步复核观察（隐性词驳回归档） ================"
if [ -n "$RPID" ]; then
  ST=$(q "SELECT status FROM localink.lk_post WHERE id=$RPID" 2>/dev/null)
  echo "      隐性词帖子 status=$ST (等待异步复审消费者处理, 由后续步骤复查)"
fi

echo "================ S8 验证码一次性消费语义（错码尝试烧码） ================"
T8=$(login_user 13977770088)
if [ -n "$T8" ]; then
  echo "      (13977770088 已登录，改用 13977770077 做错码实验)"
fi
curl -s -m 10 -X POST -H "X-Forwarded-For: 10.9.9.9" -H 'Content-Type: application/json' -d '{"phone":"13977770077"}' "$BASE/api/sms/code" >/dev/null
sleep 1
REAL77=$(sms_code 13977770077)
printf '{"phone":"13977770077","code":"000000"}' > "$JF"
R=$(req POST "$BASE/api/user/login" "" "$JF")
C8=$(code_of "$R"); echo "      错码登录返回 code=$C8"
[ "$C8" != "0" ] && [ -n "$C8" ] && check_true "S8a 错误验证码登录被拒" 0 || check_true "S8a 错误验证码登录被拒" 1
LEFT=$(sms_code 13977770077)
[ -z "$LEFT" ] && check_true "S8b 错码尝试已烧码(GETDEL 一次性消费, Redis 中已无码)" 0 || check_true "S8b 错码尝试已烧码(码仍在: $LEFT)" 1
printf '{"phone":"13977770077","code":"%s"}' "$REAL77" > "$JF"
R=$(req POST "$BASE/api/user/login" "" "$JF")
C8b=$(code_of "$R"); echo "      烧码后再用真码登录返回 code=$C8b"
[ "$C8b" != "0" ] && [ -n "$C8b" ] && check_true "S8c 烧码后真码不可再用(防爆破)" 0 || check_true "S8c 烧码后真码不可再用" 1

echo "================ 汇总 ================"
echo "PASS=$PASS FAIL=$FAIL"
if [ ${#FAILED[@]} -gt 0 ]; then printf '失败用例: %s\n' "${FAILED[*]}"; fi
echo "VID=$VID OID=$OID U1=$U1 U2=$U2 U3=$U3 PID=$PID RPID=$RPID" > scripts/verify/last-run.env
echo "上下文已存 scripts/verify/last-run.env (供关单/复核复查用)"
