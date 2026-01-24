# RPPacks (Rascraft Plugin Packs)

RPPacksは、サーバー内での農業を支援する「スニーク成長」と、移動を演出する「足あと軌跡」の2つの主要機能を備えた、RAScraftオリジナルのプラグインです。

## 主要機能

1. スニーク成長 (Sneak Grow)
   作物の近くでスニーク（しゃがむ）することで、骨粉のような効果を与え、成長を促進させます。

- 対象: 小麦、サトウキビ、苗木、コケ、竹など。
- カスタマイズ: 設定により成功確率や影響範囲（半径）を変更可能です。

2. 足あと軌跡 (Footprint Trail)
   プレイヤーが歩いた場所にパーティクルエフェクトを表示します。

- GUIメニュー: /rppacks trail で専用メニューを開き、エフェクトを選択できます。
- ランク制限: 一般用およびVIP限定のエフェクトを設定可能です。

## コマンドリファレンス

- /rppacks
  説明: プラグインのバージョンおよびシステム情報を表示します。
  権限: 全プレイヤー

- /rppacks help
  説明: 利用可能なコマンドのヘルプ一覧を表示します。
  権限: 全プレイヤー

- /rppacks trail
  説明: 足あとエフェクト設定UIを開きます。
  権限: rppacks.trail

- /rppacks status
  説明: 現在の稼働設定（確率、半径、読み込み数）を表示します。
  権限: rppacks.admin

- /rppacks sneakgrow <chance|radius> <数値>
  説明: スニーク成長の成功率(0.0-1.0)や影響半径を動的に変更します。
  権限: rppacks.admin

- /rppacks reload
  説明: config.ymlの設定を再読み込みします。
  権限: rppacks.admin

## 権限設定 (Permissions)

- rppacks.trail
  説明: 足あとメニューを開く権限。デフォルトで全プレイヤーに付与。

- rascraft.sneakgrow
  説明: スニーク成長機能を利用できる権限。デフォルトで全プレイヤーに付与。

- rppacks.vip
  説明: メニュー内のVIP限定エフェクトを装備できる権限。デフォルトでOPのみ。

- rppacks.admin
  説明: リロードや設定変更などの管理者用コマンドにアクセスできる権限。デフォルトでOPのみ。

## 導入と設定

1. RPPacks.jarをサーバーのpluginsフォルダに配置。
2. サーバーを起動し、自動生成されるconfig.ymlで詳細な設定（対象ブロックやエフェクト密度など）を調整。
3. /rppacks reloadで設定を反映。

---
Developed by Kyamy for RAScraft