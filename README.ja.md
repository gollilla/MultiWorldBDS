# BDS-waterdogpe

[English](README.md)

素のMinecraft Bedrock Dedicated Server(BDS)上で、PMMPのような動的マルチワールドを実現する仕組み。[WaterdogPE](https://github.com/WaterdogPE/WaterdogPE)とAWS ECS Fargateの上に構築している。プレイヤーは単一のWaterdogPEプロキシに接続し、ワールドは必要に応じて個別のBDSタスクとしてプロビジョニング/破棄される。プロキシを再起動する必要は一切ない。

## なぜこの構成か

BDSには複数ワールドを同時にロードする概念もプラグインAPIも無く、ワールドごとに完全に別のサーバープロセスになる。WaterdogPEは、PMMPのようなプラグイン拡張可能なサーバーが標準で持っている「1つのアドレスの裏に複数ワールド」という役割を肩代わりし、そのプラグインAPIで実行中にダウンストリームサーバーを登録・解除できる。このリポジトリでは、それをECS Fargateに接続し、「タスクをプロビジョニングし、ヘルスチェックが通るのを待ち、プロキシに登録する」という一連の作業をAPI呼び出し1回で完結させている。

## アーキテクチャ

```
Bedrockクライアント
      │ UDP 19132 (RakNet)
      ▼
WaterdogPEタスク (ECS Fargate, パブリック)
  ├─ WaterdogPEプロキシ本体
  └─ WorldControlプラグイン (同一JVM内)
        - GET/POST/DELETE /worlds (TCP 8081、VPC内部のみ)
        - AWS ECSのRunTask/DescribeTasks/StopTaskを呼んでワールドを
          プロビジョニング/破棄し、その後ProxyServer.registerServerInfo()/
          removeServerInfo()でプロキシへの登録・解除を行う
          (プロキシの再起動は不要)
        │
        │ RunTask / StopTask (IAMタスクロール。このクラスターと
        │ BDSタスク定義に限定したスコープ)
        ▼
BDSワールドタスク (ECS Fargate, プライベート -
Waterdogタスクのセキュリティグループからのみ到達可能)
```

BDS側からの呼び返しは一切無い。Bedrock Dedicated Serverバイナリにはプラグイン機構が無いため、「ワールドを追加する」というトリガーは常に外側(WorldControl API、またはhakomcのスクリプトが*別の*稼働中ワールドからそのAPIを呼ぶ形。後述)からしか発生し得ない。

## 構成

- **`waterdog/`** — WaterdogPEプロキシのイメージと、AWS SDK経由でワールドをプロビジョニングする`WorldControl`プラグイン(Java/Gradle)。プロキシ設定は[`waterdog/config.yml`](waterdog/config.yml)、プラグイン本体は[`waterdog/plugin/src`](waterdog/plugin/src)を参照。
- **`infra/`** — AWS CDK(TypeScript)スタック。VPC(パブリックサブネットのみ、NAT Gateway無し — ロードバランサや複数インスタンスによる高可用性はスコープ外なので不要)、セキュリティグループ、ECSクラスター/タスク定義、そしてWorldControlのAWS操作権限をこのクラスターへの`RunTask`/`StopTask`/`DescribeTasks`と、BDSタスクの2つのロールへの`PassRole`だけに絞ったIAMポリシー。
- **`hakomc/`** — [hakomc](https://github.com/hakomc/hakomc)(Bedrock Scripting API)の開発環境。[hakomc-server](https://github.com/hakomc/hakomc-server)からブートストラップ。`src/worldControl.ts`はWorldControl APIの小さなクライアントで、*あるワールド*上で動いているビヘイビアパックから、`@minecraft/server-net`のHTTPクライアント(BDS側スクリプトから外部HTTP呼び出しを行う唯一の手段)経由で*別のワールド*を追加・削除できる。

## 現状

IaCとアプリケーションコードのみ — **ここには何もデプロイされていない**。`cdk synth`とGradle/npm両方のビルドが成功することは確認済み。実際に何を確認したか(生成されたjarの中身、synthesizeされたIAMポリシー、クリーンインストールでのビルド等)と、デプロイ時にまだ確認が必要な仮定の部分は、コミット履歴を参照。

コードコメントに残している、既知の粗い部分:

- `@minecraft/server-net`と`@minecraft/server-admin`はどちらもプレリリース版のBedrockモジュール。manifestの依存バージョン文字列の慣習や、`worldControl.ts`が`worldControlApiUrl`を読み取っているサーバー側の`variables`設定ファイル/フラグの正確な仕様は、実デプロイ前に最新のBedrockドキュメントで再確認する価値がある。
- WaterdogPEのFargateタスクにロードバランサが無いため、タスクが置き換わるとパブリックIPが変わる。Elastic IPやRoute53の自動更新は未整備。
- BDSがデフォルトでNetherNetトランスポートになる問題(WaterdogPEのRakNetダウンストリーム接続が届かない)は、RunTaskのコンテナオーバーライドで`TRANSPORT=raknet`を渡すことで対処している。itzgのイメージは[itzg/docker-minecraft-bedrock-server#675](https://github.com/itzg/docker-minecraft-bedrock-server/pull/675)以降、これを正しく`server.properties`にマッピングしてくれる。

## ビルド

```bash
# WaterdogPE + プラグインのイメージ (マルチステージ: プラグインjarをビルドしてから
# プロキシイメージを組み立てる)
docker build -t waterdogpe ./waterdog

# CDKインフラ — 型チェックとCloudFormationテンプレートの生成のみ、
# デプロイは行わない
cd infra && npm install && npx cdk synth

# hakomc開発環境 + WorldControl操作ライブラリ
cd hakomc && npm install && npm run build
```

## ライセンス

このリポジトリ自体のコード(`waterdog/`、`infra/`)についてはまだ未決定。`hakomc/`は、ブートストラップ元の[hakomc-server](https://github.com/hakomc/hakomc-server)テンプレートから継承した独自のGPLv3ライセンスを持つ。
