# HTML parse bench (jkit vs Jsoup 1.18.1)

warmup=3 rounds=7 (中位数)
os=Linux amd64 java=11.0.32.1 maxHeap=2048 MB

倍数 = jsoup / jkit，>1 表示 jkit 更快

## 解析

```
scenario                   jkit(ms)  jsoup(ms)    jkit快
小页面 2 条 (1.6 KB)              0.011      0.021    1.94x
中页面 60 条 (30 KB)              0.128      0.261    2.03x
大页面 600 条 (300 KB)            1.204      2.539    2.11x
```

## 解析 + text()

```
scenario                   jkit(ms)  jsoup(ms)    jkit快
小页面 2 条 (1.6 KB)              0.011      0.022    2.01x
中页面 60 条 (30 KB)              0.160      0.278    1.73x
大页面 600 条 (300 KB)            1.638      2.750    1.68x
```

## 选择器（中页面 60 条，预解析后重复查询）

```
selector                   jkit(ms)  jsoup(ms)    jkit快
#main                         0.000      0.012   79.90x
.post                         0.001      0.014   10.42x
article.post h2               0.002      0.020    8.29x
main > article                0.002      0.013    8.11x
a[href^=/p/]                  0.002      0.016    9.42x
li:first-child                0.003      0.015    4.57x
h2 + p                        0.001      0.017   11.79x
p b                           0.001      0.017   14.02x
```

## 选择器（大页面 600 条）

```
selector                   jkit(ms)  jsoup(ms)    jkit快
#main                         0.000      0.146  748.82x
.post                         0.015      0.182   12.38x
article.post h2               0.023      0.202    8.61x
a[href^=/p/]                  0.016      0.173   10.70x
```

## 常驻内存（150 份大页面 DOM）

```
只解析              jkit=  202.6 MB  jsoup=  179.7 MB  jkit/jsoup=1.13x
解析+一次查询          jkit=  209.1 MB  jsoup=  181.0 MB  jkit/jsoup=1.16x
```

## 端到端（大页面：解析 + 3 次查询 + 取文本）

```
jkit=   3.397 ms  jsoup=   6.428 ms  jkit快= 1.89x
```
