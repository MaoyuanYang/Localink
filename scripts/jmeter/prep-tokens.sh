#!/usr/bin/env bash
# 造压测用户并产出 token CSV：发码 -> Redis 取码 -> 登录 -> 落盘 token
# 用法: ./prep-tokens.sh <数量> <起始序号> <输出CSV>
# 依赖: 服务已启动(8086)、docker 容器 localink-redis 可达；在 Git Bash 下运行
set -euo pipefail

COUNT=${1:?用法: prep-tokens.sh <数量> <起始序号> <输出CSV>}
START=${2:?缺少起始序号}
OUT=${3:?缺少输出CSV路径}

BASE=http://localhost:8086
mkdir -p "$(dirname "$OUT")"
: > "$OUT"

for ((i = START; i < START + COUNT; i++)); do
    phone="138$(printf '%08d' "$i")"
    curl -s -X POST "$BASE/api/sms/code" -H 'Content-Type: application/json' \
        -d "{\"phone\":\"$phone\"}" > /dev/null
    code=$(docker exec localink-redis redis-cli --raw GET "lk:sms:code:$phone")
    if [ -z "$code" ]; then
        echo "FAIL: $phone 验证码读取为空" >&2
        exit 1
    fi
    resp=$(curl -s -X POST "$BASE/api/user/login" -H 'Content-Type: application/json' \
        -d "{\"phone\":\"$phone\",\"code\":\"$code\"}")
    token=$(echo "$resp" | sed -n 's/.*"data":"\([^"]*\)".*/\1/p')
    if [ -z "$token" ]; then
        echo "FAIL: $phone 登录失败: $resp" >&2
        exit 1
    fi
    echo "$token" >> "$OUT"
done

echo "OK: $COUNT 个用户 token 已写入 $OUT"
