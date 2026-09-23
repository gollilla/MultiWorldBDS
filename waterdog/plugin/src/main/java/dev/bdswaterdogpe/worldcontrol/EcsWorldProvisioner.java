package dev.bdswaterdogpe.worldcontrol;

import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.ecs.model.Task;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs BDS worlds as standalone ECS Fargate tasks (RunTask), one per world.
 * Worlds are not backed by an ECS Service since each is provisioned/torn down
 * on demand rather than kept at a fixed desired count.
 */
public class EcsWorldProvisioner implements WorldProvisioner {

    private static final Duration HEALTHY_TIMEOUT = Duration.ofMinutes(3);

    private final EcsClient ecs;
    private final Logger logger;
    private final String clusterArn;
    private final String taskDefinitionArn;
    private final String containerName;
    private final List<String> subnetIds;
    private final String securityGroupId;

    private final Map<String, String> worldNameToTaskArn = new ConcurrentHashMap<>();

    public EcsWorldProvisioner(Logger logger) {
        this.logger = logger;
        this.ecs = EcsClient.builder()
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
                .build();
        this.clusterArn = requireEnv("ECS_CLUSTER_ARN");
        this.taskDefinitionArn = requireEnv("BDS_TASK_DEFINITION_ARN");
        this.containerName = System.getenv().getOrDefault("BDS_CONTAINER_NAME", "bds");
        this.subnetIds = Arrays.asList(requireEnv("SUBNET_IDS").split(","));
        this.securityGroupId = requireEnv("BDS_SECURITY_GROUP_ID");
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    /**
     * Starts a new BDS task for the given world and blocks until it is
     * reachable, returning the address WaterdogPE should dial.
     */
    @Override
    public InetSocketAddress startWorld(String name, String gamemode, String worldType) throws InterruptedException {
        if ("void".equalsIgnoreCase(worldType)) {
            // DockerWorldProvisioner seeds a void world by docker cp-ing a
            // pre-built one into place before first start; there's no EFS
            // equivalent of that wired up here yet.
            throw new IllegalArgumentException("worldType=void is not supported by the ECS provisioner yet");
        }
        RunTaskResponse runResponse = this.ecs.runTask(RunTaskRequest.builder()
                .cluster(this.clusterArn)
                .taskDefinition(this.taskDefinitionArn)
                .launchType(LaunchType.FARGATE)
                .count(1)
                .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                .subnets(this.subnetIds)
                                .securityGroups(this.securityGroupId)
                                .assignPublicIp(AssignPublicIp.DISABLED)
                                .build())
                        .build())
                .overrides(TaskOverride.builder()
                        .containerOverrides(ContainerOverride.builder()
                                .name(this.containerName)
                                .environment(
                                        keyValue("SERVER_NAME", name),
                                        keyValue("LEVEL_NAME", name),
                                        keyValue("GAMEMODE", gamemode),
                                        keyValue("LEVEL_TYPE", "flat".equalsIgnoreCase(worldType) ? "FLAT" : "DEFAULT"),
                                        // TRANSPORT is correctly mapped into server.properties by itzg's image
                                        // as of https://github.com/itzg/docker-minecraft-bedrock-server/pull/675;
                                        // without this BDS defaults to NetherNet, which WaterdogPE's RakNet
                                        // downstream connection cannot reach.
                                        keyValue("TRANSPORT", "raknet"))
                                .build())
                        .build())
                .build());

        if (!runResponse.failures().isEmpty()) {
            throw new IllegalStateException("RunTask failed: " + runResponse.failures());
        }

        String taskArn = runResponse.tasks().get(0).taskArn();
        this.worldNameToTaskArn.put(name, taskArn);

        Task task = this.waitUntilHealthy(taskArn);
        String privateIp = this.extractPrivateIp(task)
                .orElseThrow(() -> new IllegalStateException("Task " + taskArn + " has no private IP"));
        return new InetSocketAddress(privateIp, 19132);
    }

    @Override
    public void stopWorld(String name) {
        String taskArn = this.worldNameToTaskArn.remove(name);
        if (taskArn == null) {
            return;
        }
        this.ecs.stopTask(StopTaskRequest.builder()
                .cluster(this.clusterArn)
                .task(taskArn)
                .reason("world removed via WorldControl API")
                .build());
    }

    private Task waitUntilHealthy(String taskArn) throws InterruptedException {
        Instant deadline = Instant.now().plus(HEALTHY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            DescribeTasksResponse response = this.ecs.describeTasks(DescribeTasksRequest.builder()
                    .cluster(this.clusterArn)
                    .tasks(taskArn)
                    .build());
            Task task = response.tasks().get(0);
            this.logger.debug("Task " + taskArn + " status=" + task.lastStatus() + " health=" + task.healthStatus());

            if (task.lastStatus().equals("RUNNING") && task.healthStatus() == HealthStatus.HEALTHY) {
                return task;
            }
            if (task.lastStatus().equals("STOPPED")) {
                throw new IllegalStateException("Task " + taskArn + " stopped before becoming healthy: "
                        + task.stoppedReason());
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("Task " + taskArn + " did not become healthy within " + HEALTHY_TIMEOUT);
    }

    private Optional<String> extractPrivateIp(Task task) {
        return task.attachments().stream()
                .filter(attachment -> "ElasticNetworkInterface".equals(attachment.type()))
                .flatMap(attachment -> attachment.details().stream())
                .filter(detail -> "privateIPv4Address".equals(detail.name()))
                .map(KeyValuePair::value)
                .findFirst();
    }

    private static KeyValuePair keyValue(String name, String value) {
        return KeyValuePair.builder().name(name).value(value).build();
    }
}
