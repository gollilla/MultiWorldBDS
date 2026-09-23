# BDS-waterdogpe

*[English](#english) / [日本語](#japanese)*

<a id="english"></a>
## English

A PMMP-style dynamic multi-world setup for vanilla Minecraft Bedrock
Dedicated Server (BDS), built on [WaterdogPE](https://github.com/WaterdogPE/WaterdogPE)
and AWS ECS Fargate. Players connect to a single WaterdogPE proxy; worlds
are provisioned/torn down on demand as separate BDS tasks, without ever
restarting the proxy.

### Why

BDS has no built-in concept of multiple loaded worlds or a plugin API -
each world is a separate server process. WaterdogPE fills the "multiple
worlds behind one address" role that a plugin-extensible server (like
PMMP) gets for free, and its plugin API can register/unregister
downstream servers at runtime. This repo wires that up to ECS Fargate so
a whole world - provisioning the task, waiting for it to become healthy,
registering it with the proxy - happens behind a single API call.

### Architecture

```mermaid
flowchart TD
    Client(["Bedrock client"]) -->|"UDP 19132 (RakNet)"| WaterdogTask

    WaterdogTask["WaterdogPE task (ECS Fargate, public)<br/>proxy + WorldControl plugin, same JVM"]

    WaterdogTask -->|"ECS RunTask / StopTask<br/>(scoped IAM role)"| BdsTasks

    subgraph BdsTasks["BDS world tasks (ECS Fargate, private)"]
        World["reachable only from the<br/>Waterdog task's security group"]
    end
```

Nothing calls back into BDS: the Bedrock Dedicated Server binary has no
plugin system, so "add a world" can only ever be triggered from outside
it (the WorldControl API, or hakomc scripts calling that API from
inside a *different* running world - see below).

### Layout

- **`waterdog/`** - the WaterdogPE proxy image and the `WorldControl`
  plugin (Java/Gradle) that provisions worlds via the AWS SDK. See
  [`waterdog/config.yml`](waterdog/config.yml) for the proxy config and
  [`waterdog/plugin/src`](waterdog/plugin/src) for the plugin.
- **`infra/`** - AWS CDK (TypeScript) stack: VPC (public subnets only,
  no NAT Gateway - there's no load balancer or multi-instance HA in
  scope, so it isn't needed), security groups, the ECS cluster/task
  definitions, and the IAM policy that scopes WorldControl's AWS access
  to `RunTask`/`StopTask`/`DescribeTasks` on this cluster and
  `PassRole` on exactly the BDS task's two roles.
- **the repo root** (`package.json`, `src/`, `worlds/`, ...) - a
  [hakomc](https://github.com/hakomc/hakomc) dev environment (Bedrock
  Scripting API), bootstrapped from
  [hakomc-server](https://github.com/hakomc/hakomc-server) and kept at
  the repo root (rather than nested in a subdirectory) so it can be
  installed directly via a plain `npm install git+https://...` -
  npm's git dependency support has no way to point at a subdirectory
  of a repo. `src/worldControl.ts` is a small client for the
  WorldControl API so a behavior pack running on *one* world can add
  or remove *other* worlds, using `@minecraft/server-net`'s HTTP
  client (the only way to make outbound HTTP calls from BDS-side
  scripts).

### Building

```bash
# WaterdogPE + plugin image (multi-stage: builds the plugin jar, then
# assembles the proxy image)
docker build -t waterdogpe ./waterdog

# CDK infra - type-checks and synthesizes CloudFormation templates,
# does not deploy anything
cd infra && npm install && npx cdk synth

# hakomc dev environment + WorldControl operations library (repo root)
npm install && npm run build
```

---

<a id="japanese"></a>
## 日本語

素のMinecraft Bedrock Dedicated Server(BDS)上で、PMMPのような動的マルチワールドを実現する仕組み。[WaterdogPE](https://github.com/WaterdogPE/WaterdogPE)とAWS ECS Fargateの上に構築している。プレイヤーは単一のWaterdogPEプロキシに接続し、ワールドは必要に応じて個別のBDSタスクとしてプロビジョニング/破棄される。プロキシを再起動する必要は一切ない。

### なぜこの構成か

BDSには複数ワールドを同時にロードする概念もプラグインAPIも無く、ワールドごとに完全に別のサーバープロセスになる。WaterdogPEは、PMMPのようなプラグイン拡張可能なサーバーが標準で持っている「1つのアドレスの裏に複数ワールド」という役割を肩代わりし、そのプラグインAPIで実行中にダウンストリームサーバーを登録・解除できる。このリポジトリでは、それをECS Fargateに接続し、「タスクをプロビジョニングし、ヘルスチェックが通るのを待ち、プロキシに登録する」という一連の作業をAPI呼び出し1回で完結させている。

### アーキテクチャ

```mermaid
flowchart TD
    Client(["Bedrockクライアント"]) -->|"UDP 19132 (RakNet)"| WaterdogTask

    WaterdogTask["WaterdogPEタスク (ECS Fargate, パブリック)<br/>プロキシ本体 + WorldControlプラグイン、同一JVM"]

    WaterdogTask -->|"ECS RunTask / StopTask<br/>(スコープ限定IAMロール)"| BdsTasks

    subgraph BdsTasks["BDSワールドタスク (ECS Fargate, プライベート)"]
        World["Waterdogタスクのセキュリティ<br/>グループからのみ到達可能"]
    end
```

BDS側からの呼び返しは一切無い。Bedrock Dedicated Serverバイナリにはプラグイン機構が無いため、「ワールドを追加する」というトリガーは常に外側(WorldControl API、またはhakomcのスクリプトが*別の*稼働中ワールドからそのAPIを呼ぶ形。後述)からしか発生し得ない。

### 構成

- **`waterdog/`** — WaterdogPEプロキシのイメージと、AWS SDK経由でワールドをプロビジョニングする`WorldControl`プラグイン(Java/Gradle)。プロキシ設定は[`waterdog/config.yml`](waterdog/config.yml)、プラグイン本体は[`waterdog/plugin/src`](waterdog/plugin/src)を参照。
- **`infra/`** — AWS CDK(TypeScript)スタック。VPC(パブリックサブネットのみ、NAT Gateway無し — ロードバランサや複数インスタンスによる高可用性はスコープ外なので不要)、セキュリティグループ、ECSクラスター/タスク定義、そしてWorldControlのAWS操作権限をこのクラスターへの`RunTask`/`StopTask`/`DescribeTasks`と、BDSタスクの2つのロールへの`PassRole`だけに絞ったIAMポリシー。
- **リポジトリのルート**(`package.json`、`src/`、`worlds/`など) — [hakomc](https://github.com/hakomc/hakomc)(Bedrock Scripting API)の開発環境。[hakomc-server](https://github.com/hakomc/hakomc-server)からブートストラップし、サブディレクトリではなくルートに置いている(npmのgit依存はリポジトリのサブディレクトリを指定する方法が無く、`npm install git+https://...`で直接インストールできるようにするため)。`src/worldControl.ts`はWorldControl APIの小さなクライアントで、*あるワールド*上で動いているビヘイビアパックから、`@minecraft/server-net`のHTTPクライアント(BDS側スクリプトから外部HTTP呼び出しを行う唯一の手段)経由で*別のワールド*を追加・削除できる。

### ビルド

```bash
# WaterdogPE + プラグインのイメージ (マルチステージ: プラグインjarをビルドしてから
# プロキシイメージを組み立てる)
docker build -t waterdogpe ./waterdog

# CDKインフラ — 型チェックとCloudFormationテンプレートの生成のみ、
# デプロイは行わない
cd infra && npm install && npx cdk synth

# hakomc開発環境 + WorldControl操作ライブラリ (リポジトリルート)
npm install && npm run build
```
