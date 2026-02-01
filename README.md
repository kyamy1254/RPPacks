# RPPacks (Rascraft Plugin Packs)

RPPacks は RAScraft 向けに作られた多機能プラグインです。プレイヤー向けの演出機能（足あとエフェクト / スニーク成長 / テレポート演出）と、エンティティ強化・表示系システム（Enchanted Mobs / Entity Health System）を提供します。

---

## 目次
- 概要
- 主要機能（詳細）
  - Sneak Grow（スニーク成長）
  - Footprint Trail（足あとトレイル）
  - Teleport Effect（テレポート演出）
  - Enchanted Mobs（エンチャントモブ）
  - Entity Health System（ダメージ/回復インジケーター & HPゲージ）
- コマンド一覧
- 権限（Permissions）
- 主な設定項目（config.yml の抜粋と説明）
- リソースファイル
- 導入手順
- よくある質問 / トラブルシュート
- 開発者 / ライセンス

---

## 概要
RPPacks は以下のカテゴリの機能を一つのプラグインでまとめて提供します。

- プレイヤー体験を向上させる軽量な演出（足あとパーティクル、テレポート時の粒子など）
- 便利系のユーティリティ（スニークでの作物成長補助）
- 高度なモブ拡張（Enchanted Mobs）：既存モブに強力なAI・装備・演出を付与
- 戦闘時の視覚情報（Entity Health System）：ダメージ/回復のインジケーター、ターゲットのHPをアクションバーに表示

設計方針は「サーバー側で容易にカスタマイズできること」と「過度に負荷を上げないこと」です。多くの挙動は config.yml から有効/無効やパラメータを設定できます。

---

## 主要機能（詳細）

### Sneak Grow（スニーク成長）
- 概要: プレイヤーがスニーク（しゃがむ）したとき、周囲の指定したブロック（作物など）に骨粉や段階的な成長・スタック成長を適用する機能。
- 設定: 有効/無効、成功確率、影響半径、対象ブロックごとの成長方式（AGEABLE / STACK / BONEMEAL）
- 使用: 権限 `rascraft.sneakgrow` を持つプレイヤーが使用可能（デフォルトは一般プレイヤーに許可）。
- 設定例: `sneak-grow.success-chance: 0.8`, `sneak-grow.radius: 1` など

実装ノート: `SneakListener` が実際の成長処理を行い、Ageable ブロックは段階を、STACK は上に伸ばす、BONEMEAL は骨粉適用を行います。

---

### Footprint Trail（足あとトレイル）
- 概要: プレイヤー移動時に足元にパーティクルを発生させる。メニュー（GUI）でエフェクトを選択できる。
- GUI: `/rppacks trail`（プレイヤーは `rppacks.trail` 権限が必要）で 54 スロットの GUI を開き、エフェクトの選択・解除・ページ移動が可能。
- エフェクト: 一般用（allowed-effects）と VIP 用（vip-effects）を分離。VIP は `rppacks.vip` 権限が必要。
- カスタム色: `footprint-trail.colored-effects` で色付き(DUST)や音階(NOTE)等の特殊エフェクトを登録可能。
- 間隔 / 密度: `footprint-trail.interval`（移動間隔）、`footprint-trail.density`（密度、個別設定可能）

実装ノート: `TrailListener` が移動イベントを監視して線形補間でパーティクルを生成。エリトラ飛行中は翼状に表示する特殊処理あり。

---

### Teleport Effect（テレポート演出）
- 概要: プラグインやコマンドによるテレポート（PlayerTeleportEvent の CAUSE が COMMAND / PLUGIN）の際に、ワープ元・ワープ先で粒子エフェクトを表示。
- 設定: `teleport-effect.enabled`, `delay-ticks`, `from-particle`, `to-particle` を調整可能。
- 注意: エンドパールやネザーゲート等のテレポートは対象外（CAUSE の判定により除外）。

実装ノート: `TeleportListener` が遅延タスクで到着時演出を実行します。

---

### Enchanted Mobs（エンチャントモブ）
- 概要: 通常のモンスターに対して「Enchanted」化することで、HP増加、AI強化、専用装備、オーラパーティクル、種族別特殊行動を付与します。
- 有効化: `enchanted-mobs.enabled`（config）およびコマンド `/rppacks enchantedmobs <enable|disable>` により切替可能。
- カスタマイズ: スポーン確率、HP倍率、移動速度、各種 AI パラメータ（索敵範囲・クールダウン等）を細かく設定可能。
- 安全策: 一時的に設置するブロック（苔石など）を管理して、プラグイン無効時に掃除するロジックを実装。

実装ノート: `EnchantedMobSystem` がほとんどのロジックを担います。スポーン時に確率で Enchanted 化し、種族に応じた AI タスクをスケジューリングします。増援スポーンやホーミング弾など多数の挙動が含まれます。

具体的な強化内容（種族別）:

