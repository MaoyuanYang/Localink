# JMeter 压测资产（M3.2 起）

## 文件

| 文件 | 说明 |
|---|---|
| `seckill-load.jmx` | 参数化秒杀压测计划：每线程从 CSV 取独立 token，POST 下单；断言 = HTTP 200 且业务码 0 |
| `prep-tokens.sh` | 造用户：发码 → Redis 取码 → 登录 → 产出 token CSV（一行一个） |
| `run-load.sh` | 非 GUI 跑一次压测：`run-load.sh <标签> <threads> <rampup> <loops> <voucherId> <tokensCsv>` |
| `results/` | 本地产出（JTL/token CSV），不入库 |

## 前置

- 中间件已起：`docker compose up -d mysql redis`
- 服务已启动（8086，`/ping` → pong）
- JMeter 5.6.3：默认取 `../../../tools/apache-jmeter-5.6.3`，可用环境变量 `JMETER_HOME` 覆盖

## 复现一次实验

```bash
cd scripts/jmeter
./prep-tokens.sh 300 1 "$(pwd)/results/tokens-300.csv"   # 300 个用户
# 建秒杀券（用任一用户 token，登录即可建），记下返回的 voucherId
./run-load.sh exp1 300 0 1 <voucherId> "$(pwd)/results/tokens-300.csv"
```

成败口径：JTL 第 8 列 success；业务码细分（0/10004/10005）以服务端日志 `业务异常: code=` 与 DB 对账为准。
