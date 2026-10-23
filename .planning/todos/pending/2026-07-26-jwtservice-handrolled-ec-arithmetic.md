---
created: 2026-07-26
updated: 2026-10-23
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

**2026-10-23 更新**:Yuan 裁定**換新金鑰**(不沿用舊 PEM;換鑰後既有 token 失效、使用者需重新登入),
demo 由 Claude 產生,正式環境由 Yuan 在本機產生(私鑰不經雲端 session)。

- [x] 新增 `scripts/GenerateJwtJwk.java`(只依賴 JDK,含固定 `kid`)——200 把金鑰驗證 x/y 由 d 推得且簽驗成功,
      並以實際 `JwtService` 在 demo profile 下簽發 / 解析通過
- [x] 修正原本給的轉換指令:`java -cp <app.jar> JwkKeyConverter` 對 fat jar 實跑會 `ClassNotFoundException`;
      `JwtService` 錯誤訊息與 `JwkKeyConverter` javadoc 已改為 `-Dloader.main=… PropertiesLauncher`(實跑通過)
- [x] `.env.example` 的說明已更新
- [x] demo 金鑰已產生(`kid: stock-signing-demo-2026`),交給 Yuan 貼進 demo 環境;**未進 repo**
- [ ] Yuan:把 demo 金鑰設進 demo 環境的 `STOCK_JWT_PRIVATE_KEY`,重啟後確認可登入
- [ ] Yuan:正式環境在本機執行 `java scripts/GenerateJwtJwk.java stock-signing-prod-2026`,
      輸出直接導向 secret store(不要進 shell history / log),重啟後確認可登入
- [ ] 兩者都完成後,本 todo 移到 `completed/`

若日後要**沿用**某把舊 PEM 金鑰(不讓既有 token 失效),用:
```
openssl pkey -in private.pem -pubout -out public.pem
java -cp <app.jar> -Dloader.main=dowob.xyz.stockwebv2.infrastructure.security.JwkKeyConverter \
    org.springframework.boot.loader.launch.PropertiesLauncher private.pem public.pem
```
輸出不含 `kid`,貼進設定前手動補上固定值。**不要用線上轉換器。**

不受影響:dev/test/e2e 的 ephemeral key 路徑(`generateKey()`)本來就產完整 keypair。
