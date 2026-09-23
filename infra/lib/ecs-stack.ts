import { Stack, StackProps, Duration, CfnOutput } from "aws-cdk-lib";
import { Vpc, SecurityGroup, SubnetType } from "aws-cdk-lib/aws-ec2";
import {
  Cluster,
  FargateTaskDefinition,
  ContainerImage,
  Protocol,
  LogDrivers,
  FargateService,
} from "aws-cdk-lib/aws-ecs";
import { PolicyStatement, Effect } from "aws-cdk-lib/aws-iam";
import { LogGroup, RetentionDays } from "aws-cdk-lib/aws-logs";
import { Construct } from "constructs";
import * as path from "path";

export interface EcsStackProps extends StackProps {
  vpc: Vpc;
  waterdogSg: SecurityGroup;
  bdsSg: SecurityGroup;
}

export class EcsStack extends Stack {
  constructor(scope: Construct, id: string, props: EcsStackProps) {
    super(scope, id, props);

    const cluster = new Cluster(this, "Cluster", { vpc: props.vpc });

    // --- BDS world task definition -----------------------------------
    // No custom image/entrypoint needed: itzg/docker-minecraft-bedrock-server
    // maps TRANSPORT into server.properties as of
    // https://github.com/itzg/docker-minecraft-bedrock-server/pull/675, so
    // WorldControlPlugin just overrides TRANSPORT=raknet per RunTask call.
    const bdsTaskDefinition = new FargateTaskDefinition(this, "BdsTaskDefinition", {
      cpu: 1024,
      memoryLimitMiB: 2048,
    });
    const bdsContainer = bdsTaskDefinition.addContainer("bds", {
      containerName: "bds",
      image: ContainerImage.fromRegistry("itzg/minecraft-bedrock-server"),
      environment: {
        EULA: "TRUE",
        ONLINE_MODE: "false", // WaterdogPE handles Xbox Live auth; downstream must be offline mode
        ALLOW_LIST: "false", // allow-list=true (image default) + online-mode=false makes BDS refuse to start
        ALLOW_CHEATS: "true", // otherwise commandsEnabled is false and even Waterdog's /server command breaks
      },
      // Confirmed against itzg/minecraft-bedrock-server:latest's own Dockerfile HEALTHCHECK
      // (docker inspect), not just the image's built-in default, since ECS needs its own
      // native healthCheck to report task health for DescribeTasks polling.
      healthCheck: {
        command: ["CMD-SHELL", "/usr/local/bin/mc-monitor status-bedrock --host 127.0.0.1 --port 19132"],
        interval: Duration.seconds(15),
        timeout: Duration.seconds(10),
        retries: 3,
        startPeriod: Duration.seconds(60),
      },
      logging: LogDrivers.awsLogs({
        streamPrefix: "bds",
        logGroup: new LogGroup(this, "BdsLogGroup", {
          retention: RetentionDays.THREE_DAYS,
        }),
      }),
    });
    bdsContainer.addPortMappings({
      containerPort: 19132,
      protocol: Protocol.UDP,
    });

    // --- Waterdog proxy task definition -------------------------------
    // Also carries the "lobby" world (see waterdog/config.yml) as a second
    // container in the same task, rather than running it as its own RunTask
    // instance like on-demand worlds. Two things that would otherwise cost
    // extra for an always-on world: it shares Waterdog's public IP (each
    // Fargate task with a public IP is billed for it, ~$0.005/hour), and it
    // shares Waterdog's task-level CPU/memory instead of adding its own on
    // top - Waterdog itself is a lightweight packet relay, so there's no
    // need to size the task as proxy-cost-plus-BDS-cost; 1 vCPU / 2GB (the
    // same size as a standalone BDS world task) covers both.
    const waterdogTaskDefinition = new FargateTaskDefinition(this, "WaterdogTaskDefinition", {
      cpu: 1024,
      memoryLimitMiB: 2048,
    });
    waterdogTaskDefinition.addContainer("waterdog", {
      containerName: "waterdog",
      image: ContainerImage.fromAsset(path.join(__dirname, "..", "..", "waterdog")),
      environment: {
        ECS_CLUSTER_ARN: cluster.clusterArn,
        BDS_TASK_DEFINITION_ARN: bdsTaskDefinition.taskDefinitionArn,
        BDS_CONTAINER_NAME: "bds",
        SUBNET_IDS: props.vpc.publicSubnets.map((subnet) => subnet.subnetId).join(","),
        BDS_SECURITY_GROUP_ID: props.bdsSg.securityGroupId,
      },
      portMappings: [
        { containerPort: 19132, protocol: Protocol.UDP },
        { containerPort: 8081, protocol: Protocol.TCP },
      ],
      logging: LogDrivers.awsLogs({
        streamPrefix: "waterdog",
        logGroup: new LogGroup(this, "WaterdogLogGroup", {
          retention: RetentionDays.THREE_DAYS,
        }),
      }),
    });

    // Same network namespace as the waterdog container above, so it's
    // reachable at 127.0.0.1 - on 19133, not 19132, since that port is
    // already taken by Waterdog's own client-facing listener in this task.
    const lobbyContainer = waterdogTaskDefinition.addContainer("lobby", {
      containerName: "lobby",
      image: ContainerImage.fromRegistry("itzg/minecraft-bedrock-server"),
      environment: {
        EULA: "TRUE",
        SERVER_NAME: "lobby",
        LEVEL_NAME: "lobby",
        ONLINE_MODE: "false",
        ALLOW_LIST: "false",
        ALLOW_CHEATS: "true",
        TRANSPORT: "raknet",
        SERVER_PORT: "19133",
      },
      healthCheck: {
        command: ["CMD-SHELL", "/usr/local/bin/mc-monitor status-bedrock --host 127.0.0.1 --port 19133"],
        interval: Duration.seconds(15),
        timeout: Duration.seconds(10),
        retries: 3,
        startPeriod: Duration.seconds(60),
      },
      logging: LogDrivers.awsLogs({
        streamPrefix: "lobby",
        logGroup: new LogGroup(this, "LobbyLogGroup", {
          retention: RetentionDays.THREE_DAYS,
        }),
      }),
    });
    lobbyContainer.addPortMappings({
      containerPort: 19133,
      protocol: Protocol.UDP,
    });

    // Least-privilege: RunTask/StopTask/DescribeTasks scoped to this specific
    // BDS task definition family and cluster, plus PassRole for exactly the two
    // roles the BDS task definition carries (RunTask requires the caller to be
    // able to pass any IAM role a task definition references).
    waterdogTaskDefinition.taskRole.addToPrincipalPolicy(
      new PolicyStatement({
        effect: Effect.ALLOW,
        actions: ["ecs:RunTask"],
        resources: [bdsTaskDefinition.taskDefinitionArn],
        conditions: {
          ArnEquals: { "ecs:cluster": cluster.clusterArn },
        },
      })
    );
    waterdogTaskDefinition.taskRole.addToPrincipalPolicy(
      new PolicyStatement({
        effect: Effect.ALLOW,
        actions: ["ecs:StopTask", "ecs:DescribeTasks"],
        resources: ["*"],
        conditions: {
          ArnEquals: { "ecs:cluster": cluster.clusterArn },
        },
      })
    );
    waterdogTaskDefinition.taskRole.addToPrincipalPolicy(
      new PolicyStatement({
        effect: Effect.ALLOW,
        actions: ["iam:PassRole"],
        resources: [
          bdsTaskDefinition.taskRole.roleArn,
          bdsTaskDefinition.executionRole!.roleArn,
        ],
      })
    );

    // Single instance, no load balancer (explicitly out of scope - see plan).
    // Its public IP is reassigned if the task is ever replaced; there is no
    // Elastic IP / Route53 update wired up for that yet.
    const waterdogService = new FargateService(this, "WaterdogService", {
      cluster,
      taskDefinition: waterdogTaskDefinition,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: SubnetType.PUBLIC },
      securityGroups: [props.waterdogSg],
    });

    // The service has no fixed public IP (see above) - these outputs are
    // just enough to look the current one up, e.g.:
    //   aws ecs list-tasks --cluster <ClusterName> --service-name <ServiceName>
    //   aws ecs describe-tasks --cluster <ClusterName> --tasks <task-arn>
    //   aws ec2 describe-network-interfaces --network-interface-ids <eni-id>
    new CfnOutput(this, "ClusterName", { value: cluster.clusterName });
    new CfnOutput(this, "ServiceName", { value: waterdogService.serviceName });
  }
}
