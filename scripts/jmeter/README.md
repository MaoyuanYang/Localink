# JMeter 压测资产（M3.2 起，M7-A 扩展三场景）

## 文件

| 文件 | 说明 |
|---|---|
| `seckill-load.jmx` | 秒杀下单压测。M7-A 起 queryToken/expectCode 参数化：默认打"无效令牌拒绝"（预期 10007），`expectCode=0`+有效令牌时打成功路径。CSV recycle=true（登录态循环使用，每请求续期） |
| `unauth-load.jmx` | 未登录拒绝路径（40002）：POST 不带 Authorization，纯拦截器开销，零 Redis |
| `shop-cache-load.jmx` | 商户详情缓存命中：GET `/api/shop/{1..shopMax}` 匿名随机轮转，L1 Caffeine → L2 Redis 逻辑过期 |
| `seckill-flow.jmx` | 成功路径两步流：每线程「申请令牌 → 立即下单」，随机 X-Forwarded-For 绕开申请接口 IP 限流（令牌 TTL 30s 必须即申即用） |
| `prep-tokens.sh` | 造用户（走发码接口）：发码 → Redis 取码 → 登录 → token CSV。受 sms-send 限流 1 次/60s/IP 限制，只适合小批量 |
| `prep-tokens-direct.sh` | 快速造用户（M7-A）：管道批量向 Redis 植验证码后走正常登录，绕过发码频控，400 用户约 40s |
| `run-load.sh` | 非 GUI 执行器：`run-load.sh <标签> <jmx名> <threads> <rampup> <loops> [key=value ...]`，key=value 透传为 `-J`；统计样本数/avg/min/p50/p90/p95/p99/max + 成败计数；`html=1` 追加 HTML 报告 |
| `results/` | 本地产出（JTL/token CSV/报告），不入库 |

## 前置

- 中间件已起：`docker compose up -d mysql redis kafka`
- 服务已启动（8086，`/ping` → pong）
- JMeter 5.6.3：默认取 `../../../tools/apache-jmeter-5.6.3`，可用环境变量 `JMETER_HOME` 覆盖
- 1000 线程档建议 `export JVM_ARGS="-Xms1g -Xmx4g"`（JMeter 客户端堆）

## 复现一次实验

```bash
cd scripts/jmeter
./prep-tokens-direct.sh 400 50001 "$(pwd)/results/tokens.csv"    # 400 个用户
# 建秒杀券（登录态 POST /api/seckill-voucher，minLevel 必须为 0，title 用英文防 GBK 乱码），记下 voucherId

# 三场景（60s 时长档）
./run-load.sh r10007 seckill-load 100 5 1000000 voucherId=<vid> expectCode=10007 tokensFile=<csv绝对路径> duration=60
./run-load.sh r40002 unauth-load  300 5 1000000 voucherId=<vid> expectCode=40002 duration=60
./run-load.sh cache   shop-cache-load 100 5 1000000 expectCode=0 duration=60
# 成功路径（每人一单，令牌即申即用）
./run-load.sh flow400 seckill-flow 400 2 1 voucherId=<vid> tokensFile=<csv绝对路径>
```

成败口径：JTL 第 8 列 success；业务码细分（0/10004/10005）以服务端日志 `业务异常: code=` 与 DB 对账为准。
注意：响应消息含逗号的失败样本（如 Connection refused）会错列，精确统计用 `grep "<sampler名>" xxx.jtl | awk -F',' '{...}'` 单独算。

## 已知坑（M7-A 实录）

- **JMeter `RegexExtractor` 属性名是 `refname`（全小写）**：写成 `refName` 时属性被静默忽略，提取结果落到空键名变量上，下游 `${var}` 不替换、URL 带字面量——用 JSR223 探针打印 `vars` 定位。
- **path 属性里混用 `__P` 函数与运行时变量**不可靠：跨 sampler 传 query 参数用 HTTP Arguments 参数表（`Argument.value=${var}`）。
- 400 并发 rampup≤2s 的突发会打满 Tomcat 默认 accept-count=100 出现 Connection refused；Hikari 池打满时 3s 超时抛 `SQLTransientConnectionException`（服务端 500，JTL 侧看到的是断言失败）。
