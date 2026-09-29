#!/usr/bin/env bash
# 快速造压测用户（绕过发码接口频控）：直接向 Redis 植入验证码 -> 登录 -> token CSV
# 用法: ./prep-tokens-direct.sh <数量> <起始序号> <输出CSV>
# 与 prep-tokens.sh 的区别：跳过 POST /api/sms/code（该接口有发送频控，串行造 400 用户会触发限流），
# 验证码本身就是明文 Redis String（KeyManage.SMS_CODE），植入后走正常登录链路，注册/登录行为完全一致。
# 依赖: 服务已启动(8086)、docker 容器 localink-redis 可达；在 Git Bash 下运行
set -euo pipefail

COUNT=${1:?用法: prep-tokens-direct.sh <数量> <起始序号> <输出CSV>}
START=${2:?缺少起始序号}
OUT=${3:?缺少输出CSV路径}

BASE=http://localhost:8086
mkdir -p "$(dirname "$OUT")"
: > "$OUT"

# 一次管道批量植入全部验证码（CODE 固定 246810，登录 getAndDelete 一次性消费）
{
    for ((i = START; i < START + COUNT; i++)); do
        echo "SET lk:sms:code:138$(printf '%08d' "$i") 246810"
    done
} | docker exec -i localink-redis redis-cli > /dev/null

FAILS=0
for ((i = START; i < START + COUNT; i++)); do
    phone="138$(printf '%08d' "$i")"
    resp=$(curl -s -m 10 -X POST "$BASE/api/user/login" -H 'Content-Type: application/json' \
        -d "{\"phone\":\"$phone\",\"code\":\"246810\"}")
    token=$(echo "$resp" | sed -n 's/.*"data":"\([^"]*\)".*/\1/p')
    if [ -z "$token" ]; then
        echo "FAIL: $phone 登录失败: $resp" >&2
        FAILS=$((FAILS + 1))
        continue
    fi
    echo "$token" >> "$OUT"
done

TOTAL=$(wc -l < "$OUT" | tr -d ' ')
echo "OK: $TOTAL 个用户 token 已写入 $OUT（失败 $FAILS）"
[ "$FAILS" -eq 0 ]
