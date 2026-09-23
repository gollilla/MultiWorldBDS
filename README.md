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
  plugin (Java/Gradle) that provisions worlds, via either the AWS SDK
  or the `docker` CLI depending on the `PROVISIONER` env var (`ecs` or
  `docker` - see `WorldProvisioner`/`EcsWorldProvisioner`/
  `DockerWorldProvisioner` in
  [`waterdog/plugin/src`](waterdog/plugin/src)). `waterdog/config.yml`
  is the proxy config shared by both deployment targets below, aside
  from `lobby`'s address.
- **`infra/`** - AWS CDK (TypeScript) stack, one deployment target
  (`PROVISIONER=ecs`): VPC (public subnets only, no NAT Gateway -
  there's no load balancer or multi-instance HA in scope, so it isn't
  needed), security groups, the ECS cluster/task definitions, and the
  IAM policy that scopes WorldControl's AWS access to
  `RunTask`/`StopTask`/`DescribeTasks` on this cluster and `PassRole`
  on exactly the BDS task's two roles.
- **`docker/`** - Docker Compose, the other deployment target
  (`PROVISIONER=docker`): WaterdogPE plus a `lobby` world, with the
  host's Docker socket mounted into the Waterdog container so it can
  `docker run` new world containers directly. That grants whoever can
  reach the WorldControl API full control of the host's Docker daemon
  - there's no IAM-style scoping possible for a Docker socket, so this
  trades the ECS path's least-privilege IAM policy for not having to
  build and run a separate provisioning service. Keep `:8081`
  loopback-only (see `docker/docker-compose.yml`), same reasoning as
  the ECS path's dedicated control security group.
- **the repo root** (`package.json`, `src/`, `worlds/`, ...) - a
  [hakomc](https://github.com/hakomc/hakomc) dev environment (Bedrock
  Scripting API), bootstrapped from
  [hakomc-server](https://github.com/hakomc/hakomc-server).
  `src/worldControl.ts` is a small client for the WorldControl API so
  a behavior pack running on *one* world can add or remove *other*
  worlds, using `@minecraft/server-net`'s HTTP client (the only way to
  make outbound HTTP calls from BDS-side scripts).
  [`examples/worldControlCommands.ts`](examples/worldControlCommands.ts)
  wires it up to in-game slash commands
  (`/hakomc:worldadd`/`worldremove`/`worldlist`).

### Usage

