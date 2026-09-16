#!/usr/bin/env bash
# 以非 GUI 模式跑一次压测并输出成败统计
# 用法: run-load.sh <标签> <threads> <rampup> <loops> <voucherId> <tokensCsv绝对路径>
# 结果: results/<标签>.jtl；stdout 末尾附 success/fail 计数
# JMETER_HOME 可环境变量覆盖，默认使用本仓库旁 tools 下的安装
set -euo pipefail

TAG=${1:?用法: run-load.sh <标签> <threads> <rampup> <loops> <voucherId> <tokensCsv>}
THREADS=${2:?缺少 threads}
RAMP=${3:?缺少 rampup}
LOOPS=${4:?缺少 loops}
VID=${5:?缺少 voucherId}
TOKENS=${6:?缺少 tokensCsv}

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RESULTS="$SCRIPT_DIR/results"
mkdir -p "$RESULTS"
JTL="$RESULTS/$TAG.jtl"
rm -f "$JTL"

JMETER_HOME=${JMETER_HOME:-"$SCRIPT_DIR/../../../tools/apache-jmeter-5.6.3"}
JMETER="$JMETER_HOME/bin/jmeter.bat"

"$JMETER" -n -t "$SCRIPT_DIR/seckill-load.jmx" \
    -Jthreads="$THREADS" -Jrampup="$RAMP" -Jloops="$LOOPS" \
    -JvoucherId="$VID" -JtokensFile="$TOKENS" \
    -l "$JTL" 2>&1 | grep -E "^summary|rror" || true

echo "=== $TAG 成败统计（JTL） ==="
awk -F',' 'NR>1 {c[$8]++} END {for (k in c) print k, c[k]}' "$JTL"
