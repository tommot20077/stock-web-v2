---
created: 2026-07-26
title: JwtService 移除手寫 EC 點運算,改用標準 JCA 簽章探針
area: infrastructure
files:
  - stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java:220-300
---

## Problem

`JwtService` 為了「從私鑰推導公鑰」自行實作了橢圓曲線點運算,約 60 行:

| 方法 | 位置 | 內容 |
|---|---|---|
| `derivePublicKey` | `:220` | `multiply(generator, privateKey.getS(), params)` |
| `multiply` | `:249` | double-and-add 純量乘法 |
| `doublePoint` | `:261` | 體算術倍點,每次 `modInverse` |
| `addPoints` | — | 體算術點加 |

用途有二:私鑰是唯一輸入時推導出公鑰;以及在有提供公鑰時比對兩者是否配對。

## 嚴重度:是程式碼品質問題,不是可利用的漏洞

這點必須說清楚,以免被誤當成緊急安全修補:

- **不在每次請求的簽章路徑上。** 只在 `resolveKey` → `parseEcPrivateKey` → `derivePublicKey`
  執行,即 bean 建構(啟動)時一次。實際簽章由 Nimbus/JCA 完成。
- **沒有攻擊者可控輸入,也沒有可觀測的時序訊號。** 非常數時間的純量乘法在一般情況是
  教科書級的側通道風險,但那需要攻擊者能反覆觸發並觀測時間差;這裡只在開機時跑一次。
- **推導錯誤會大聲失敗。** 若算錯,啟動時的公私鑰比對就不通過,或 JWKS 公布錯誤公鑰導致
  所有驗證失敗——是 fail-loud 而非靜默錯誤。

真正的成本是**維護面**:60 行沒有人想 review 的手寫體算術,而 JDK 已經有標準做法可以
完全避開它。

## Solution

用標準 JCA 的「私鑰簽、公鑰驗」探針取代推導 + 比對:

```java
byte[] probe = "stock-web-v2-jwt-key-probe".getBytes(StandardCharsets.UTF_8);
Signature signer = Signature.getInstance("SHA256withECDSA");
signer.initSign(privateKey);
signer.update(probe);
byte[] signature = signer.sign();

Signature verifier = Signature.getInstance("SHA256withECDSA");
verifier.initVerify(publicKey);
verifier.update(probe);
if (!verifier.verify(signature)) {
    throw new IllegalArgumentException("...public key block must match the private key");
}
```

參考實作在本地 ref `archive/fullstack-review-q5nvfj`(commit `30b2ca2`,原
`claude/fullstack-review-architecture-q5nvfj` 分支,遠端已於 2026-07-26 刪除;
該分支其餘 4/6 commit 的內容都已被 develop 以別的方式達成)。

## ⚠️ 這是破壞性設定變更,需要 Yuan 決定

JCA 沒有公開 API 可以做純量乘法,所以「不自己算」的代價是**不能再推導公鑰**——必須要求
部署方直接提供:

```
openssl pkey -in private.pem -pubout
```

`STOCK_JWT_PRIVATE_KEY` 只給私鑰區塊的環境會在啟動時失敗並要求補上公鑰區塊。
落地前要確認:

- [ ] 現行 dev / demo / 正式環境的 `STOCK_JWT_PRIVATE_KEY` 是否都已含 PUBLIC KEY 區塊
- [ ] `.env.example` 與部署文件同步更新產生指令
- [ ] 開發用的 ephemeral key 路徑(`generateKey()`)不受影響——它本來就有完整 keypair

若不接受這個破壞性變更,替代方案是引入 BouncyCastle 做推導(多一個相依),或維持現狀
並在 JavaDoc 註明「刻意保留,已評估風險」。
