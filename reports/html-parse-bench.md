# HTML parse bench (jkit vs Jsoup 1.18.1)

warmup=3 rounds=7 (中位数)
os=Linux amd64 java=11.0.32.1 maxHeap=2048 MB

倍数 = jsoup / jkit，>1 表示 jkit 更快

## 解析

```
scenario                   jkit(ms)  jsoup(ms)    jkit快
小页面 2 条 (1.6 KB)              0.012      0.023    1.92x
中页面 60 条 (30 KB)              0.134      0.275    2.06x
大页面 600 条 (300 KB)            1.307      2.638    2.02x
```

## 解析 + text()

```
scenario                   jkit(ms)  jsoup(ms)    jkit快
小页面 2 条 (1.6 KB)              0.012      0.023    2.00x
中页面 60 条 (30 KB)              0.170      0.297    1.75x
大页面 600 条 (300 KB)            1.711      2.865    1.67x
```

## 选择器（中页面 60 条，预解析后重复查询）

```
selector                   jkit(ms)  jsoup(ms)    jkit快
#main                         0.007      0.012    1.62x
.post                         0.009      0.014    1.55x
article.post h2               0.009      0.019    2.04x
main > article                0.007      0.013    1.76x
a[href^=/p/]                  0.009      0.016    1.82x
li:first-child                0.009      0.015    1.60x
h2 + p                        0.008      0.016    2.06x
p b                           0.008      0.017    2.17x
```

## 选择器（大页面 600 条）

```
selector                   jkit(ms)  jsoup(ms)    jkit快
#main                         0.110      0.141    1.28x
.post                         0.132      0.177    1.34x
article.post h2               0.109      0.194    1.77x
a[href^=/p/]                  0.105      0.165    1.57x
```

## 常驻内存（150 份大页面 DOM）

```
jkit=202.1 MB  jsoup=179.7 MB  jkit/jsoup=0.89x
```

## 端到端（大页面：解析 + 3 次查询 + 取文本）

```
jkit=   2.341 ms  jsoup=   4.715 ms  jkit快= 2.01x
```
