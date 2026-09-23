import { Stack, StackProps, Duration } from "aws-cdk-lib";
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
    const waterdogTaskDefinition = new FargateTaskDefinition(this, "WaterdogTaskDefinition", {
      cpu: 512,
      memoryLimitMiB: 1024,
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
    new FargateService(this, "WaterdogService", {
      cluster,
      taskDefinition: waterdogTaskDefinition,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: SubnetType.PUBLIC },
      securityGroups: [props.waterdogSg],
    });
  }
}
