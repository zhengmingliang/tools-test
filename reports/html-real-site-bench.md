# HTML real-site bench (jkit vs Jsoup 1.18.1)

site=https://alianga.com/
size=70.1 KB
os=Linux amd64 java=11.0.32.1
warmup=3 rounds=7 (中位数)

正确性：10 篇 × 5 字段与 Jsoup 逐条一致

```
scenario                       jkit(ms)  jsoup(ms)    jkit快
parse                             0.203      0.381    1.88x
parse + extract all               0.247      0.428    1.74x
select article.post-list-thumb      0.007      0.017    2.25x
select a.post-title h3            0.007      0.017    2.35x
select .post-thumb img            0.011      0.020    1.87x
select .post-date span.i18n       0.014      0.026    1.86x
```
