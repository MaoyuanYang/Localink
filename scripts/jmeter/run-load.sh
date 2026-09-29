#!/usr/bin/env bash
# 以非 GUI 模式跑一次压测并输出成败与 RT 分位统计
# 用法: run-load.sh <标签> <jmx名(不含.jmx)> <threads> <rampup> <loops> [key=value ...]
#   key=value 会透传为 -Jkey=value（如 voucherId=123 expectCode=10007 tokensFile=...）
# 结果: results/<标签>.jtl + stdout 统计；附加 html=1 时再产出 results/<标签>-html/ 报告
# JMETER_HOME 可环境变量覆盖，默认使用本仓库旁 tools 下的安装
set -euo pipefail

TAG=${1:?用法: run-load.sh <标签> <jmx名> <threads> <rampup> <loops> [key=value ...]}
JMX=${2:?缺少 jmx 名（不含 .jmx，如 seckill-load / unauth-load / shop-cache-load / seckill-flow）}
THREADS=${3:?缺少 threads}
RAMP=${4:?缺少 rampup}
LOOPS=${5:?缺少 loops}
shift 5

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RESULTS="$SCRIPT_DIR/results"
mkdir -p "$RESULTS"
JTL="$RESULTS/$TAG.jtl"
rm -rf "$JTL" "$RESULTS/$TAG-html"

JMETER_HOME=${JMETER_HOME:-"$SCRIPT_DIR/../../../tools/apache-jmeter-5.6.3"}
JMETER="$JMETER_HOME/bin/jmeter.bat"

EXTRA=()
HTML_ARGS=()
for kv in "$@"; do
    key=${kv%%=*}
    val=${kv#*=}
    if [ "$key" = "html" ] && [ "$val" = "1" ]; then
        HTML_ARGS=(-e -o "$RESULTS/$TAG-html")
    else
        EXTRA+=("-J$key=$val")
    fi
done

"$JMETER" -n -t "$SCRIPT_DIR/$JMX.jmx" \
    -Jthreads="$THREADS" -Jrampup="$RAMP" -Jloops="$LOOPS" \
    "${EXTRA[@]}" "${HTML_ARGS[@]}" \
    -l "$JTL" 2>&1 | grep -E "^summary|rror" || true

echo "=== $TAG 统计（$JMX threads=$THREADS rampup=$RAMP loops=$LOOPS ${*}） ==="
awk -F',' '
function pct(a, n, p,   i) { i = int((n * p + 99) / 100); return (i > n) ? a[n] : a[i] }
NR>1 {n++; rt[n]=$2+0; ok[$8]++; sum+=$2}
END {
    asort(rt)
    printf "samples=%d  avg=%.1fms  min=%d  p50=%d  p90=%d  p95=%d  p99=%d  max=%d\n", \
        n, sum/n, rt[1], pct(rt,n,50), pct(rt,n,90), pct(rt,n,95), pct(rt,n,99), rt[n]
    for (k in ok) printf "success=%s: %d\n", k, ok[k]
}' "$JTL"