- 共通強化（Enchanted 共通）
  - HP 倍率の増加（`stats.hp-multiplier`）および移動速度の調整
  - カスタムネーム（例: "Enchanted ZOMBIE"）と非表示/表示切替
  - 常時オーラパーティクル（種族に応じた粒子表現／螺旋・色付き）
  - アイテム拾得を有効化（装備を拾って強化する挙動）
  - スケジューラでの AI タスク（ナビゲーション、視覚効果、種族特有のタスク）を登録

- ゾンビ系（Zombie / Husk / PigZombie）
  - 指揮官オーラ: 周囲のアンデッドにバフ（移動速度/攻撃力）を付与する周期タスク
  - 増援召喚: HP 閾値を下回ると一定確率で増援をスポーン（場合によって Enchanted 化の可能性あり）
  - 建築/登攀 AI: 高さ差を埋めるために一時ブロック（苔石やブラックストーン）を設置して登る処理
  - 装備付与: Leather/IRON などの装備を付与（指揮官はより強力）

- スケルトン系（Skeleton / WitherSkeleton）
  - 偏差射撃・直線スナイプ: プレイヤーの動きを先読みして直線的に弾速を上げた矢を放つ
  - ホーミング矢: 一定確率で低速ホーミング矢を発射し、ターゲットを追尾
  - 近接時には速度強化（ポーション効果）や近接武器に持ち替える行動
  - 装備面: 種族に応じたメイン武器（弓／石剣）やトリム付き防具

- クリーパー（Creeper）
  - サイドステップ: プレイヤーに見られている時に横方向へ回避（視線ベースの判断）
  - 背後フェイズ（Backstab）: プレイヤーの背後にいると速度を上げて不意打ちを仕掛ける
  - バースト突進: 一定距離帯で突進して接触で即時爆発する攻撃パターン
  - 爆発後に毒ガス (AreaEffectCloud) を残すロジック（持続デバフ）

- クモ（Spider / CaveSpider）
  - ウェブトラップ: 攻撃時にプレイヤー足元にクモの巣（Cobweb）を設置し一定時間維持
  - 飛びかかり（Leap）: ターゲットとの距離条件で突進・ジャンプ攻撃を行う
  - 群れ呼び出し: ウェブ設置時や攻撃時に近隣のクモを呼び寄せる

- ピグリン系 / ピグリン・ブルート（Piglin / PiglinBrute / ZombifiedPiglin）
  - ゴールド装飾やネザライト（ブルート）装備の付与、装飾（Trim）による見た目強化
  - 攻撃パターン: 斧や金武器を持ち替え、近接重視の攻撃を行う
  - ブルートは高威力・ネザライト装備でボス感を演出し、群れを呼ぶ能力を持つ

- ウィザースケルトン（WitherSkeleton）
  - ネザライト/特別装備（ヘルメット/チェスト）と弓運用のバランス切替（遠距離・近距離）
  - 弾速・射撃ロジックの強化（通常スケルトンより強力）

- 増援（Reinforcement）
  - リーダー（指揮官）が低HPになった際に周囲へ増援をスポーン。増援は確率で Enchanted 化することがあり、名前や装備で差別化される

- 建築 AI（全体共通の補助）
  - 高低差を埋めるために一時ブロックを設置して進行する。設置ブロックは後で自動的に壊れる/消える管理を行う
  - ブロックにダメージ表現（ブロックダメージパケット）を送信し、段階的に崩す

- 拾得 AI
  - 周囲の良い装備（武器・防具）を検出して拾いに行く行動。拾うことで個体の装備が強化される

- キャンプファイア回復
  - オンラインプレイヤー周辺の焚き火（Campfire）が点火されている場合、プレイヤーを徐々に回復するタスクを持つ

---

### Entity Health System（ダメージ/回復インジケーター & HPゲージ）
- 概要: プレイヤーが視線を向けたエンティティのHPをアクションバーで表示し、ダメージや回復を見やすく演出するインジケーターを出します。
- 機能:
  - ダメージ/回復量を小さな浮遊テキスト（TextDisplay）として表示（観測者単位で表示管理）
  - ターゲットの HP をアクションバーに表示（ゲージ表現）
- 設定: 表示距離（health-bar.view-distance）、表示持続時間（health-bar.display-ticks）、共有表示範囲（damage-indicator.share-continuous-damage.range）など。
- 有効化: `features.entity-health.enabled` とコマンド `/rppacks entityhealth <enable|disable>`。

実装ノート: `EntityHealthSystem` が RayTrace で視線先を検出、定期タスクでアクションバー更新とテキスト表示を行います。負荷対策として更新間隔や表示レンジが設定可能。

---

## コマンド一覧
- /rppacks
  - 説明: バージョンや簡易情報を表示
- /rppacks help
  - 説明: すべての使用可能コマンド一覧を表示
- /rppacks status
  - 説明: 各機能の現在状態を表示（管理者専用）
  - 権限: rppacks.admin
- /rppacks reload
  - 説明: 設定ファイルを再読み込み（player.yml / lang.yml の生成も行う）
  - 権限: rppacks.admin