Once deployed, everything goes through the WorldControl API on the
Waterdog task (`<waterdog-ip>:8081` - VPC-internal only, see
[Architecture](#architecture)). Two ways to call it:

**Directly**, e.g. from something with network access to that VPC:

```bash
curl -X POST http://<waterdog-ip>:8081/worlds -d "name=survival2&gamemode=survival"
curl http://<waterdog-ip>:8081/worlds
curl -X DELETE http://<waterdog-ip>:8081/worlds/survival2
```

**From a BDS-side script**, using this repo's `worldControl.ts`
(bundled as the `hakomc-world` package). Install it straight from this
repo:

```bash
npm install git+https://github.com/gollilla/MultiWorldBDS.git
```

```ts
import { addWorld, removeWorld, listWorlds } from 'hakomc-world';

await addWorld('survival2', 'survival');
console.log(await listWorlds());
await removeWorld('survival2');
```

This lets a behavior pack running on one world add or remove *other*
worlds - e.g. a hub world with a "create world" command. The base URL
is read from this server's `variables` config as `worldControlApiUrl`
(see the comment in `src/worldControl.ts`).

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

### Deploying (ECS Fargate)

Prerequisites: AWS credentials with permission to create the resources
in `infra/lib` (VPC, ECS, IAM roles, security groups, log groups), a
region set (`aws configure` default, `--profile`, or `CDK_DEFAULT_REGION`),
and Docker running locally (the Waterdog image is built from source at
deploy time). **This creates real, billed AWS resources.**

```bash
cd infra
npm install

# One-time per AWS account + region
npx cdk bootstrap

npx cdk deploy --all
```

`cdk deploy` prints `ClusterName` and `ServiceName` outputs. There's no
load balancer, so the Waterdog task's public IP isn't fixed - it's
reassigned if the task is ever replaced - use the outputs to look up
the current one:

```bash
TASK_ARN=$(aws ecs list-tasks --cluster <ClusterName> --service-name <ServiceName> --query 'taskArns[0]' --output text)
ENI_ID=$(aws ecs describe-tasks --cluster <ClusterName> --tasks "$TASK_ARN" --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text)
aws ec2 describe-network-interfaces --network-interface-ids "$ENI_ID" --query 'NetworkInterfaces[0].Association.PublicIp' --output text
```

To tear everything down (stops billing; any BDS worlds started via
`addWorld`/`POST /worlds` also need to be stopped first, since they're
separate ad-hoc tasks the stacks don't track):

```bash
npx cdk destroy --all
```

### Deploying (Docker Compose)

The self-hosted alternative - no AWS account needed. Mounts the host's
Docker socket into the Waterdog container (see the `docker/` note in
[Layout](#layout) for the tradeoff that implies).

```bash
cd docker
docker compose up -d --build
```

Connect to this host on UDP 19132; `lobby` is the default world. Add/remove
more the same way as [Usage](#usage) describes, against
`http://127.0.0.1:8081`.

```bash
docker compose down   # world data under docker/data/ is kept
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

- **`waterdog/`** — WaterdogPEプロキシのイメージと、`PROVISIONER`環境変数(`ecs`または`docker`)に応じてAWS SDKまたは`docker` CLIでワールドをプロビジョニングする`WorldControl`プラグイン(Java/Gradle。[`waterdog/plugin/src`](waterdog/plugin/src)の`WorldProvisioner`/`EcsWorldProvisioner`/`DockerWorldProvisioner`を参照)。`waterdog/config.yml`は、`lobby`のアドレスを除いて以下2つのデプロイ先で共通のプロキシ設定。
- **`infra/`** — AWS CDK(TypeScript)スタック、デプロイ先の1つ(`PROVISIONER=ecs`)。VPC(パブリックサブネットのみ、NAT Gateway無し — ロードバランサや複数インスタンスによる高可用性はスコープ外なので不要)、セキュリティグループ、ECSクラスター/タスク定義、そしてWorldControlのAWS操作権限をこのクラスターへの`RunTask`/`StopTask`/`DescribeTasks`と、BDSタスクの2つのロールへの`PassRole`だけに絞ったIAMポリシー。
- **`docker/`** — Docker Compose、もう1つのデプロイ先(`PROVISIONER=docker`)。WaterdogPEと`lobby`ワールドに加え、Waterdogコンテナにホストの Dockerソケットを直接マウントして、新しいワールドコンテナを`docker run`できるようにしている。これは、WorldControl APIに到達できる者にホストのDockerデーモンの全権限を渡すことを意味する — DockerソケットにはIAMのような権限の絞り込みができないので、専用のプロビジョニングサービスを別途構築・運用しない代わりに、ECS版の最小権限IAMポリシーというメリットを手放すトレードオフ。`:8081`はloopback限定のままにしておくこと([`docker/docker-compose.yml`](docker/docker-compose.yml)参照。理由はECS版の専用制御セキュリティグループと同じ)。
- **リポジトリのルート**(`package.json`、`src/`、`worlds/`など) — [hakomc](https://github.com/hakomc/hakomc)(Bedrock Scripting API)の開発環境。[hakomc-server](https://github.com/hakomc/hakomc-server)からブートストラップ。`src/worldControl.ts`はWorldControl APIの小さなクライアントで、*あるワールド*上で動いているビヘイビアパックから、`@minecraft/server-net`のHTTPクライアント(BDS側スクリプトから外部HTTP呼び出しを行う唯一の手段)経由で*別のワールド*を追加・削除できる。[`examples/worldControlCommands.ts`](examples/worldControlCommands.ts)がゲーム内スラッシュコマンド(`/hakomc:worldadd`/`worldremove`/`worldlist`)に繋いでいる。

### 使い方

デプロイ後は、すべてWaterdogタスク上のWorldControl API(`<waterdogのIP>:8081`、VPC内部のみ。[アーキテクチャ](#アーキテクチャ)参照)経由で操作する。呼び方は2通り。

**直接叩く**(そのVPCにネットワーク到達できる場所から):

```bash
curl -X POST http://<waterdogのIP>:8081/worlds -d "name=survival2&gamemode=survival"
curl http://<waterdogのIP>:8081/worlds
curl -X DELETE http://<waterdogのIP>:8081/worlds/survival2
```

**BDS側のスクリプトから**、このリポジトリの`worldControl.ts`(`hakomc-world`パッケージとしてバンドルされている)を使う場合。このリポジトリから直接インストールできる:

```bash
npm install git+https://github.com/gollilla/MultiWorldBDS.git
```

```ts
import { addWorld, removeWorld, listWorlds } from 'hakomc-world';

await addWorld('survival2', 'survival');
console.log(await listWorlds());
await removeWorld('survival2');
```

これにより、あるワールドで動いているビヘイビアパックから*別の*ワールドを追加・削除できる(例: ハブワールドに「ワールド作成」コマンドを置く、など)。ベースURLはこのサーバーの`variables`設定から`worldControlApiUrl`として読み込まれる(`src/worldControl.ts`のコメント参照)。

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

### デプロイ (ECS Fargate)

前提: `infra/lib`が作るリソース(VPC・ECS・IAMロール・セキュリティグループ・ロググループ)を作成できるAWS認証情報、リージョン設定(`aws configure`のデフォルト、`--profile`、または`CDK_DEFAULT_REGION`)、そしてローカルでDockerが起動していること(Waterdogイメージはデプロイ時にソースからビルドされる)。**これは実際に課金されるAWSリソースを作成します。**

```bash
cd infra
npm install

# AWSアカウント+リージョンごとに1回だけ
npx cdk bootstrap

npx cdk deploy --all
```

`cdk deploy`は`ClusterName`と`ServiceName`という出力を表示する。ロードバランサが無いため、Waterdogタスクのパブリックipは固定ではなく、タスクが置き換わるたびに変わる — この出力を使って現在のIPを調べられる:

```bash
TASK_ARN=$(aws ecs list-tasks --cluster <ClusterName> --service-name <ServiceName> --query 'taskArns[0]' --output text)
ENI_ID=$(aws ecs describe-tasks --cluster <ClusterName> --tasks "$TASK_ARN" --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text)
aws ec2 describe-network-interfaces --network-interface-ids "$ENI_ID" --query 'NetworkInterfaces[0].Association.PublicIp' --output text
```

全部破棄する場合(課金停止。`addWorld`/`POST /worlds`で起動したBDSワールドは、スタックの管理外の単発タスクなので、先に個別で止めておく必要がある):

```bash
npx cdk destroy --all
```

### デプロイ (Docker Compose)

自前ホストでの代替手段 — AWSアカウント不要。Waterdogコンテナにホストのdockerソケットをマウントする([構成](#構成)の`docker/`の項にあるトレードオフ参照)。

```bash
cd docker
docker compose up -d --build
```

このホストのUDP 19132に接続する。`lobby`がデフォルトワールド。追加・削除は[使い方](#使い方)と同じ要領で、`http://127.0.0.1:8081`に対して行う。

```bash
docker compose down   # docker/data/配下のワールドデータは残る
```
