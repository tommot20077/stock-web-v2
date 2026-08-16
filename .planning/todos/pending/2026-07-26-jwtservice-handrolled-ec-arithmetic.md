---
created: 2026-07-26
updated: 2026-08-16
title: JwtService 移除手寫 EC 點運算(程式碼已完成,剩環境遷移)
area: infrastructure
status: code-done-pending-rollout
files:
  - stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java
  - stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwkKeyConverter.java
---

## ⚠️ 2026-08-16 更正:原本的 Solution 建立在一個錯誤前提上

原文說「JCA 沒有公開 API 可以做純量乘法,所以『不自己算』的代價是不能再推導公鑰」,
並據此提議用簽章探針取代推導 + 比對,還標註這是破壞性變更。

**實測後發現:公鑰本來就在私鑰 PEM 裡面,根本不需要推導。**

RFC 5915 的 `ECPrivateKey` 結構有一個 optional 欄位 `publicKey [1] BIT STRING`,
OpenSSL 產生 PKCS#8 EC 私鑰時預設就會填。實測(2026-08-16):

```
$ openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out t.pem
$ openssl asn1parse -in t.pem
   27:d=1  hl=2 l= 109 prim: OCTET STRING  [HEX DUMP]:306B0201010420<32-byte 私鑰>
                                           A14403420004<65-byte 公鑰點>
                                           ^^^^ 這就是內嵌的 publicKey 欄位
```

把 `openssl pkey -in t.pem -pubout` 的輸出與這個內嵌欄位逐位元組比對:**完全相同**。
`pubout` 沒有在計算,它只是把已經存在的欄位讀出來。

`derivePublicKey` 會存在,是因為 **Java 的 `ECPrivateKey` 介面沒把這個欄位暴露出來**
(只有 `getS()` / `getParams()`),`KeyFactory.generatePrivate` 解析完就丟了。
這是 JCA 的 API 缺口,不是資料缺失。前人看到「JCA 給不出公鑰」,結論是「那我自己算」;
正確的結論是「那我自己去讀」。

## 已完成(2026-08-16,分支 `fix/jwt-jwk-key-format`)

採用正規做法:**設定格式改為 EC JWK**,`d` / `x` / `y` 在同一個 JSON 物件裡。

- `JwtService` 的 `parseEcPrivateKey` / `derivePublicKey` / `parsePublicKey` /
  `multiply` / `doublePoint` / `addPoints` / `toNimbusCurve` / `PEM_BLOCK_PATTERN`
  **全部刪除**,改為 `parseJwk`(`JWK.parse` + kty 檢查)。
- 「公私鑰是否配對」的檢查一併消失——只有一個物件,不存在兩塊可能不一致的情形。
- Nimbus 已是既有相依,**零新增套件**。
- `kid` 設定裡有寫就沿用,沒寫才補隨機值(原本每次啟動都換,對外發布 JWKS 時會出事)。
- 新增 `JwkKeyConverter`:一次性 PEM → JWK 轉換工具。簽章探針的正確歸宿是**這裡**
  (一次性轉換時確認兩個檔案來自同一把金鑰),不是啟動路徑。

驗證:`./mvnw test`、`./mvnw -pl stock-start -am verify`(86 IT)、
`./mvnw -pl stock-start -am test -Pe2e`(30) 三者皆綠;轉換工具已用真實 openssl 產生的
PEM 實跑過端到端。

## 剩下的工作:環境遷移(需要 Yuan)

設定格式變了,每個環境的 `STOCK_JWT_PRIVATE_KEY` 都要從 PEM 換成 JWK。
啟動時若偵測到值仍是 PEM,錯誤訊息會直接給出轉換指令,不會靜默失敗。

- [ ] 盤點 dev / demo / 正式環境目前的 `STOCK_JWT_PRIVATE_KEY` 值
- [ ] 逐一轉換:
      ```
      openssl pkey -in private.pem -pubout -out public.pem
      java -cp <app.jar> dowob.xyz.stockwebv2.infrastructure.security.JwkKeyConverter private.pem public.pem
      ```
      **不要用線上轉換器**——那等同把生產環境的簽章私鑰交給第三方。
- [ ] `.env.example:12` 的說明要更新(該檔在本 session 的權限設定下無法讀寫,未改到)
- [ ] 考慮在轉換時順手加上固定的 `kid`(例如 `"kid":"stock-signing-2026"`),
      為日後對外發布 JWKS 預留

不受影響:dev/test/e2e 的 ephemeral key 路徑(`generateKey()`)本來就產完整 keypair。