- /rppacks sneakgrow <enable|disable|set>
  - 説明: スニーク成長の有効化/無効化および成功率・半径の動的設定
  - 権限: rppacks.admin（切替）、スニーク自体は `rascraft.sneakgrow` が許可されていれば使用可能
- /rppacks trail
  - 説明: 足あとメニューを開く（引数なしで GUI を開く）。管理者は対象プレイヤーの装備変更や状態確認が可能。
  - 権限: rppacks.trail（GUI） / rppacks.admin（他プレイヤー操作）
- /rppacks tpeffect <enable|disable>
  - 説明: テレポートエフェクトの有効/無効切替
  - 権限: rppacks.admin
- /rppacks enchantedmobs <enable|disable>
  - 説明: Enchanted Mobs 機能の有効/無効切替
  - 権限: rppacks.admin
- /rppacks entityhealth <enable|disable>
  - 説明: Entity Health System の有効/無効切替
  - 権限: rppacks.admin
- /rppacks spawn <zombie|skeleton|creeper|spider>
  - 説明: 実行地点に指定モブを召喚し、Enchanted 化（管理者用）
  - 権限: rppacks.admin

---

## 権限（Permissions）
- rppacks.trail : 足あとメニューを開く（default: true）
- rascraft.sneakgrow : スニーク成長の利用（default: true）
- rppacks.vip : VIP 専用エフェクトの使用（default: op）
- rppacks.admin : 管理者コマンドの使用（default: op）
- rppacks.* : 上記すべての権限を含む

（`plugin.yml` に定義されています。詳細は該当ファイルを参照してください。）

---

## 主な設定項目（config.yml の抜粋と説明）
以下は主要な設定キーの簡易説明です。詳細は `src/main/resources/config.yml` を参照してください。

- sneak-grow:
  - enabled: スニーク成長を有効にするか
  - success-chance: 成功確率（0.0〜1.0）
  - radius: 効果半径（ブロック）
  - growable-blocks: ブロックごとの成長方式（AGEABLE/STACK/BONEMEAL）

- footprint-trail:
  - enabled: 足あと機能を有効にするか
  - density: デフォルト密度
  - interval: 何ブロック動くごとにパーティクルを出すか
  - allowed-effects / vip-effects: メニューに表示するエフェクト一覧（密度指定可）
  - colored-effects: DUST/NOTE 等の特殊設定（色・サイズ・密度）
  - icons: GUI に表示するアイコン素材名

- teleport-effect:
  - enabled, delay-ticks, from-particle, to-particle

- enchanted-mobs:
  - enabled, enchanted-spawn-chance
  - stats, particles, navigation, skeleton, zombie, creeper, spider, climbing, looting, campfire-healing など多数の細かいパラメータ

- damage-indicator / health-bar / features.entity-health:
  - インジケーター共有範囲、HPゲージの視認距離と維持時間、有効化フラグ等

注: ほとんどのパラメータはデフォルトが設定されており、運用中に `/rppacks reload` で反映できます。

---

## リソースファイル
- `config.yml` : 動作パラメータの全設定
- `lang.yml` : メッセージや GUI 表示文言（prefix / trail-menu-title など）
- `player.yml` : プレイヤーごとのトレイル設定を保存するファイル（自動生成）
- `plugin.yml` : プラグインメタ情報とコマンド/権限定義

---

## 導入手順
1. 生成した `RPPacks.jar` をサーバーの `plugins/` フォルダに配置します。
2. サーバーを起動すると `config.yml`, `lang.yml`, `player.yml`（初回のみ）が自動生成されます。
3. 必要に応じて `config.yml` を編集し、`/rppacks reload` で設定を反映します。

簡単なテスト手順:
- `/rppacks` でプラグインが稼働しているか確認
- `/rppacks trail` で GUI を開き、エフェクトを選択（`rppacks.trail` が必要）
- スニーク成長を試す: スニークして作物の近くに立つ（`rascraft.sneakgrow` が必要）
- エンチャントモブや HP 表示は config と権限を確認して機能を有効にしてください。

---

## よくある質問 / トラブルシュート
Q: エフェクトが表示されない
- A: `config.yml` の `footprint-trail.enabled` が true になっているか、対象プレイヤーが `rppacks.trail` の権限を持っているか確認してください。
- サーバー側のパーティクル設定やクライアント設定（描画距離）によって視認できない場合があります。

Q: Enchanted Mobs の動作が重い
- A: `enchanted-mobs.enabled` をオフにするか、個別パラメータ（索敵距離 / オーラ更新間隔 / タスク周期）を調整してください。

Q: 一時的に設置されたブロックが残る
- A: プラグイン停止時に自動でクリアするはずですが、万が一残っていたらサーバー側で手動で削除してください（苔石など）。次回リロードで掃除されます。

---

## 開発者 / ライセンス
- 開発者: Kyamy
- 本プラグインは RAScraft(play.akabox.net) 向けに設計されています。ソースはプロジェクト内に含まれており、改変/配布はライセンスに従って行ってください。

---

