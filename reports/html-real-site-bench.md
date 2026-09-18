# HTML real-site bench (jkit vs Jsoup 1.18.1)

site=https://alianga.com/
size=70.1 KB
os=Linux amd64 java=11.0.32.1
warmup=3 rounds=7 (中位数)

正确性：10 篇 × 5 字段与 Jsoup 逐条一致

```
scenario                       jkit(ms)  jsoup(ms)    jkit快
parse                             0.200      0.347    1.74x
parse + extract all               0.268      0.462    1.73x
select article.post-list-thumb      0.000      0.015   33.94x
select a.post-title h3            0.000      0.016   37.14x
select .post-thumb img            0.003      0.020    5.85x
select .post-date span.i18n       0.006      0.026    4.50x
```
